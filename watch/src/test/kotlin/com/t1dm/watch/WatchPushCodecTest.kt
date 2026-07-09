package com.t1dm.watch

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ForecastStatus
import com.t1dm.watch.crypto.SealedFrame
import com.t1dm.watch.proto.WatchPush
import com.t1dm.watch.proto.WatchPushCodec
import com.t1dm.watch.proto.WatchStatus
import com.t1dm.watch.proto.WatchTrend
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the frozen [WatchPush] byte layout + the sealed wire framing (docs/WATCH_BLE.md §Push). */
class WatchPushCodecTest {

    private val full = WatchPush(
        bgMgdl = 142,
        trendTenths = -12,
        readingAgeMs = 125_000L,
        alertBand = AlertBand.IN_RANGE,
        forecastStatus = ForecastStatus.OK,
        fcEndMgdl = 96,
        fcHorizonSteps = 24,
        fcTrend = WatchTrend.FALLING,
        summary = "falling to ~96 in 2h",
        status = WatchStatus(stale = false, warmup = false, predictedLowCrossing = true, alarmActive = true),
    )

    @Test fun `plaintext round-trips every field`() {
        val got = WatchPushCodec.decode(WatchPushCodec.encode(full))
        assertEquals(full.bgMgdl, got.bgMgdl)
        assertEquals(full.trendTenths, got.trendTenths)
        assertEquals(full.readingAgeMs, got.readingAgeMs) // second-granular; input is a whole second
        assertEquals(full.alertBand, got.alertBand)
        assertEquals(full.forecastStatus, got.forecastStatus)
        assertEquals(full.fcEndMgdl, got.fcEndMgdl)
        assertEquals(full.fcHorizonSteps, got.fcHorizonSteps)
        assertEquals(full.fcTrend, got.fcTrend)
        assertEquals(full.summary, got.summary)
        assertEquals(full.status, got.status)
    }

    @Test fun `null bg trend forecast band encode as sentinels`() {
        val empty = full.copy(bgMgdl = null, trendTenths = null, alertBand = null, forecastStatus = null, fcEndMgdl = null)
        val got = WatchPushCodec.decode(WatchPushCodec.encode(empty))
        assertNull(got.bgMgdl)
        assertNull(got.trendTenths)
        assertNull(got.alertBand)
        assertNull(got.forecastStatus)
        assertNull(got.fcEndMgdl)
    }

    @Test fun `oversize summary is truncated to the cap`() {
        val long = full.copy(summary = "x".repeat(WatchPush.MAX_SUMMARY + 20))
        val got = WatchPushCodec.decode(WatchPushCodec.encode(long))
        assertEquals(WatchPush.MAX_SUMMARY, got.summary.length)
    }

    @Test fun `status bitfield is exact`() {
        val s = WatchStatus(
            stale = true, signalLoss = true, warmup = true, predictedLowCrossing = true,
            predictedHighCrossing = true, alarmActive = true, forecastUnavailable = true, lowPowerSuspending = true,
        )
        assertEquals(0xFF, s.toBits())
        assertEquals(s, WatchStatus.fromBits(s.toBits()))
        assertEquals(0, WatchStatus().toBits())
    }

    @Test fun `wire frame is the authoritative record verbatim and its header parses`() {
        // A hand-built §6.1 record: ver=0x01, epoch=3 (u32le), seq=0x01020304 (u64le), then ct||tag.
        val seq = 0x01020304L
        val header = ByteArray(WatchPushCodec.HEADER_LEN)
        header[0] = WatchPushCodec.FRAME_VERSION.toByte()
        header[1] = 3 // epoch low byte (u32le)
        for (i in 0 until 8) header[5 + i] = ((seq ushr (i * 8)) and 0xFF).toByte()
        val frame = header + byteArrayOf(9, 8, 7) + ByteArray(16)
        val sealed = SealedFrame(seq = seq, frame = frame)

        assertArrayEquals("wire frame must be the record verbatim (no added header)", frame, WatchPushCodec.wireFrame(sealed))
        val (epoch, back) = WatchPushCodec.parseWireFrame(frame)!!
        assertEquals(3, epoch)
        assertEquals(seq, back.seq)
        assertArrayEquals(frame, back.frame)
    }

    @Test fun `parseWireFrame rejects a short or wrong-version frame`() {
        assertNull(WatchPushCodec.parseWireFrame(ByteArray(4)))
        // One byte shy of header(13)+tag(16).
        assertNull(WatchPushCodec.parseWireFrame(ByteArray(28).also { it[0] = 0x01 }))
        // Correct length but a non-0x01 version byte.
        assertNull(WatchPushCodec.parseWireFrame(ByteArray(29).also { it[0] = 0x02 }))
    }
}
