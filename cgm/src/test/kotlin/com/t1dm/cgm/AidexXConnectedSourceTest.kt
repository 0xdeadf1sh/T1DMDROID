package com.t1dm.cgm

import com.t1dm.core.model.CgmLogKind
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Glue tests for AidexXConnectedSource: connect->handshake->check->realtime machine. */
class AidexXConnectedSourceTest {

    private val serial = "00000T1DM1"
    private val iv = AidexSessionCodec.iv(serial)
    private val masterKey = hex("000102030405060708090A0B0C0D0E0F")
    private val blob = hex("AA5C7CE51D0C86DB1B91728AB95B3E63E0")
    private val sess = hex("0F1E2D3C4B5A69788796A5B4C3D2E1F0")

    // A grid-aligned instant; tz = 0 so tsMs == gridNow.
    private val gridNow = 300_000L * 5_666_667L

    private fun source(
        t: FakeAidexGattTransport,
        repo: FakeCgmRepository,
        warmupWindowMin: Int = CgmConstants.WARMUP_WINDOW_MIN,
        onAuthenticated: suspend () -> Unit = {},
        nowMs: () -> Long = { gridNow },
        log: CgmSensorLog = CgmSensorLog.NONE,
    ) = AidexXConnectedSource(
        descriptor = AidexXConnectedSource.descriptorFor(serial).copy(warmupWindowMin = warmupWindowMin),
        serial = serial,
        transport = t,
        session = AidexSessionCodec,
        repository = repo,
        scope = CoroutineScope(Dispatchers.Unconfined),
        tzOffsetMinFor = { 0 },
        nowMs = nowMs,
        onAuthenticated = onAuthenticated,
        log = log,
    )

    @Test
    fun codec_matches_golden() {
        assertEquals("478ce91d196cf6cdcef562a3bf936a04", iv.hexStr())
        assertEquals("b79260f20c5ff20512c07cd7a0cc080f", AidexSessionCodec.askKey(serial).hexStr())
        val derived = AidexSessionCodec.deriveSession(serial, masterKey, blob)
        assertEquals("0f1e2d3c4b5a69788796a5b4c3d2e1f0", derived!!.hexStr())
        assertEquals("50f314", AidexSessionCodec.encryptFrame(sess, iv, byteArrayOf(0x10)).hexStr())
        assertEquals("10", AidexSessionCodec.decryptFrame(sess, iv, hex("50f314"))!!.hexStr())
    }

