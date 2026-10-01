package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.SensorArrow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4/§5.7/§11/§12.4: the post-Established data plane driven over fakes — scripted data-plane
 * handle, recording transport, fake repository. The stream's dispatch rules are pinned here:
 * the §5.1 enable order, the flag mapping, the §11 sample clock, persistence and backfill.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Libre3DataStreamTest {

    /** The stream rig: scripted plane, recording transport, fake repository, mutable clock. */
    private class Rig(sensorOverride: Libre3SensorState? = null) {
        val plane = ScriptedDataPlane()
        val native = FakeLibre3Native().apply { dataPlane = plane }
        val repo = FakeCgmRepository()
        val fake = FakeLibre3GattTransport()
        val statuses = mutableListOf<CgmSourceStatus>()
        val readings = mutableListOf<CgmReading>()

        /** Each published arrow, with the reading count at that moment. */
        val telemetry = mutableListOf<Pair<CgmSourceTelemetry, Int>>()

        /** The test's wall clock; tests move it for skew cases. */
        var now: Long = T0

        val stream = Libre3DataStream(
            sourceId = SOURCE_ID,
            transport = fake,
            startDataPlane = { Libre3Call.Ok(plane) },
            sensor = sensorOverride ?: SENSOR,
            repository = repo,
            emitReading = { readings += it },
            onTelemetry = { telemetry += it to readings.size },
            onStatus = { statuses += it },
            historicalBackfillCmd = { lifeCount -> native.historicalBackfillCmd(lifeCount) },
            clinicalBackfillCmd = { lifeCount -> native.clinicalBackfillCmd(lifeCount) },
            historicalBackfillRangeCmd = { start, end -> native.historicalBackfillRangeCmd(start, end) },
            clinicalBackfillRangeCmd = { start, end -> native.clinicalBackfillRangeCmd(start, end) },
            armBackfillDelayMs = 0L,
            nowMs = { now },
            tzOffsetMinFor = { 60 },
        )

        /** run()'s outcome, captured by [startRig] so tests await it after driving the fake. */
        lateinit var outcome: Deferred<Libre3DataStream.Outcome>
    }

    /** Arms the stream (data plane built, listeners on, §5.1 CCCDs acked, loop parked). */
    private fun TestScope.startRig(
        sensorOverride: Libre3SensorState? = null,
        nextFrame: ByteArray? = null,
    ): Rig {
        val rig = Rig(sensorOverride)
        if (nextFrame != null) rig.plane.nextFrame = nextFrame
        rig.outcome = async { rig.stream.run() }
        runCurrent()
        return rig
    }

    /** Ends a still-streaming test with a link death; every test must tear the loop down. */
    private suspend fun Rig.drop() {
        fake.fail("disconnected (status=8)")
        outcome.await()
        assertTrue(fake.closed)
        assertTrue(plane.closed)
    }

    // MARK: - fixtures

    private fun usableRealtime(
        lifeCount: Int = 60,
        mgdl: Int = 103,
        rateRaw: Short = 23,
    ) = Libre3DataUpdate.RealtimeGlucose(
        lifeCount = lifeCount,
        mgdl = mgdl,
        usable = true,
        warmup = false,
        expired = false,
        issues = emptyList(),
        rateRaw = rateRaw,
        trendKind = "stable",
    )

    private fun warmupRealtime(lifeCount: Int = 30) = Libre3DataUpdate.RealtimeGlucose(
        lifeCount = lifeCount,
        mgdl = 90,
        usable = false,
        warmup = true,
        expired = false,
        issues = listOf("sensorWarmup(remainingMinutes: 30)"),
        rateRaw = null,
        trendKind = "unknown",
    )

    private fun unusableRealtime() = Libre3DataUpdate.RealtimeGlucose(
        lifeCount = 70,
        mgdl = 103,
        usable = false,
        warmup = false,
        expired = false,
        issues = listOf("currentDataQuality(countsInvalid)"),
        rateRaw = null,
        trendKind = "unknown",
    )

    private fun expiredRealtime() = Libre3DataUpdate.RealtimeGlucose(
        lifeCount = 70,
        mgdl = 103,
        usable = false,
        warmup = false,
        expired = true,
        issues = listOf("sensorExpired"),
        rateRaw = null,
        trendKind = "unknown",
    )

    /** §5.7: one 14-B page = 6 samples at +0,+5,…,+25 life-count minutes. */
    private fun page(startLifeCount: Int = 120) = Libre3DataUpdate.HistoricalPage(
        startLifeCount = startLifeCount,
        valuesMgdl = List(PAGE_SAMPLES) { 100 + it },
        sampleLifeCounts = List(PAGE_SAMPLES) { startLifeCount + it * 5 },
    )

    private fun activeStatus() = Libre3DataUpdate.PatchStatus(
        lifeCount = 30,
        currentLifeCount = 30,
        patchState = 4,
        errorData = 0,
        isPatchStateActive = true,
        isPatchStateTerminated = false,
        isShutdownTerminated = false,
        attention = "ok",
        shouldNotifyUser = false,
        shouldNotifyReplaceSensor = false,
        lifecycle = Libre3Lifecycle(
            phase = "active",
            isWarmingUp = false,
            isExpired = false,
            remainingWarmupMin = 0,
            remainingWearMin = 20_100,
        ),
    )

    private fun terminalStatus() = Libre3DataUpdate.PatchStatus(
        lifeCount = 20_160,
        currentLifeCount = 20_160,
        patchState = 6,
        errorData = 6,
        isPatchStateActive = false,
        isPatchStateTerminated = true,
        isShutdownTerminated = false,
        attention = "expired",
        shouldNotifyUser = false,
        shouldNotifyReplaceSensor = true,
        lifecycle = Libre3Lifecycle(
            phase = "expired",
            isWarmingUp = false,
            isExpired = true,
            remainingWarmupMin = 0,
            remainingWearMin = 0,
        ),
    )

    /** §5.2: glucoseData arrives as a 15-B prefix and a 20-B suffix. */
    private val glucosePrefix = ByteArray(15) { it.toByte() }
    private val glucoseSuffix = ByteArray(20) { it.toByte() }

    /** The §5.1 post-handshake subscribe order, pinned literally (protocol.md PoC). */
    private val expectedEnableOrder = listOf(
        "08981338-ef89-11e9-81b4-2a2ae2dbcce4",
        "08981bee-ef89-11e9-81b4-2a2ae2dbcce4",
        "0898195a-ef89-11e9-81b4-2a2ae2dbcce4",
        "08981ab8-ef89-11e9-81b4-2a2ae2dbcce4",
        "08981d24-ef89-11e9-81b4-2a2ae2dbcce4",
        "0898177a-ef89-11e9-81b4-2a2ae2dbcce4",
        "08981482-ef89-11e9-81b4-2a2ae2dbcce4",
    )

    // MARK: - tests

    @Test
    fun `the seven data chars arm in the protocol order`() = runTest {
        val rig = startRig()
        assertEquals(expectedEnableOrder, rig.fake.dataCharsEnabled.map { it.uuid })
        rig.drop()
    }

    @Test
    fun `a scripted enable failure fails the stream before it streams`() = runTest {
        val rig = Rig()
        rig.fake.dataEnableFailure = "CCCD write failed (status=0x05)"
        rig.outcome = async { rig.stream.run() }
        runCurrent()
        assertEquals(
            Libre3DataStream.Outcome.Failed("enable failed: CCCD write failed (status=0x05)"),
            rig.outcome.await(),
        )
        assertTrue(rig.fake.closed)
        assertTrue(rig.plane.closed)
    }

    @Test
    fun `glucose files NORMAL on the sensor clock and advances the life count`() = runTest {
        val rig = startRig()
        var latched = true
        rig.plane.onFeed = { char, _ ->
            when {
                char != Libre3DataChar.GlucoseData -> emptyList()
                latched -> {
                    latched = false
                    emptyList() // the 15-B prefix latches; the stream keeps waiting (§5.2)
                }

                else -> listOf(usableRealtime(lifeCount = 60, mgdl = 103, rateRaw = 23))
            }
        }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
        assertEquals("the 15-B prefix latches; nothing stored yet", 0, rig.repo.upsertedReadings.size)
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucoseSuffix)
        runCurrent()

        val reading = rig.repo.upsertedReadings.single()
        assertTrue(reading.tsMs % 300_000 == 0L)
        assertEquals(103, reading.bgMgdl)
        assertEquals("rateRaw 23/100 mg/dL/min files as 2/10", 2, reading.trendTenthsPerMin)
        val repair = rig.repo.rateRepairs.single()
        assertEquals("libre3:", repair.prefix)
        assertEquals("the repair precedes every write", 0, repair.upsertsSoFar)
        val (arrow, readingsThen) = rig.telemetry.single()
        assertEquals("\"stable\" is the sensor's flat arrow", SensorArrow.FLAT, arrow.arrow)
        assertEquals("the reading's own instant", reading.measuredAtMs, arrow.sampledAtMs)
        assertEquals("published before the reading", 0, readingsThen)
        assertEquals(60, reading.minFromStart)
        assertEquals("measuredAtMs = activation + lifeCount*60_000 (§11)", ACTIVATION_MS + 60 * 60_000L, reading.measuredAtMs)
        assertEquals(ReadingFlag.NORMAL, reading.flag)
        assertEquals(ReadingProvenance.MEASURED, reading.provenance)
        assertTrue(rig.statuses.contains(CgmSourceStatus.Live))
        assertTrue(rig.readings.contains(reading))
        // Persisted on change via saveSensorSecret.
        val stored = Libre3SensorState.decode(rig.repo.sensorSecrets.getValue(SOURCE_ID))
        assertEquals(60, stored!!.lastRealtimeLifeCount)
        rig.drop()
    }

    @Test
    fun `the sensor's trend spellings map to arrows, and anything else is fitted`() {
        val cases = mapOf(
            "fallingQuickly" to SensorArrow.FALLING_FAST, "falling" to SensorArrow.FALLING,
            "stable" to SensorArrow.FLAT, "rising" to SensorArrow.RISING,
            "risingQuickly" to SensorArrow.RISING_FAST, "notDetermined" to SensorArrow.UNDETERMINED,
            "raw(6)" to SensorArrow.UNDETERMINED,
        )
        for ((kind, arrow) in cases) assertEquals(kind, arrow, libre3Arrow(kind))
    }

    @Test
    fun `hundredths round half away from zero into tenths`() {
        val cases = mapOf(0 to 0, 4 to 0, 5 to 1, 14 to 1, 15 to 2, -4 to 0, -5 to -1, -15 to -2, 32767 to 3277)
        for ((raw, tenths) in cases) assertEquals("raw $raw", tenths, hundredthsToTenths(raw))
    }

    @Test
    fun `a re-provisioned blob is not overwritten by the live stream`() = runTest {
        val rig = startRig()
        val reprovisioned = SENSOR.copy(provisionedAtMs = SENSOR.provisionedAtMs + 1, blePin = byteArrayOf(5, 6, 7, 8))
        rig.repo.saveSensorSecret(SOURCE_ID, reprovisioned.encode())
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.GlucoseData) listOf(usableRealtime()) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()

        assertEquals("the reading still files", 1, rig.repo.upsertedReadings.size)
        assertTrue(rig.repo.sensorSecrets.getValue(SOURCE_ID).contentEquals(reprovisioned.encode()))
        rig.drop()
    }

    @Test
    fun `a warmup realtime files WARMUP and moves status to Warmup`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.GlucoseData) listOf(warmupRealtime(30)) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()

        val reading = rig.repo.upsertedReadings.single()
        assertEquals(ReadingFlag.WARMUP, reading.flag)
        assertEquals(30, reading.minFromStart)
        assertTrue(rig.statuses.contains(CgmSourceStatus.Warmup))
        // Only NORMAL readings advance the persisted life count (§5.7 usable-only acceptance):
        // a warmup-only stream has written NO secret yet.
        assertEquals(
            null,
            rig.repo.sensorSecrets[SOURCE_ID]?.let { blob -> Libre3SensorState.decode(blob)?.lastRealtimeLifeCount },
        )
        rig.drop()
    }

    @Test
    fun `a usable realtime inside the first hour files WARMUP before any patchStatus`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ ->
            if (char == Libre3DataChar.GlucoseData) listOf(usableRealtime(lifeCount = 30)) else emptyList()
        }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()

        assertEquals(ReadingFlag.WARMUP, rig.repo.upsertedReadings.single().flag)
        assertTrue(rig.statuses.contains(CgmSourceStatus.Warmup))
        assertFalse(rig.statuses.contains(CgmSourceStatus.Live))
        assertFalse("WARMUP never advances the count", rig.repo.sensorSecrets.containsKey(SOURCE_ID))
        rig.drop()
    }

    @Test
    fun `an unusable realtime inside the first hour is dropped`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ ->
            if (char == Libre3DataChar.GlucoseData) listOf(unusableRealtime().copy(lifeCount = 30)) else emptyList()
        }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        assertTrue(rig.readings.isEmpty())
        rig.drop()
    }

    @Test
    fun `an unusable realtime is dropped and stores nothing`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.GlucoseData) listOf(unusableRealtime()) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        assertTrue(rig.readings.isEmpty())
        rig.drop()
    }

    @Test
    fun `an expired realtime is dropped`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.GlucoseData) listOf(expiredRealtime()) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
        assertTrue(rig.repo.upsertedReadings.isEmpty())
        rig.drop()
    }

    @Test
    fun `a terminal patch status ends the stream as SignalLost`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.PatchStatus) listOf(terminalStatus()) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.PatchStatus, ByteArray(12))
        runCurrent()

        assertEquals(Libre3DataStream.Outcome.SignalLost, rig.outcome.await())
        assertTrue(rig.fake.closed)
        assertTrue(rig.plane.closed)
        assertTrue(rig.statuses.contains(CgmSourceStatus.SignalLost))
    }

    @Test
    fun `an active patch status moves status to Live`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.PatchStatus) listOf(activeStatus()) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.PatchStatus, ByteArray(12))
        runCurrent()
        assertTrue(rig.statuses.contains(CgmSourceStatus.Live))
        rig.drop()
    }

    @Test
    fun `a historical page stores six samples and advances the historical count`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.HistoricData) listOf(page(120)) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.HistoricData, ByteArray(17))
        runCurrent()

        val stored = rig.repo.upsertedReadings
        assertEquals(PAGE_SAMPLES, stored.size)
        for ((i, reading) in stored.withIndex()) {
            assertEquals(100 + i, reading.bgMgdl)
            assertEquals(120 + i * 5, reading.minFromStart)
            assertEquals("past warmup", ReadingFlag.NORMAL, reading.flag)
            assertEquals(ACTIVATION_MS + (120 + i * 5) * 60_000L, reading.measuredAtMs)
            assertEquals("pages carry no rate", null, reading.trendTenthsPerMin)
        }
        assertTrue("backfill never reaches the alarms", rig.readings.isEmpty())
        assertFalse(rig.statuses.contains(CgmSourceStatus.Live))
        val last = Libre3SensorState.decode(rig.repo.sensorSecrets.getValue(SOURCE_ID))!!
        assertEquals(145, last.lastHistoricalLifeCount)
        rig.drop()
    }

    @Test
    fun `a page sample inside warmup files WARMUP and moves no status`() = runTest {
        val rig = startRig()
        // startLifeCount 0 → samples 0,5,…,25 — all inside the 60-min warmup.
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.HistoricData) listOf(page(0)) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.HistoricData, ByteArray(17))
        runCurrent()
        assertEquals(PAGE_SAMPLES, rig.repo.upsertedReadings.size)
        assertTrue(rig.repo.upsertedReadings.all { it.flag == ReadingFlag.WARMUP })
        assertFalse("an old page says nothing of the sensor now", rig.statuses.contains(CgmSourceStatus.Warmup))
        rig.drop()
    }

    @Test
    fun `a feed exception is survived and the next update still ingests`() = runTest {
        val rig = startRig()
        var first = true
        rig.plane.onFeed = { char, _ ->
            when (char) {
                Libre3DataChar.GlucoseData ->
                    if (first) {
                        first = false
                        throw IllegalStateException("noDescriptorMatched(channel: glucoseData)")
                    } else {
                        listOf(usableRealtime())
                    }

                else -> emptyList()
            }
        }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
        assertTrue("the bad frame stored nothing", rig.repo.upsertedReadings.isEmpty())
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
        assertTrue("the stream kept going", rig.repo.upsertedReadings.isNotEmpty())
        rig.drop()
    }

    @Test
    fun `a sample clock far ahead of the wall clock files under receive time`() = runTest {
        val rig = startRig()
        // lifeCount 210 derives activation + 210 min = T0 + 10 min — ahead of the 5-min band.
        rig.plane.onFeed = { char, _ -> if (char == Libre3DataChar.GlucoseData) listOf(usableRealtime(lifeCount = 210)) else emptyList() }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()

        val reading = rig.repo.upsertedReadings.single()
        assertEquals(T0, reading.rxWallMs)
        assertEquals(T0, reading.measuredAtMs)
        assertTrue(reading.tsMs % 300_000 == 0L)
        rig.drop()
    }

    @Test
    fun `requestBackfill writes historical and clinical frames at the historical lower bound`() = runTest {
        val rig = startRig(nextFrame = ByteArray(13) { it.toByte() })
        runCurrent()
        assertEquals(
            "the arm round sent nothing (no realtime count yet; the pair rides the tap only)",
            0,
            rig.fake.writes.size,
        )
        launch { rig.stream.requestBackfill() }
        runCurrent()

        assertEquals(
            "the tap's kit pair at bound 0 — nothing historical accepted yet (the realtime " +
                "count asks for data the sensor has not committed; 17-min lag, live 2026-09-24)",
            listOf(Libre3DataChar.PatchControl.uuid, Libre3DataChar.PatchControl.uuid),
            rig.fake.writes.map { it.first },
        )
        assertTrue(rig.fake.writes.all { it.second.size == 13 })
        assertEquals(listOf(0 to 1), rig.native.historicalRequests)
        assertEquals(listOf(0 to 1), rig.native.clinicalRequests)
        assertEquals("historical plaintext first, clinical second", 2, rig.plane.framePlaintexts.size)
        assertTrue(rig.plane.framePlaintexts[0].contentEquals(rig.native.historicalCmd ?: ByteArray(0)))
        assertTrue(rig.plane.framePlaintexts[1].contentEquals(rig.native.clinicalCmd ?: ByteArray(0)))
        rig.drop()
    }

    @Test
    fun `requestBackfill skips the write when the frame cannot be encrypted`() = runTest {
        val rig = startRig()
        rig.plane.nextFrame = null
        launch { rig.stream.requestBackfill() }
        runCurrent()

        assertTrue(rig.fake.writes.isEmpty())
        rig.drop()
    }

    @Test
    fun `a tap after the plane closed refuses without throwing`() = runTest {
        val rig = startRig(nextFrame = ByteArray(13))
        rig.plane.close()
        rig.stream.requestBackfill()
        assertTrue("the closed plane threw; the tap stopped, nothing written", rig.fake.writes.isEmpty())
        rig.drop()
        assertNull(rig.stream.handle)
        rig.stream.requestBackfill()
        assertTrue(rig.fake.writes.isEmpty())
    }

    @Test
    fun `a plane that throws mid-walk stops the walk and leaves the floor`() = runTest {
        val rig = startRig(
            sensorOverride = SENSOR.copy(lastRealtimeLifeCount = 1378),
            nextFrame = ByteArray(13),
        )
        // Frames 0-1 pair, 2-3 top-up, 4-5 window [0, 120]; the next window throws.
        rig.plane.throwFromFrame = 6
        launch { rig.stream.requestBackfill() }
        runCurrent()

        assertEquals(6, rig.fake.writes.size)
        assertEquals(listOf(1241 to 1361, 0 to 120), rig.native.clinicalRangeRequests)
        assertEquals(
            "the floor stays at the last window both streams confirmed",
            120,
            Libre3SensorState.decode(rig.repo.sensorSecrets.getValue(SOURCE_ID))!!.lastHistoricalFloorLifeCount,
        )
        rig.drop()
    }

    @Test
    fun `requestBackfill is bounded by the persisted historical life count`() = runTest {
        val rig = startRig(
            sensorOverride = SENSOR.copy(lastHistoricalLifeCount = 80),
            nextFrame = ByteArray(13),
        )
        runCurrent()
        // Arm: top-up skipped (no realtime count), and the pair rides the tap only now.
        assertEquals(0, rig.fake.writes.size)

        launch { rig.stream.requestBackfill() }
        runCurrent()
        assertEquals(
            "the tap's kit pair at the persisted historical count; the top-up and walk are " +
                "skipped (no realtime count)",
            listOf(80 to 1),
            rig.native.historicalRequests,
        )
        assertEquals(listOf(80 to 1), rig.native.clinicalRequests)
        assertEquals(2, rig.fake.writes.size)
        rig.drop()
    }

    @Test
    fun `the manual tap walks the whole gap and persists the floor`() = runTest {
        val rig = startRig(
            sensorOverride = SENSOR.copy(lastRealtimeLifeCount = 1378),
            nextFrame = ByteArray(13),
        )
        runCurrent()
        // Arm round: waiting on the first realtime frame of this session.
        val afterArm = rig.fake.writes.size
        assertEquals(0, afterArm)
        launch { rig.stream.requestBackfill() }
        runCurrent()

        // Tap: pair (2) + top-up [1241, 1361] (2) + the walk [0,120)…[1200,1241] (11 × 2).
        val expectedWindows = listOf(
            0 to 120, 120 to 240, 240 to 360, 360 to 480, 480 to 600,
            600 to 720, 720 to 840, 840 to 960, 960 to 1080, 1080 to 1200, 1200 to 1241,
        )
        assertEquals(
            "the tap top-up keys on the persisted count; the walk tops out where it takes over",
            listOf(1241 to 1361) + expectedWindows,
            rig.native.historicalRangeRequests,
        )
        assertEquals(listOf(1241 to 1361) + expectedWindows, rig.native.clinicalRangeRequests)
        assertEquals(afterArm + 2 + 2 + 22, rig.fake.writes.size)
        assertEquals(
            "the floor persists at the walk's top (accepted-progress rule)",
            1241,
            Libre3SensorState.decode(rig.repo.sensorSecrets.getValue(SOURCE_ID))!!.lastHistoricalFloorLifeCount,
        )
        rig.drop()
    }

    @Test
    fun `the walk stops at an unconfirmed window and leaves the floor alone`() = runTest {
        val rig = startRig(
            sensorOverride = SENSOR.copy(lastRealtimeLifeCount = 1378),
            nextFrame = ByteArray(13),
        )
        runCurrent()
        val afterArm = rig.fake.writes.size
        // The busy-link case, live 2026-09-24: everything after the tap's top-up is rejected.
        rig.fake.refuseWritesAfter = afterArm + 4
        launch { rig.stream.requestBackfill() }
        runCurrent()

        // Tap: pair (2) + top-up (2) + the walk's first write, refused once and never resent.
        assertEquals(afterArm + 2 + 2 + 1, rig.fake.writes.size)
        assertEquals(
            "the walk asked [0, 120] once and never advanced past it",
            listOf(1241 to 1361, 0 to 120),
            rig.native.historicalRangeRequests,
        )
        assertEquals(
            "an undelivered window never counts as covered",
            false,
            rig.repo.sensorSecrets.containsKey(SOURCE_ID),
        )
        rig.fake.refuseWritesAfter = null
        rig.drop()
    }

    @Test
    fun `the walk resumes from the persisted floor`() = runTest {
        val rig = startRig(
            sensorOverride = SENSOR.copy(
                lastRealtimeLifeCount = 1378,
                lastHistoricalFloorLifeCount = 1241,
            ),
            nextFrame = ByteArray(13),
        )
        runCurrent()
        val afterArm = rig.fake.writes.size
        launch { rig.stream.requestBackfill() }
        runCurrent()

        // Arm: waiting on realtime. Tap: pair + top-up — the gap below 1241 is already walked.
        assertEquals(0, afterArm)
        assertEquals(afterArm + 4, rig.fake.writes.size)
        assertEquals(
            "the top-up range went out once (tap)",
            listOf(1241 to 1361),
            rig.native.historicalRangeRequests,
        )
        assertEquals(
            "nothing advanced — no walk, no persist — the stored floor stays absent",
            false,
            rig.repo.sensorSecrets.containsKey(SOURCE_ID),
        )
        rig.drop()
    }

    @Test
    fun `arming sends nothing until a realtime count exists`() = runTest {
        val rig = startRig(nextFrame = ByteArray(13))

        assertEquals(
            "the pair rides the tap; the top-up waits for a realtime count",
            0,
            rig.fake.writes.size,
        )
        assertTrue(rig.native.historicalRangeRequests.isEmpty())
        rig.drop()
    }

    @Test
    fun `the reconnect arm round carries only the historical top-up`() = runTest {
        val rig = startRig(
            sensorOverride = SENSOR.copy(lastHistoricalLifeCount = 80, lastRealtimeLifeCount = 1378),
            nextFrame = ByteArray(13),
        )
        deliverRealtime(rig, 1500)

        assertEquals("the top-up alone — no ≥ pair, no walk, no clinical", 1, rig.fake.writes.size)
        assertEquals(
            "keyed on this session's 1500, not the persisted 1378: the missed minutes",
            listOf(1363 to 1483),
            rig.native.historicalRangeRequests,
        )
        assertTrue(rig.native.clinicalRangeRequests.isEmpty())
        assertEquals("the walk is the manual tap's job — the floor never persisted",
            null, rig.repo.sensorSecrets[SOURCE_ID]?.let { Libre3SensorState.decode(it)?.lastHistoricalFloorLifeCount })
        rig.drop()
    }

    @Test
    fun `the reconnect top-up asks only the slots the store lacks`() = runTest {
        val rig = startRig(nextFrame = ByteArray(13))
        // ACTIVATION_MS sits 200 s into a slot, so minutes 5k..5k+4 share one.
        ((1363..1420) + (1450..1483)).forEach { rig.repo.upsertReading(measured(it)) }
        deliverRealtime(rig, 1500)

        assertEquals(listOf(1425 to 1449), rig.native.historicalRangeRequests)
        assertTrue(rig.native.clinicalRangeRequests.isEmpty())
        rig.drop()
    }

    @Test
    fun `the reconnect top-up is skipped when every slot is stored`() = runTest {
        val rig = startRig(nextFrame = ByteArray(13))
        (1360..1480 step 5).forEach { rig.repo.upsertReading(measured(it)) }
        deliverRealtime(rig, 1500)

        assertEquals("one page sample per slot covers it", 0, rig.fake.writes.size)
        assertTrue(rig.native.historicalRangeRequests.isEmpty())
        rig.drop()
    }

    private fun TestScope.deliverRealtime(rig: Rig, lifeCount: Int) {
        rig.now = ACTIVATION_MS + (lifeCount + 100) * 60_000L
        rig.plane.onFeed = { char, _ ->
            if (char == Libre3DataChar.GlucoseData) listOf(usableRealtime(lifeCount = lifeCount)) else emptyList()
        }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
    }

    private fun measured(lifeCount: Int): CgmReading {
        val atMs = ACTIVATION_MS + lifeCount * 60_000L
        return CgmReading(
            sourceId = SOURCE_ID,
            tsMs = GridStamper().snap(atMs),
            bgMgdl = 110,
            trendTenthsPerMin = null,
            minFromStart = lifeCount,
            quality = null,
            provenance = ReadingProvenance.MEASURED,
            flag = ReadingFlag.NORMAL,
            tzOffsetMin = 60,
            rxWallMs = atMs,
            rssi = null,
            measuredAtMs = atMs,
        )
    }

    @Test
    fun `the arm round waits for a realtime count from this session`() = runTest {
        val rig = startRig(
            sensorOverride = SENSOR.copy(lastRealtimeLifeCount = 1378),
            nextFrame = ByteArray(13),
        )
        assertEquals("the persisted 1378 is the last session's edge; nothing sent on it", 0, rig.fake.writes.size)

        testScheduler.advanceTimeBy(3 * 60_000L + 1)
        rig.now = ACTIVATION_MS + 1_600 * 60_000L
        rig.plane.onFeed = { char, _ ->
            if (char == Libre3DataChar.GlucoseData) listOf(usableRealtime(lifeCount = 1500)) else emptyList()
        }
        rig.fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
        assertEquals("past the 3-min wait the arm round gave up", 0, rig.fake.writes.size)
        assertTrue(rig.native.historicalRangeRequests.isEmpty())
        rig.drop()
    }

    @Test
    fun `the top-up and walk are skipped without a realtime life count`() = runTest {
        val rig = startRig(nextFrame = ByteArray(13))
        runCurrent()
        assertEquals("the arm round sent nothing (no realtime count)", 0, rig.fake.writes.size)
        launch { rig.stream.requestBackfill() }
        runCurrent()

        assertEquals("the tap's kit pair; top-up and walk are skipped", 2, rig.fake.writes.size)
        assertTrue(rig.native.historicalRangeRequests.isEmpty())
        rig.drop()
    }

    @Test
    fun `the top-up and walk are skipped when nothing is committed past the floor`() = runTest {
        // end = realtime 100 − 17 = 83 ≤ floor 90: neither the top-up nor a walk window fits.
        val rig = startRig(
            sensorOverride = SENSOR.copy(
                lastHistoricalLifeCount = 90,
                lastHistoricalFloorLifeCount = 90,
                lastRealtimeLifeCount = 100,
            ),
            nextFrame = ByteArray(13),
        )
        runCurrent()
        assertEquals("the arm round sent nothing (no realtime this session)", 0, rig.fake.writes.size)
        launch { rig.stream.requestBackfill() }
        runCurrent()

        assertEquals("the tap adds its pair; the top-up and walk are skipped", 2, rig.fake.writes.size)
        assertTrue(rig.native.historicalRangeRequests.isEmpty())
        assertTrue(rig.native.clinicalRangeRequests.isEmpty())
        rig.drop()
    }

    @Test
    fun `a clinical record is logged, never stored`() = runTest {
        val rig = startRig()
        rig.plane.onFeed = { char, _ ->
            if (char == Libre3DataChar.ClinicalData) {
                listOf(Libre3DataUpdate.Clinical(lifeCount = 90, currentMgdl = 111, historicMgdl = null))
            } else {
                emptyList()
            }
        }
        rig.fake.deliverDataNotify(Libre3DataChar.ClinicalData, ByteArray(14))
        runCurrent()

        assertTrue("no data-quality word, so no row", rig.repo.upsertedReadings.isEmpty())
        assertTrue(rig.readings.isEmpty())
        assertFalse("no bound advances", rig.repo.sensorSecrets.containsKey(SOURCE_ID))
        rig.drop()
    }

    // MARK: - source wiring (start → Established → stream; registry-style readings capture)

    @Test
    fun `the source pairs, hands the link to the stream and feeds readings`() = runTest {
        val machine = ScriptedPairingMachine(Libre3PairingAction.Established(kEnc, ivEnc, null))
        val plane = ScriptedDataPlane()
        val native = FakeLibre3Native().apply {
            tablesVerify = Libre3Call.Ok(Unit)
            ephemeral = Libre3Call.Ok(
                Libre3FirstPairEphemeral(
                    privateBe32 = ByteArray(32),
                    publicKey65 = ByteArray(65),
                    nullEntropy11a = ByteArray(0x11A),
                    nullScalarWindow = ByteArray(70),
                    attempts = 1,
                ),
            )
            firstPairMachine = machine
            dataPlane = plane
        }
        val repo = FakeCgmRepository()
        val fake = FakeLibre3GattTransport()
        val firstReading = CompletableDeferred<CgmReading>()
        val source = Libre3ConnectedSource(
            descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS),
            transport = fake,
            native = native,
            tablesDir = TABLES_DIR,
            sensor = SENSOR,
            repository = repo,
            scope = this,
            nowMs = { T0 },
            tzOffsetMinFor = { 60 },
            armBackfillDelayMs = 0L,
        )
        // The registry collects readings() BEFORE start() — replay=0, so subscribe first.
        // nextFrame armed early: the arm round's kit pair encrypts and writes.
        plane.nextFrame = ByteArray(13)
        launch { firstReading.complete(source.readings().first()) }
        source.start()
        runCurrent()

        // The machine scripts straight to Established: no pairing writes, link stays open.
        assertTrue(fake.connectCalled)
        assertFalse("the link stays open for the data plane (§4)", fake.closed)
        assertTrue(fake.enableDataCharsCalled)
        plane.onFeed = { char, _ -> if (char == Libre3DataChar.GlucoseData) listOf(usableRealtime()) else emptyList() }
        fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()

        assertEquals(CgmSourceStatus.Live, source.status.value)
        assertEquals("readings() flows from the stream", 103, firstReading.await().bgMgdl)
        val stored = Libre3SensorState.decode(repo.sensorSecrets.getValue(SOURCE_ID))
        assertEquals(60, stored!!.lastRealtimeLifeCount)

        // Top-up [0, 43] = realtime 60 − 17 lag, on arm and on tap; nothing below 0 to walk.
        val afterArm = fake.writes.size
        source.requestBackfill()
        runCurrent()
        assertEquals("the arm round tops up once realtime 60 lands, historical only", 1, afterArm)
        assertEquals(listOf(0 to 43, 0 to 43), native.historicalRangeRequests)
        assertEquals(
            "the tap's pair plus both top-up ranges reach patchControl",
            List(4) { Libre3DataChar.PatchControl.uuid },
            fake.writes.subList(afterArm, fake.writes.size).map { it.first },
        )

        source.close()
        assertTrue("close is idempotent after the stream's own teardown", fake.closed)
    }

    @Test
    fun `the source polls rssi and stamps it onto readings`() = runTest {
        val machine = ScriptedPairingMachine(Libre3PairingAction.Established(kEnc, ivEnc, null))
        val plane = ScriptedDataPlane()
        val native = FakeLibre3Native().apply {
            tablesVerify = Libre3Call.Ok(Unit)
            ephemeral = Libre3Call.Ok(
                Libre3FirstPairEphemeral(
                    privateBe32 = ByteArray(32),
                    publicKey65 = ByteArray(65),
                    nullEntropy11a = ByteArray(0x11A),
                    nullScalarWindow = ByteArray(70),
                    attempts = 1,
                ),
            )
            firstPairMachine = machine
            dataPlane = plane
        }
        val repo = FakeCgmRepository()
        val fake = FakeLibre3GattTransport().apply { scriptedRssi = -71 }
        val source = Libre3ConnectedSource(
            descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS),
            transport = fake,
            native = native,
            tablesDir = TABLES_DIR,
            sensor = SENSOR,
            repository = repo,
            scope = this,
            nowMs = { T0 },
            tzOffsetMinFor = { 60 },
        )
        source.start()
        runCurrent()
        assertEquals("the first poll waits out the cadence (15 s virtual)", 0, fake.readRemoteRssiCount)
        testScheduler.advanceTimeBy(15_000)
        runCurrent()
        assertEquals("the poll asks the link for RSSI", 1, fake.readRemoteRssiCount)
        assertEquals(-71, source.rssi.value)

        plane.onFeed = { char, _ -> if (char == Libre3DataChar.GlucoseData) listOf(usableRealtime()) else emptyList() }
        fake.deliverDataNotify(Libre3DataChar.GlucoseData, glucosePrefix)
        runCurrent()
        assertEquals("the polled rssi rides the readings (Ct5's lastRssi)", -71, repo.upsertedReadings.single().rssi)

        source.close()
        assertTrue(fake.closed)
    }

    /** Some(0) made every realtime reading "expired" and dropped it while values kept coming. */
    @Test
    fun `a sensor that states a wear of 0 starts its data plane with no wear at all`() = runTest {
        suspend fun wearSent(sensor: Libre3SensorState): Int? {
            val native = FakeLibre3Native().apply {
                tablesVerify = Libre3Call.Ok(Unit)
                ephemeral = Libre3Call.Ok(
                    Libre3FirstPairEphemeral(
                        privateBe32 = ByteArray(32),
                        publicKey65 = ByteArray(65),
                        nullEntropy11a = ByteArray(0x11A),
                        nullScalarWindow = ByteArray(70),
                        attempts = 1,
                    ),
                )
                firstPairMachine = ScriptedPairingMachine(Libre3PairingAction.Established(kEnc, ivEnc, null))
                dataPlane = ScriptedDataPlane()
            }
            val source = Libre3ConnectedSource(
                descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS),
                transport = FakeLibre3GattTransport(),
                native = native,
                tablesDir = TABLES_DIR,
                sensor = sensor,
                repository = FakeCgmRepository(),
                scope = this,
                nowMs = { T0 },
                tzOffsetMinFor = { 60 },
            )
            source.start()
            runCurrent()
            source.close()
            return native.dataPlaneRequests.single().wearDurationMin
        }

        assertNull(wearSent(SENSOR.copy(wearDurationMin = 0)))
        assertEquals(21_600, wearSent(SENSOR))
    }

    @Test
    fun `a pairing failure surfaces as a classified failure note`() = runTest {
        val native = FakeLibre3Native().apply {
            tablesVerify = Libre3Call.Failed("runtime table missing: sbox_12bit_full.bin")
        }
        val fake = FakeLibre3GattTransport()
        val source = Libre3ConnectedSource(
            descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS),
            transport = fake,
            native = native,
            tablesDir = TABLES_DIR,
            sensor = SENSOR,
            repository = FakeCgmRepository(),
            scope = this,
            nowMs = { T0 },
            tzOffsetMinFor = { 60 },
        )
        source.start()
        runCurrent()

        assertEquals(CgmSourceStatus.SignalLost, source.status.value)
        assertEquals(
            "§15: the tables gate names the offender on the row",
            "tables: runtime table missing: sbox_12bit_full.bin",
            source.failure.value,
        )
        assertTrue("fail-closed: the transport died with the refused session", fake.closed)
        source.close()
        assertTrue(fake.closed)
    }

    @Test
    fun `an account mismatch surfaces as the account class, not a raw step`() = runTest {
        val native = FakeLibre3Native().apply { ephemeral = Libre3Call.AccountMismatch }
        val fake = FakeLibre3GattTransport()
        val source = Libre3ConnectedSource(
            descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS),
            transport = fake,
            native = native,
            tablesDir = TABLES_DIR,
            sensor = SENSOR,
            repository = FakeCgmRepository(),
            scope = this,
            nowMs = { T0 },
            tzOffsetMinFor = { 60 },
        )
        source.start()
        runCurrent()

        assertEquals("§15: 0xB1 reads as the account class on the row", "account/region mismatch", source.failure.value)
        source.close()
    }

    @Test
    fun `a stream failure sets its own failure note`() = runTest {
        val machine = ScriptedPairingMachine(Libre3PairingAction.Established(kEnc, ivEnc, null))
        val native = FakeLibre3Native().apply {
            tablesVerify = Libre3Call.Ok(Unit)
            ephemeral = Libre3Call.Ok(
                Libre3FirstPairEphemeral(
                    privateBe32 = ByteArray(32),
                    publicKey65 = ByteArray(65),
                    nullEntropy11a = ByteArray(0x11A),
                    nullScalarWindow = ByteArray(70),
                    attempts = 1,
                ),
            )
            firstPairMachine = machine
            dataPlaneCall = Libre3Call.Failed("wrongKEncSize(32)")
        }
        val fake = FakeLibre3GattTransport()
        val source = Libre3ConnectedSource(
            descriptor = Libre3FamilyDriver.descriptorFor(ADDRESS),
            transport = fake,
            native = native,
            tablesDir = TABLES_DIR,
            sensor = SENSOR,
            repository = FakeCgmRepository(),
            scope = this,
            nowMs = { T0 },
            tzOffsetMinFor = { 60 },
        )
        source.start()
        runCurrent()

        assertEquals(CgmSourceStatus.SignalLost, source.status.value)
        assertEquals("data plane: wrongKEncSize(32)", source.failure.value)
        assertTrue(fake.closed)
        source.close()
    }

    // MARK: - fixture constants

    private val kEnc = ByteArray(16) { (it + 1).toByte() }
    private val ivEnc = ByteArray(8) { (it + 1).toByte() }

    private companion object {
        const val ADDRESS = "C0:FF:EE:C0:FF:EE"
        const val TABLES_DIR = "/data/user/0/com.t1dm.app/files/libre3/tables"
        const val PAGE_SAMPLES = 6

        /** Epoch seconds; [T0] sits 200 min into the wear, past the backfill page samples. */
        const val ACTIVATION_S = 1_790_000_000L
        const val ACTIVATION_MS = ACTIVATION_S * 1000L

        /** 200 minutes after activation: realtime 60–170 and the 120–145 page all derive to the past. */
        const val T0 = ACTIVATION_MS + 200 * 60_000L

        val SOURCE_ID = Libre3FamilyDriver.sourceIdFor(ADDRESS)

        val SENSOR = Libre3SensorState(
            receiverId = 0x684FC53Fu,
            serial = "0T1DM0000",
            bleAddress = ADDRESS,
            blePin = byteArrayOf(0x11, 0x22, 0x33, 0x44),
            activationTimeS = ACTIVATION_S,
            wearDurationMin = 21_600,
            region = Libre3Region.Eu,
            provisionedAtMs = 0L,
            kAuth = null,
        )
    }
}