package com.t1dm.watch.proto

import com.t1dm.core.model.AlertBand
import com.t1dm.core.model.ForecastStatus
import com.t1dm.watch.crypto.SealedFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The frozen, little-endian byte layout of a [WatchPush] plaintext, plus the sealed WIRE frame that
 * wraps a [SealedFrame] for the PUSH characteristic (docs/WATCH_BLE.md §Push). Pure, deterministic,
 * and golden-tested so the firmware decoder and the app encoder cannot drift.
 *
 * Plaintext (17-byte fixed head + N-byte summary, N ≤ [WatchPush.MAX_SUMMARY]):
 * ```
 *  off  type  field
 *   0   u8    payload_version
 *   1   u8    status_bits              (WatchStatus.toBits)
 *   2   i16   bg_mgdl                  (-1 = none)
 *   4   i16   trend_tenths             (Short.MIN_VALUE = none)
 *   6   u8    alert_band               (0..4 URGENT_LOW..URGENT_HIGH; 0xFF none)
 *   7   u8    forecast_status          (0..4; 0xFF none)
 *   8   i16   fc_end_mgdl              (-1 = none)
 *  10   u8    fc_horizon_steps         (each step = 5 min)
 *  11   u8    fc_trend                 (WatchTrend ordinal)
 *  12   u32   reading_age_s            (seconds since last MEASURED reading)
 *  16   u8    summary_len N
 *  17  ..N    summary                  (UTF-8)
 * ```
 *
 * Sealed wire frame written to PUSH — the authoritative record of docs/WATCH_BLE.md §6.1 (the 13-byte
 * cleartext header is also the AEAD associated data; there is NO magic or direction byte — the two
 * directions are separated by KEY, not by a wire flag):
 * ```
 *   0   u8    version = 0x01
 *   1   u32   epoch                    (little-endian)
 *   5   u64   seq                      (little-endian; the windowed nonce counter)
 *  13   ..    ciphertext || 16-byte GCM tag
 * ```
 * The [crypto.WatchSession] produces and consumes this record whole (the header is built inside the
 * cipher so `epoch`/`seq` are authenticated); this codec only serialises the plaintext and offers a
 * header parse for the panel/tests.
 */
object WatchPushCodec {

    /** Frame version byte leading every sealed record (docs/WATCH_BLE.md §9.1). */
    const val FRAME_VERSION = 0x01
    /** `version(1) || epoch(u32le) || seq(u64le)` = the authoritative header / AEAD AAD length. */
    const val HEADER_LEN = 13
    private const val TAG_LEN = 16
    private const val NONE_I16 = -1

    // ── Plaintext ───────────────────────────────────────────────────────────────────────────────

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

    // ── Sealed wire frame (authoritative §6.1) ──────────────────────────────────────────────────

    /** The bytes written to the PUSH characteristic ARE the sealed record; this is the identity that
     *  names the intent at the call site. The header (version/epoch/seq) lives inside [SealedFrame.frame],
     *  built and authenticated by the cipher — the codec never prepends anything. */
    fun wireFrame(sealed: SealedFrame): ByteArray = sealed.frame

    /** Parse the 13-byte authoritative header of a PUSH record; null if too short or a wrong version.
     *  Returns `(epoch, SealedFrame(seq, wholeRecord))` — the whole record is what the peer opens. */
    fun parseWireFrame(b: ByteArray): Pair<Int, SealedFrame>? {
        if (b.size < HEADER_LEN + TAG_LEN) return null
        if (b[0].toInt() and 0xFF != FRAME_VERSION) return null
        val hdr = ByteBuffer.wrap(b, 1, 12).order(ByteOrder.LITTLE_ENDIAN)
        val epoch = hdr.int.toLong() and 0xFFFFFFFFL
        val seq = hdr.long
        return epoch.toInt() to SealedFrame(seq, b)
    }

    // ── enum ↔ wire ─────────────────────────────────────────────────────────────────────────────

    private fun bandToWire(band: AlertBand?): Int = band?.ordinal ?: 0xFF
    private fun wireToBand(v: Int): AlertBand? = AlertBand.entries.getOrNull(v)
    private fun statusToWire(s: ForecastStatus?): Int = s?.ordinal ?: 0xFF
    private fun wireToStatus(v: Int): ForecastStatus? = ForecastStatus.entries.getOrNull(v)
}