    @Test
    fun activated_handshake_reaches_live_and_maps_f003_reading() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)

        assertEquals(CgmSourceStatus.Idle, src.status.value)
        assertTrue(src.beginHandshake())
        assertTrue(t.connectCalled)
        assertEquals(CgmSourceStatus.Scanning, src.status.value)

        src.onGattEvent(AidexGattEvent.Connection(connected = true, statusOk = true, statusCode = 0))
        assertTrue("connect goes straight to discovery", t.discoverCalled)

        src.onGattEvent(AidexGattEvent.ServicesDiscovered(ok = true, hasCgmService = true))
        assertEquals(listOf(AidexChar.F001), t.notifyEnables)
        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F001, ok = true))
        assertEquals(listOf(AidexChar.F001, AidexChar.F002), t.notifyEnables)
        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F002, ok = true))
        assertEquals(AidexSessionCodec.askKey(serial).hexStr(), t.lastWrite(AidexChar.F001)!!.hexStr())

        src.onGattEvent(AidexGattEvent.Write(AidexChar.F001, ok = true))
        src.onGattEvent(AidexGattEvent.Notify(AidexChar.F001, masterKey))
        assertEquals(listOf(AidexChar.F002), t.reads)

        src.onGattEvent(AidexGattEvent.Read(AidexChar.F002, blob, ok = true))
        assertEquals(AidexSessionCodec.cmdDeviceInfo(sess, iv).hexStr(), t.lastWrite(AidexChar.F002)!!.hexStr())
        src.onGattEvent(AidexGattEvent.Write(AidexChar.F002, ok = true))
        assertEquals(AidexSessionCodec.cmdGetStartTime(sess, iv).hexStr(), t.lastWrite(AidexChar.F002)!!.hexStr())

        // year 2026: activated
        src.onGattEvent(f002(byteArrayOf(0x21, 0x01) + hex("EA07020F0C21360400")))
        assertTrue("activated → F003 subscribed", AidexChar.F003 in t.notifyEnables)
        assertEquals(2, t.writes.count { it.first == AidexChar.F002 })

        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F003, ok = true))
        assertEquals(AidexSessionCodec.cmdSetAutoUpdate(sess, iv).hexStr(), t.writesTo(AidexChar.F002)[2].hexStr())
        src.onGattEvent(AidexGattEvent.Write(AidexChar.F002, ok = true))
        assertEquals(AidexSessionCodec.cmdGetBroadcast(sess, iv).hexStr(), t.writesTo(AidexChar.F002)[3].hexStr())
        src.onGattEvent(AidexGattEvent.Write(AidexChar.F002, ok = true))

        src.onGattEvent(f003(realtime(type = 1, trend = 8, mfs = 1418, glucose = 115, valid = true, warmup = false)))

        assertEquals(1, repo.upsertedReadings.size)
        val r = repo.upsertedReadings.single()
        assertEquals(115, r.bgMgdl)
        assertEquals(8, r.trendTenthsPerMin)
        assertEquals(1418, r.minFromStart)
        assertEquals(ReadingProvenance.MEASURED, r.provenance)
        assertEquals(ReadingFlag.NORMAL, r.flag)
        assertEquals(gridNow, r.tsMs)
        assertEquals(0L, r.tsMs % 300_000L)
        assertEquals(CgmSourceStatus.Live, src.status.value)
        assertTrue("decrypted frame stored for forensics", repo.rawAdverts.single().crcValid)
    }

    @Test
    fun unactivated_sensor_runs_5step_activation_then_reads() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        driveToCheckActivation(src, t)

        // year 0: unactivated
        src.onGattEvent(f002(byteArrayOf(0x21, 0x01) + ByteArray(9)))
        assertTrue("F003 armed before activation commands", AidexChar.F003 in t.notifyEnables)
        assertEquals(2, t.writesTo(AidexChar.F002).size) // deviceInfo, then the CHECK getStartTime

        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F003, ok = true))
        val setNew = t.writesTo(AidexChar.F002)[2]
        val payload = AidexSessionCodec.decryptFrame(sess, iv, setNew)!!
        assertEquals(0x20, payload[0].toInt() and 0xFF)
        assertEquals(10, payload.size)                        // 0x20 + 9-byte LocalStartTime
        // tz 0 ⇒ epoch == gridNow/1000
        val startResp = AidexSessionCodec.parseResponse(byteArrayOf(0x21, 0x01) + payload.copyOfRange(1, 10))
        assertEquals(gridNow / 1000L, (startResp as AidexSessionResponse.StartTime).epochSecs)

        src.onGattEvent(f002(byteArrayOf(0x20, 0x01)))
        assertEquals(
            AidexSessionCodec.cmdSetDynamicAdvMode(sess, iv).hexStr(),
            t.writesTo(AidexChar.F002)[3].hexStr(),
        )
        src.onGattEvent(f002(byteArrayOf(0x35, 0x01)))
        assertEquals(
            AidexSessionCodec.cmdSetAutoUpdate(sess, iv).hexStr(),
            t.writesTo(AidexChar.F002)[4].hexStr(),
        )
        src.onGattEvent(f002(byteArrayOf(0x34, 0x01)))
        assertEquals(
            AidexSessionCodec.cmdGetStartTime(sess, iv).hexStr(),
            t.writesTo(AidexChar.F002)[5].hexStr(),
        )
        // year 2026
        src.onGattEvent(f002(byteArrayOf(0x21, 0x01) + hex("EA07020F0C21360400")))
        assertEquals(
            AidexSessionCodec.cmdGetBroadcast(sess, iv).hexStr(),
            t.writesTo(AidexChar.F002)[6].hexStr(),
        )
        src.onGattEvent(AidexGattEvent.Write(AidexChar.F002, ok = true))

        assertFalse(
            "0x31 prepareNewSensor must never be sent",
            t.writesTo(AidexChar.F002).any { (AidexSessionCodec.decryptFrame(sess, iv, it)?.getOrNull(0)?.toInt() ?: 0) and 0xFF == 0x31 },
        )

        src.onGattEvent(f003(realtime(type = 1, trend = 3, mfs = 5, glucose = 90, valid = true, warmup = true)))
        assertEquals(1, repo.upsertedReadings.size)
    }

    @Test
    fun activated_sensor_skips_activation() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        driveToCheckActivation(src, t)

        src.onGattEvent(f002(byteArrayOf(0x21, 0x01) + hex("EA07020F0C21360400"))) // year 2026
        assertTrue(AidexChar.F003 in t.notifyEnables)
        assertFalse(
            "must not activate an active sensor",
            t.writesTo(AidexChar.F002).any { (AidexSessionCodec.decryptFrame(sess, iv, it)?.getOrNull(0)?.toInt() ?: 0) and 0xFF == 0x20 },
        )
    }

    @Test
    fun device_info_states_the_lifetime_and_stores_it() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        driveToCheckActivation(src, t)

        src.onGattEvent(f002(deviceInfo(lifeDays = 15)))

        assertEquals(15 * 1440, src.statedLifetimeMin.value)
        assertEquals(15 * 1440, repo.loadSensorLifetimeMin(descriptorId))
    }

    @Test
    fun a_zero_life_states_nothing() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        driveToCheckActivation(src, t)

        src.onGattEvent(f002(deviceInfo(lifeDays = 0)))

        assertNull(src.statedLifetimeMin.value)
        assertNull(repo.loadSensorLifetimeMin(descriptorId))
    }

    @Test
    fun a_device_info_reply_awaiting_the_start_time_is_not_taken_for_it() = runBlocking {
        val t = FakeAidexGattTransport()
        val src = source(t, FakeCgmRepository())
        driveToCheckActivation(src, t)

        src.onGattEvent(f002(deviceInfo(lifeDays = 15)))
        assertFalse("still awaiting 0x121", AidexChar.F003 in t.notifyEnables)

        src.onGattEvent(f002(byteArrayOf(0x21, 0x01) + hex("EA07020F0C21360400"))) // year 2026
        assertTrue(AidexChar.F003 in t.notifyEnables)
    }

    @Test
    fun realtime_warmup_bit_maps_to_warmup_reading() = runBlocking {
        val (src, repo) = live()
        src.onGattEvent(f003(realtime(type = 1, trend = 0, mfs = 500, glucose = 100, valid = true, warmup = true)))
        val r = repo.upsertedReadings.single()
        assertEquals(100, r.bgMgdl)
        assertEquals(ReadingFlag.WARMUP, r.flag)
        assertEquals(CgmSourceStatus.Warmup, src.status.value)
    }

    @Test
    fun cleared_warmup_bit_beats_the_configured_window() = runBlocking {
        val (src, repo) = live(warmupWindowMin = 120)
        src.onGattEvent(f003(realtime(type = 1, trend = 0, mfs = 5, glucose = 105, valid = true, warmup = false)))
        assertEquals(ReadingFlag.NORMAL, repo.upsertedReadings.single().flag)
        assertEquals(CgmSourceStatus.Live, src.status.value)
    }

    @Test
    fun set_warmup_bit_outlives_the_configured_window() = runBlocking {
        val (src, repo) = live(warmupWindowMin = 10)
        src.onGattEvent(f003(realtime(type = 1, trend = 0, mfs = 200, glucose = 95, valid = true, warmup = true)))
        assertEquals(ReadingFlag.WARMUP, repo.upsertedReadings.single().flag)
        assertEquals(CgmSourceStatus.Warmup, src.status.value)
    }

    @Test
    fun lastpast_falls_back_to_the_configured_window() = runBlocking {
        val (src, repo) = live(warmupWindowMin = 60)
        src.onGattEvent(f002(lastPast(mfs = 59, glucose = 88)))
        assertEquals(ReadingFlag.WARMUP, repo.upsertedReadings.single().flag)
    }

    @Test
    fun lastpast_at_the_window_boundary_is_normal() = runBlocking {
        val (src, repo) = live(warmupWindowMin = 60)
        src.onGattEvent(f002(lastPast(mfs = 60, glucose = 88)))
        assertEquals(ReadingFlag.NORMAL, repo.upsertedReadings.single().flag)
    }

    @Test
    fun a_zero_window_means_no_warmup_at_all() = runBlocking {
        val (src, repo) = live(warmupWindowMin = 0)
        src.onGattEvent(f002(lastPast(mfs = 0, glucose = 88)))
        assertEquals(ReadingFlag.NORMAL, repo.upsertedReadings.single().flag)
    }

    @Test
    fun realtime_invalid_reading_is_dropped() = runBlocking {
        val (src, repo) = live()
        src.onGattEvent(f003(realtime(type = 3, trend = 0, mfs = 900, glucose = 40, valid = false, warmup = false)))
        assertTrue(repo.upsertedReadings.isEmpty())
    }

    @Test
    fun repeated_minfromstart_is_deduped() = runBlocking {
        val (src, repo) = live()
        val frame = f003(realtime(type = 1, trend = 4, mfs = 700, glucose = 120, valid = true, warmup = false))
        src.onGattEvent(frame)
        src.onGattEvent(frame)
        assertEquals(1, repo.upsertedReadings.size)
    }

    @Test
    fun f003_decode_lines_name_their_chunk() = runBlocking {
        val dir = Files.createTempDirectory("cgmlog").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val logs = CgmSensorLogs(dir, scope, Dispatchers.IO, wallMs = { gridNow }, monoNs = { 1L })
            val id = AidexXConnectedSource.descriptorFor(serial).id
            val (src, _) = live(log = logs.of(id))
            val before = logs.snapshot(id).size
            src.onGattEvent(f003(realtime(1, 0, 800, 130, valid = true, warmup = false)).copy(rx = 42L))
            val dec = logs.snapshot(id).drop(before).filter { it.kind == CgmLogKind.DEC }
            assertTrue(dec.isNotEmpty())
            assertTrue(dec.all { it.rxNs == 42L })
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun crc_failing_f003_frame_is_dropped() = runBlocking {
        val (src, repo) = live()
        val ct = AidexSessionCodec.encryptFrame(sess, iv, realtime(1, 0, 800, 130, valid = true, warmup = false))
        ct[0] = (ct[0].toInt() xor 0xFF).toByte()
        src.onGattEvent(AidexGattEvent.Notify(AidexChar.F003, ct))
        assertTrue(repo.upsertedReadings.isEmpty())
    }

    @Test
    fun current_response_maps_to_a_reading() = runBlocking {
        val (src, repo) = live()
        src.onGattEvent(f002(lastPast(mfs = 300, glucose = 88)))
        val r = repo.upsertedReadings.single()
        assertEquals(88, r.bgMgdl)
        assertEquals(2, r.trendTenthsPerMin)
        assertEquals(0x63, r.quality)
        assertEquals(ReadingFlag.NORMAL, r.flag)
    }

    @Test
    fun service_discovery_failure_fails_the_session() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        src.beginHandshake()
        src.onGattEvent(AidexGattEvent.Connection(connected = true, statusOk = true, statusCode = 0))
        src.onGattEvent(AidexGattEvent.ServicesDiscovered(ok = false, hasCgmService = false))
        assertEquals(CgmSourceStatus.SignalLost, src.status.value)
        assertTrue(repo.upsertedReadings.isEmpty())
    }

    @Test
    fun bad_session_blob_fails_the_session() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        var proofs = 0
        val src = source(t, repo, onAuthenticated = { proofs++ })
        driveHandshakeToBlobRead(src, t)
        val badBlob = blob.copyOf().also { it[16] = (it[16].toInt() xor 0xFF).toByte() } // bad crc8
        src.onGattEvent(AidexGattEvent.Read(AidexChar.F002, badBlob, ok = true))
        assertEquals(CgmSourceStatus.SignalLost, src.status.value)
        assertNull("no start time write on a failed derive", t.lastWrite(AidexChar.F002))
        assertEquals("a failed derive proves no handle", 0, proofs)
    }

    @Test
    fun a_derived_key_proves_the_handle_once() = runBlocking {
        val t = FakeAidexGattTransport()
        var proofs = 0
        val src = source(t, FakeCgmRepository(), onAuthenticated = { proofs++ })
        driveHandshakeToBlobRead(src, t)
        src.onGattEvent(AidexGattEvent.Read(AidexChar.F002, blob, ok = true))
        src.onGattEvent(AidexGattEvent.Read(AidexChar.F002, blob, ok = true))
        assertEquals(1, proofs)
    }

    @Test
    fun a_blob_read_refused_by_a_busy_client_is_reissued_rather_than_fatal() = runBlocking {
        // Masterkey notify can beat askKey callback, client refuses busy; re-issued once idle.
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        src.beginHandshake()
        src.onGattEvent(AidexGattEvent.Connection(connected = true, statusOk = true, statusCode = 0))
        src.onGattEvent(AidexGattEvent.ServicesDiscovered(ok = true, hasCgmService = true))
        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F001, ok = true))
        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F002, ok = true))

        t.rejectNextReads = 1
        // no Write(F001) delivered yet: the notify wins the race
        src.onGattEvent(AidexGattEvent.Notify(AidexChar.F001, masterKey))
        assertEquals("the read was attempted", listOf(AidexChar.F002), t.reads)
        assertTrue("and refusing it must not end the session", src.status.value != CgmSourceStatus.SignalLost)

        src.onGattEvent(AidexGattEvent.Write(AidexChar.F001, ok = true))
        assertEquals("the late write-callback re-issues it", listOf(AidexChar.F002, AidexChar.F002), t.reads)

        src.onGattEvent(AidexGattEvent.Read(AidexChar.F002, blob, ok = true))
        assertEquals(
            "and the handshake carries on from there",
            AidexSessionCodec.cmdDeviceInfo(sess, iv).hexStr(),
            t.lastWrite(AidexChar.F002)!!.hexStr(),
        )
    }

    @Test
    fun the_ordinary_write_then_notify_order_reads_the_blob_once() = runBlocking {
        // The usual order: the late branch must not fire and read a second time.
        val t = FakeAidexGattTransport()
        val src = source(t, FakeCgmRepository())
        driveHandshakeToBlobRead(src, t)
        assertEquals(listOf(AidexChar.F002), t.reads)
    }

    @Test
    fun a_pull_recovers_the_store_decimated_onto_the_grid() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        driveToLive(src, t)

        src.pullForTest(user = true)
        assertEquals(
            "a pull asks for the newest absolute id first",
            AidexSessionCodec.cmdGetLastId(sess, iv).hexStr(),
            t.lastWrite(AidexChar.F002)!!.hexStr(),
        )

        src.onGattEvent(f002(lastId(12)))
        assertEquals(
            "the first ask of a session must be relId 1 — nothing else names the range base",
            AidexSessionCodec.cmdGetHistory(sess, iv, 1).hexStr(),
            t.lastWrite(AidexChar.F002)!!.hexStr(),
        )

        src.onGattEvent(f002(historyBatch(startId = 1, count = 12)))

        // 1-minute ids against a 5-minute grid: one row per slot, the one nearest its centre.
        assertEquals(3, repo.upsertedReadings.size)
        assertEquals(listOf(101, 105, 110), repo.upsertedReadings.map { it.bgMgdl })
        assertEquals(listOf(1, 5, 10), repo.upsertedReadings.map { it.minFromStart })
        assertEquals(
            listOf(activationEpochMs, activationEpochMs + 300_000L, activationEpochMs + 600_000L),
            repo.upsertedReadings.map { it.tsMs },
        )
        for (r in repo.upsertedReadings) {
            assertEquals(0L, r.tsMs % 300_000L)
            assertEquals(ReadingProvenance.MEASURED, r.provenance)
            assertEquals(ReadingFlag.NORMAL, r.flag)
            // The sensor's own clock, hours before receipt; never the link's instant.
            assertEquals(r.rxWallMs, r.measuredAtMs)
            assertNull("a recovered sample says nothing about signal at its own moment", r.rssi)
        }
        assertTrue("a recovered sample is not a frame the phone received", repo.rawAdverts.isEmpty())
        assertEquals("the cursor survives for the next reconnect", 12, repo.loadSourceCursor(descriptorId))
    }

    @Test
    fun recovered_samples_never_clear_a_lost_signal() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        driveToLive(src, t)

        // Only a measured reading puts a session on the air, so only then is there a signal to lose.
        src.onGattEvent(f003(realtime(type = 1, trend = 0, mfs = 400, glucose = 120, valid = true, warmup = false)))
        assertEquals(CgmSourceStatus.Live, src.status.value)
        src.markSignalLost()

        src.pullForTest(user = true)
        src.onGattEvent(f002(lastId(12)))
        src.onGattEvent(f002(historyBatch(startId = 1, count = 12)))

        assertEquals("the live reading plus three recovered slots", 4, repo.upsertedReadings.size)
        assertEquals(
            "hours-old numbers must not put the session back on the air",
            CgmSourceStatus.SignalLost,
            src.status.value,
        )
    }

    /** A reset restarts ids at 1; the old wear's cursor held every automatic pull off. */
    @Test
    fun a_cursor_past_the_newest_id_is_dropped_so_a_reset_sensor_is_pulled() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        repo.saveSourceCursor(descriptorId, 21_600)
        val src = source(t, repo)
        driveToLive(src, t)

        src.pullForTest(user = false)
        src.onGattEvent(f002(lastId(12)))
        assertEquals(
            AidexSessionCodec.cmdGetHistory(sess, iv, 1).hexStr(),
            t.lastWrite(AidexChar.F002)!!.hexStr(),
        )
        src.onGattEvent(f002(historyBatch(startId = 1, count = 12)))
        assertEquals(3, repo.upsertedReadings.size)
        assertEquals(12, repo.loadSourceCursor(descriptorId))
    }

    /** Minute numbers from the last wear collided with this one's and read as already held. */
    @Test
    fun minutes_held_from_before_this_activation_do_not_count_as_held() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        for (minute in 1..12) {
            val before = activationEpochMs - 60 * 60_000L + minute * 60_000L
            repo.upsertReading(
                CgmReading(
                    sourceId = descriptorId,
                    tsMs = before - before % 300_000L,
                    bgMgdl = 100,
                    trendTenthsPerMin = null,
                    minFromStart = minute,
                    quality = null,
                    provenance = ReadingProvenance.MEASURED,
                    flag = ReadingFlag.NORMAL,
                    tzOffsetMin = 0,
                    rxWallMs = before,
                    rssi = null,
                ),
            )
        }
        val src = source(t, repo)
        driveToLive(src, t)

        src.pullForTest(user = true)
        src.onGattEvent(f002(lastId(12)))
        assertEquals(
            AidexSessionCodec.cmdGetHistory(sess, iv, 1).hexStr(),
            t.lastWrite(AidexChar.F002)!!.hexStr(),
        )
    }

    @Test
    fun a_pull_is_refused_without_an_activation_time() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        // installSessionForTest goes LIVE without ever reading 0x121, so nothing can date a record.
        src.installSessionForTest(sess)
        src.pullForTest(user = true)
        assertNull("a recovered sample has no receive-time fallback", t.lastWrite(AidexChar.F002))
    }

    @Test
    fun a_pull_skips_to_its_target_once_the_base_is_known() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        repo.saveSourceCursor(descriptorId, 100)
        val src = source(t, repo)
        driveToLive(src, t)

        src.pullForTest(user = false)
        src.onGattEvent(f002(lastId(112)))
        assertEquals(AidexSessionCodec.cmdGetHistory(sess, iv, 1).hexStr(), t.lastWrite(AidexChar.F002)!!.hexStr())

        src.onGattEvent(f002(historyBatch(startId = 1, count = 12)))
        assertTrue("ids below the cursor are not filed again", repo.upsertedReadings.isEmpty())

        src.tickForTest()
        assertEquals(
            "the next ask jumps to the target, not to the end of the probe batch",
            AidexSessionCodec.cmdGetHistory(sess, iv, 101).hexStr(),
            t.lastWrite(AidexChar.F002)!!.hexStr(),
        )

        src.onGattEvent(f002(historyBatch(startId = 101, count = 12)))
        assertEquals(listOf(101, 105, 110), repo.upsertedReadings.map { it.minFromStart })
        assertEquals(112, repo.loadSourceCursor(descriptorId))
    }

    @Test
    fun a_probe_batch_straddling_the_target_files_only_ids_from_it() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        repo.upsertReading(heldAt(minute = 1))
        repo.upsertReading(heldAt(minute = 5))
        val src = source(t, repo)
        driveToLive(src, t)

        src.pullForTest(user = true)
        src.onGattEvent(f002(lastId(12)))
        src.onGattEvent(f002(historyBatch(startId = 1, count = 12)))

        assertEquals("only the slot from id 8 on", listOf(10), repo.upsertedReadings.drop(2).map { it.minFromStart })
        assertEquals(12, repo.loadSourceCursor(descriptorId))
    }

    @Test
    fun an_automatic_pull_reopens_after_the_go_live_pull_ends() = runBlocking {
        val t = FakeAidexGattTransport()
        val src = source(t, FakeCgmRepository())
        driveToLive(src, t)

        src.pullForTest(user = false)
        src.onGattEvent(f002(lastId(12)))
        src.onGattEvent(f002(historyBatch(startId = 1, count = 12)))
        val asked = t.writesTo(AidexChar.F002).size

        src.pullForTest(user = false)
        assertEquals("an idle pull asks again", asked + 1, t.writesTo(AidexChar.F002).size)
        assertEquals(AidexSessionCodec.cmdGetLastId(sess, iv).hexStr(), t.lastWrite(AidexChar.F002)!!.hexStr())
    }

    @Test
    fun a_late_history_reply_is_not_taken_for_the_activation_ack() = runBlocking {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo)
        driveToLive(src, t)
        src.pullForTest(user = true)
        src.onGattEvent(f002(lastId(12)))

        src.activateForTest()
        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F003, ok = true))
        val setNew = t.lastWrite(AidexChar.F002)!!
        assertEquals(0x20, AidexSessionCodec.decryptFrame(sess, iv, setNew)!![0].toInt() and 0xFF)

        src.onGattEvent(f002(historyBatch(startId = 1, count = 12)))
        assertEquals("still awaiting the 0x120", setNew.hexStr(), t.lastWrite(AidexChar.F002)!!.hexStr())
        assertTrue(repo.upsertedReadings.isEmpty())

        src.onGattEvent(f002(byteArrayOf(0x20, 0x01)))
        assertEquals(AidexSessionCodec.cmdSetDynamicAdvMode(sess, iv).hexStr(), t.lastWrite(AidexChar.F002)!!.hexStr())
    }

    @Test
    fun request_activate_touches_no_gatt_off_the_collector() = runBlocking {
        val t = FakeAidexGattTransport()
        val src = source(t, FakeCgmRepository())
        driveToLive(src, t)
        val enables = t.notifyEnables.size
        val writes = t.writes.size

        src.requestActivate()

        assertEquals(enables, t.notifyEnables.size)
        assertEquals(writes, t.writes.size)
    }

    @Test
    fun a_silent_handshake_step_fails_the_session() = runBlocking {
        val t = FakeAidexGattTransport()
        var now = gridNow
        val src = source(t, FakeCgmRepository(), nowMs = { now })
        driveToCheckActivation(src, t)

        now += 60_000L
        src.stallCheckForTest()
        assertEquals(CgmSourceStatus.Scanning, src.status.value)

        now += 1
        src.stallCheckForTest()
        assertEquals("no 0x121 on a held link", CgmSourceStatus.SignalLost, src.status.value)
    }

    @Test
    fun a_live_link_without_f003_frames_fails() = runBlocking {
        val t = FakeAidexGattTransport()
        var now = gridNow
        val src = source(t, FakeCgmRepository(), nowMs = { now })
        driveToLive(src, t)
        src.onGattEvent(f003(realtime(type = 1, trend = 0, mfs = 400, glucose = 120, valid = true, warmup = false)))

        now += 10 * 60_000L
        src.onGattEvent(f002(lastPast(mfs = 410, glucose = 118)))
        now += 5 * 60_000L - 1
        src.stallCheckForTest()
        assertEquals(CgmSourceStatus.Live, src.status.value)

        now += 2
        src.stallCheckForTest()
        assertEquals("an F002 reply is not a push", CgmSourceStatus.SignalLost, src.status.value)
    }

    @Test
    fun an_f003_status_frame_postpones_the_watchdog() = runBlocking {
        val t = FakeAidexGattTransport()
        var now = gridNow
        val src = source(t, FakeCgmRepository(), nowMs = { now })
        driveToLive(src, t)
        src.onGattEvent(f003(realtime(type = 1, trend = 0, mfs = 400, glucose = 120, valid = true, warmup = false)))

        now += 10 * 60_000L
        src.onGattEvent(f003(byteArrayOf(0x12, 0x01, 0x00)))
        now += 10 * 60_000L
        src.stallCheckForTest()
        assertEquals(CgmSourceStatus.Live, src.status.value)
    }

    @Test
    fun an_activation_on_a_quiet_live_link_starts_a_fresh_step_budget() = runBlocking {
        val t = FakeAidexGattTransport()
        var now = gridNow
        val src = source(t, FakeCgmRepository(), nowMs = { now })
        driveToLive(src, t)
        src.onGattEvent(f003(realtime(type = 1, trend = 0, mfs = 400, glucose = 120, valid = true, warmup = false)))

        now += 10 * 60_000L
        src.activateForTest()
        src.stallCheckForTest()
        assertEquals(CgmSourceStatus.Live, src.status.value)
    }

    @Test
    fun connecting_never_stalls() = runBlocking {
        val t = FakeAidexGattTransport()
        var now = gridNow
        val src = source(t, FakeCgmRepository(), nowMs = { now })
        src.beginHandshake()

        now += 60 * 60_000L
        src.stallCheckForTest()
        assertEquals(CgmSourceStatus.Scanning, src.status.value)
    }

    private fun live(
        warmupWindowMin: Int = CgmConstants.WARMUP_WINDOW_MIN,
        log: CgmSensorLog = CgmSensorLog.NONE,
    ): Pair<AidexXConnectedSource, FakeCgmRepository> {
        val t = FakeAidexGattTransport()
        val repo = FakeCgmRepository()
        val src = source(t, repo, warmupWindowMin, log = log)
        src.installSessionForTest(sess)
        return src to repo
    }

    private suspend fun driveHandshakeToBlobRead(src: AidexXConnectedSource, t: FakeAidexGattTransport) {
        src.beginHandshake()
        src.onGattEvent(AidexGattEvent.Connection(connected = true, statusOk = true, statusCode = 0))
        src.onGattEvent(AidexGattEvent.ServicesDiscovered(ok = true, hasCgmService = true))
        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F001, ok = true))
        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F002, ok = true))
        src.onGattEvent(AidexGattEvent.Write(AidexChar.F001, ok = true))
        src.onGattEvent(AidexGattEvent.Notify(AidexChar.F001, masterKey))
    }

    /** Leaves the 0x10 reply unsent: the machine moves on at its write. */
    private suspend fun driveToCheckActivation(src: AidexXConnectedSource, t: FakeAidexGattTransport) {
        driveHandshakeToBlobRead(src, t)
        src.onGattEvent(AidexGattEvent.Read(AidexChar.F002, blob, ok = true))
        src.onGattEvent(AidexGattEvent.Write(AidexChar.F002, ok = true)) // deviceInfo
    }

    /** 0x110 laid out as the core's golden vector; life days at byte 8. */
    private fun deviceInfo(lifeDays: Int): ByteArray =
        hex("1001020101080200") + byteArrayOf(lifeDays.toByte(), 0x07) + "GX-01S".toByteArray() + ByteArray(4)

    private val descriptorId = AidexXConnectedSource.descriptorFor(serial).id

    /** 2023-11-14 16:15:00Z, tz 0 — six hours before [gridNow], so every record dates in the past. */
    private val startTime = byteArrayOf(0x21, 0x01) + hex("E7070B0E100F000000")

    private val activationEpochMs = gridNow - 6 * 60 * 60 * 1000L

    /** A reading already held for this wear's [minute]; it covers that minute's grid slot. */
    private fun heldAt(minute: Int): CgmReading {
        val at = activationEpochMs + minute * 60_000L
        return CgmReading(
            sourceId = descriptorId,
            tsMs = at - at % 300_000L,
            bgMgdl = 100,
            trendTenthsPerMin = null,
            minFromStart = minute,
            quality = null,
            provenance = ReadingProvenance.MEASURED,
            flag = ReadingFlag.NORMAL,
            tzOffsetMin = 0,
            rxWallMs = at,
            rssi = null,
        )
    }

    private suspend fun driveToLive(src: AidexXConnectedSource, t: FakeAidexGattTransport) {
        driveToCheckActivation(src, t)
        src.onGattEvent(f002(startTime))
        src.onGattEvent(AidexGattEvent.NotifyEnabled(AidexChar.F003, ok = true))
        src.onGattEvent(AidexGattEvent.Write(AidexChar.F002, ok = true)) // setAutoUpdate
        src.onGattEvent(AidexGattEvent.Write(AidexChar.F002, ok = true)) // getBroadcast → LIVE
    }

    /** 0x122: the id sits in the LAST two bytes, whatever precedes it (CGM.md §6). */
    private fun lastId(id: Int): ByteArray = byteArrayOf(
        0x22, 0x01, 0, 0, 0, 0,
        (id and 0xFF).toByte(), ((id ushr 8) and 0xFF).toByte(),
    )

    /** 0x123 over ids [startId, startId+count), glucose 100+id, valid, warm-up bit clear. */
    private fun historyBatch(startId: Int, count: Int): ByteArray {
        val out = ByteArray(4 + count * 2)
        out[0] = 0x23
        out[1] = 0x01
        out[2] = (startId and 0xFF).toByte()
        out[3] = ((startId ushr 8) and 0xFF).toByte()
        for (i in 0 until count) {
            val bf = ((100 + startId + i) and 0x3FF) or (1 shl 15)
            out[4 + i * 2] = (bf and 0xFF).toByte()
            out[5 + i * 2] = ((bf ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun f002(plaintext: ByteArray) =
        AidexGattEvent.Notify(AidexChar.F002, AidexSessionCodec.encryptFrame(sess, iv, plaintext))

    private fun f003(plaintext: ByteArray) =
        AidexGattEvent.Notify(AidexChar.F003, AidexSessionCodec.encryptFrame(sess, iv, plaintext))

    /** A 0x111 LastPast plaintext, the one read shape carrying NO warm-up bit (quality 0x63). */
    private fun lastPast(mfs: Int, glucose: Int, trend: Int = 2): ByteArray {
        val bitfield = glucose or (1 shl 15)
        return byteArrayOf(
            0x11, 0x01,
            (mfs and 0xFF).toByte(), ((mfs ushr 8) and 0xFF).toByte(), 0, 0, trend.toByte(),
            (bitfield and 0xFF).toByte(), ((bitfield ushr 8) and 0xFF).toByte(), 0x63,
        )
    }

    private fun realtime(type: Int, trend: Int, mfs: Int, glucose: Int, valid: Boolean, warmup: Boolean): ByteArray {
        val bitfield = (glucose and 0x3FF) or (if (warmup) 1 shl 10 else 0) or (if (valid) 1 shl 15 else 0)
        val b = ByteArray(15)
        b[0] = type.toByte()
        b[3] = trend.toByte()
        b[4] = (mfs and 0xFF).toByte(); b[5] = ((mfs ushr 8) and 0xFF).toByte()
        b[6] = (bitfield and 0xFF).toByte(); b[7] = ((bitfield ushr 8) and 0xFF).toByte()
        return b
    }

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hexStr(): String = joinToString("") { "%02x".format(it) }
}
