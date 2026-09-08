package com.t1dm.watch.proto

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ForecastStatus
import com.t1dm.watch.crypto.SealedFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frozen LE [WatchPush] layout (17B head+summary), PUSH char §6.1; golden-tested vs firmware. */
object WatchPushCodec {

    /** docs/WATCH_BLE.md §9.1. */
    const val FRAME_VERSION = 0x01
    /** `version(1) || epoch(u32le) || seq(u64le)`, also the AEAD associated data. */
    const val HEADER_LEN = 13
    private const val TAG_LEN = 16
    private const val NONE_I16 = -1

    fun encode(push: WatchPush): ByteArray {
        val summaryBytes = push.summary.encodeToByteArray().let {
            if (it.size <= WatchPush.MAX_SUMMARY) it else it.copyOf(WatchPush.MAX_SUMMARY)
        }
        val buf = ByteBuffer.allocate(17 + summaryBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(WatchPush.PAYLOAD_VERSION.toByte())
        buf.put(push.status.toBits().toByte())
        buf.putShort((push.bgMgdl ?: NONE_I16).toShort())
        buf.putShort((push.trendTenths?.toShort()) ?: Short.MIN_VALUE)
        buf.put(bandToWire(push.alertBand).toByte())
        buf.put(statusToWire(push.forecastStatus).toByte())
        buf.putShort((push.fcEndMgdl ?: NONE_I16).toShort())
        buf.put(push.fcHorizonSteps.coerceIn(0, 255).toByte())
        buf.put(push.fcTrend.ordinal.toByte())
        buf.putInt((push.readingAgeMs / 1000L).coerceIn(0, 0xFFFFFFFFL).toInt())
        buf.put(summaryBytes.size.toByte())
        buf.put(summaryBytes)
        return buf.array()
    }

    fun decode(b: ByteArray): WatchPush {
        require(b.size >= 17) { "watch push plaintext too short: ${b.size}" }
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // payload_version
        val status = WatchStatus.fromBits(buf.get().toInt() and 0xFF)
        val bg = buf.short.toInt().let { if (it == NONE_I16) null else it }
        val trend = buf.short.let { if (it == Short.MIN_VALUE) null else it.toInt() }
        val band = wireToBand(buf.get().toInt() and 0xFF)
        val fcStatus = wireToStatus(buf.get().toInt() and 0xFF)
        val fcEnd = buf.short.toInt().let { if (it == NONE_I16) null else it }
        val horizon = buf.get().toInt() and 0xFF
        val trendClass = WatchTrend.entries[(buf.get().toInt() and 0xFF).coerceIn(0, WatchTrend.entries.lastIndex)]
        val ageS = buf.int.toLong() and 0xFFFFFFFFL
        val n = buf.get().toInt() and 0xFF
        val summary = if (n > 0 && buf.remaining() >= n) ByteArray(n).also(buf::get).decodeToString() else ""
        return WatchPush(
            bgMgdl = bg,
            trendTenths = trend,
            readingAgeMs = ageS * 1000L,
            alertBand = band,
            forecastStatus = fcStatus,
            fcEndMgdl = fcEnd,
            fcHorizonSteps = horizon,
            fcTrend = trendClass,
            summary = summary,
            status = status,
        )
    }

    /** The sealed record IS the wire frame; the cipher already built/authenticated its header. */
    fun wireFrame(sealed: SealedFrame): ByteArray = sealed.frame

    /** `(epoch, SealedFrame(seq, record))`; null if too short or wrong version (fail-closed). */
    fun parseWireFrame(b: ByteArray): Pair<Int, SealedFrame>? {
        if (b.size < HEADER_LEN + TAG_LEN) return null
        if (b[0].toInt() and 0xFF != FRAME_VERSION) return null
        val hdr = ByteBuffer.wrap(b, 1, 12).order(ByteOrder.LITTLE_ENDIAN)
        val epoch = hdr.int.toLong() and 0xFFFFFFFFL
        val seq = hdr.long
        return epoch.toInt() to SealedFrame(seq, b)
    }

    private fun bandToWire(band: AlertBand?): Int = band?.ordinal ?: 0xFF
    private fun wireToBand(v: Int): AlertBand? = AlertBand.entries.getOrNull(v)
    private fun statusToWire(s: ForecastStatus?): Int = s?.ordinal ?: 0xFF
    private fun wireToStatus(v: Int): ForecastStatus? = ForecastStatus.entries.getOrNull(v)
}
