package com.t1dm.feature.cgm

import androidx.compose.ui.text.AnnotatedString
import com.t1dm.core.model.CgmLogEntry
import com.t1dm.core.model.CgmLogKind
import com.t1dm.core.model.CgmLogTopic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
private val HEX = "0123456789ABCDEF".toCharArray()

/** Monotonic clocks further apart than this in their wall deltas are taken as different boots. */
private const val SAME_BOOT_SLACK_MS = 5_000L

internal fun clockOf(ms: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(zone))

/** Monotonic when both lines came from one boot, else wall clock. */
internal fun deltaMs(prev: CgmLogEntry?, e: CgmLogEntry): Long? {
    if (prev == null) return null
    val wall = e.wallMs - prev.wallMs
    val mono = (e.monoNs - prev.monoNs) / 1_000_000L
    val sameBoot = prev.monoNs > 0 && e.monoNs > 0 && mono >= 0 && kotlin.math.abs(mono - wall) < SAME_BOOT_SLACK_MS
    return if (sameBoot) mono else wall
}

/** `+0.101`, `+12.345`, `+12m03s`, `+3h12m`; a clock step back shows its sign. */
internal fun deltaLabel(ms: Long?): String {
    if (ms == null) return "      "
    val sign = if (ms < 0) "-" else "+"
    val a = kotlin.math.abs(ms)
    return when {
        a < 60_000 -> "$sign${a / 1000}.${(a % 1000).toString().padStart(3, '0')}"
        a < 3_600_000 -> "$sign${a / 60_000}m${((a / 1000) % 60).toString().padStart(2, '0')}s"
        else -> "$sign${a / 3_600_000}h${((a / 60_000) % 60).toString().padStart(2, '0')}m"
    }
}

internal fun kindTag(k: CgmLogKind): String = when (k) {
    CgmLogKind.TX -> "TX "
    CgmLogKind.RX -> "RX "
    CgmLogKind.GATT -> "BLE"
    CgmLogKind.DEC -> "DEC"
    CgmLogKind.LOG -> "LOG"
}

internal fun topicName(t: CgmLogTopic): String = t.name.lowercase()

/** Space-separated upper-case pairs from [from] until [to]. */
internal fun hexOf(b: ByteArray, from: Int = 0, to: Int = b.size): String {
    if (to <= from) return ""
    val out = CharArray((to - from) * 3 - 1)
    var o = 0
    for (i in from until to) {
        if (i > from) out[o++] = ' '
        val v = b[i].toInt() and 0xFF
        out[o++] = HEX[v ushr 4]
        out[o++] = HEX[v and 0xF]
    }
    return String(out)
}

/** A fold's value range with the unit its topic carries, or empty. */
internal fun valueRange(topic: CgmLogTopic, lo: Int?, hi: Int?): String {
    if (lo == null || hi == null) return ""
    val unit = when (topic) {
        CgmLogTopic.RSSI, CgmLogTopic.ADVERT -> "dBm"
        else -> "mg/dL"
    }
    return if (lo == hi) "$lo $unit" else "$lo–$hi $unit"
}

private const val LINE_TAG = "cgm-log-line"

/** Compose copies several selected Texts with no separator; this mark survives the copy. */
internal fun AnnotatedString.Builder.markLine(block: AnnotatedString.Builder.() -> Unit) {
    pushStringAnnotation(LINE_TAG, "")
    block()
    pop()
}

/** A copied selection, a newline before every marked line but the first. */
internal fun joinMarkedLines(s: AnnotatedString): String {
    val starts = s.getStringAnnotations(LINE_TAG, 0, s.length).mapTo(HashSet()) { it.start }
    return buildString(s.length + starts.size) {
        s.text.forEachIndexed { i, c ->
            if (i > 0 && i in starts) append('\n')
            append(c)
        }
    }
}

fun cgmLogFileName(id: String, nowMs: Long, zone: ZoneId): String =
    "t1dm-cgm-" + id.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("") +
        "-" + FILE_STAMP.format(Instant.ofEpochMilli(nowMs).atZone(zone)) + ".log"

/** One line per entry, oldest first, bytes in hex after a bar. */
fun writeCgmLogExport(
    out: Appendable,
    name: String,
    id: String,
    entries: List<CgmLogEntry>,
    zone: ZoneId,
    nowMs: Long,
) {
    out.append("# T1DM CGM log · ").append(name).append(" (").append(id).append(") · exported ")
        .append(STAMP.format(Instant.ofEpochMilli(nowMs).atZone(zone))).append(' ').append(zone.id)
        .append(" · ").append(entries.size.toString()).append(" lines\n")
    var prev: CgmLogEntry? = null
    for (e in entries) {
        out.append(STAMP.format(Instant.ofEpochMilli(e.wallMs).atZone(zone))).append(' ')
            .append(deltaLabel(deltaMs(prev, e)).padEnd(8)).append(' ')
            .append(e.level.name).append(' ')
            .append(kindTag(e.kind)).append(' ')
            .append(topicName(e.topic).padEnd(8)).append(' ')
        e.channel?.let { out.append('[').append(it).append("] ") }
        out.append(e.text)
        e.value?.let { out.append(" {").append(it.toString()).append('}') }
        e.bytes?.let { out.append(" | ").append(hexOf(it)) }
        out.append('\n')
        prev = e
    }
}
