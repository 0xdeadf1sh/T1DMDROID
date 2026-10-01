package com.t1dm.feature.cgm

import com.t1dm.core.model.CgmLogEntry
import com.t1dm.core.model.CgmLogKind
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.statusWord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** One sensor's side of the app. Actions return at once; the sensor's log shows how they went. */
interface CgmConsoleHost {
    /** Written to the sensor's log file; replies never are. */
    fun echo(line: String)
    suspend fun readingStats(): CgmReadingStats

    /** Newest first. */
    suspend fun lastReadings(n: Int): List<CgmReading>
    fun connect()
    fun disconnect()
    fun reconnect()
    fun fetch()
    fun scan()
    fun makeMain()
    fun hide()
    fun setWarmupMin(min: Int)
    fun activate()
    fun bind()
    fun repair()
    fun recoverKey()
    fun provision()
    fun save()

    /** The live session key, off the stream: nothing sent, no counter moved. */
    fun openFrames(chunks: List<CgmCapturedChunk>): CgmFrameResult<List<CgmOpenedFrame>>
    fun sealFrame(kind: Int, sequence: Int, plaintext: ByteArray): CgmFrameResult<ByteArray>
}

/** [measured]: measured, valid values; the rest are gap fills, reconstructions and warm-up rows. */
class CgmReadingStats(val total: Int, val measured: Int, val firstMs: Long?, val lastMs: Long?)

/** [channel] null = unassembled, every packet kind tried. */
class CgmCapturedChunk(val channel: String?, val bytes: ByteArray)

/** Null [kind]: no tag verifies, or under 3 B. */
class CgmOpenedFrame(
    /** Index of the chunk that completed the frame. */
    val chunk: Int,
    val sequence: Int?,
    val kind: Int?,
    val plaintext: ByteArray?,
)

sealed interface CgmFrameResult<out T> {
    class Done<T>(val value: T) : CgmFrameResult<T>
    class Refused(val reason: String) : CgmFrameResult<Nothing>
    data object NotLinked : CgmFrameResult<Nothing>
}

enum class CgmReplyTone { OUT, DIM, WARN, ERR }

class CgmReplyLine(val text: String, val tone: CgmReplyTone = CgmReplyTone.OUT)

/** What the screen, not the app, must do. */
sealed interface CgmConsoleEffect {
    data object Clear : CgmConsoleEffect

    /** Null turns the filter off. */
    class Grep(val text: String?) : CgmConsoleEffect

    data object Exit : CgmConsoleEffect
}

class CgmConsoleReply(val lines: List<CgmReplyLine>, val effect: CgmConsoleEffect? = null)

/** What a command reads; [row] is null once the panel no longer lists the sensor. */
class CgmConsoleView(
    val row: CgmSensorRow?,
    val panel: CgmPanelState,
    /** The whole log, oldest first, unfiltered and unfrozen. */
    val entries: List<CgmLogEntry>,
    val zone: ZoneId,
    val nowMs: Long,
)

internal class Command(
    val name: String,
    val args: String,
    val help: String,
    val aliases: List<String> = emptyList(),
    /** Destructive: runs only on a `yes` typed within [CONFIRM_WINDOW_MS]. */
    val destructive: Boolean = false,
    /** Listed by `help` for this sensor. */
    val offered: (CgmSensorRow?) -> Boolean = { true },
)

private val COMMANDS = listOf(
    Command("help", "[cmd]", "list commands"),
    Command("info", "", "sensor and link state"),
    Command("status", "", "link state"),
    Command("caps", "", "what this sensor supports"),
    Command("rssi", "", "signal strength"),
    Command("tel", "", "telemetry"),
    Command("links", "", "radio links in use"),
    Command("db", "", "readings on record"),
    Command("last", "[n]", "newest readings"),
    Command("raw", "[n]", "last received frames, hex"),
    Command("decode", "[n]", "last received frames, decoded"),
    Command("decrypt", "[n|hex]", "open frames; this link's key only", offered = { it?.hasFrameCrypto == true }),
    Command("encrypt", "kind seq hex", "seal a frame; not sent", offered = { it?.hasFrameCrypto == true }),
    Command("clear", "", "clear the screen"),
    Command("grep", "[text]", "show matching lines; none = all"),
    Command("save", "", "save the log to a file"),
    Command("exit", "", "back to the CGM panel", aliases = listOf("quit")),
    Command("connect", "", "start reading"),
    Command("disconnect", "", "stop reading", destructive = true),
    Command("reconnect", "", "rebuild the link"),
    Command("fetch", "", "pull sensor history", offered = { it?.hasHistory == true }),
    Command("scan", "", "search for sensors"),
    Command("main", "", "make this the main sensor"),
    Command("hide", "", "drop from the list; readings kept", destructive = true),
    Command("warmup", "[min]", "warm-up window"),
    Command("activate", "", "activate the sensor", destructive = true, offered = { it?.hasActivate == true }),
    Command("bind", "", "claim the sensor for good", destructive = true, offered = { it?.canBind == true }),
    Command(
        "repair", "", "re-date the wear; deletes readings", destructive = true,
        offered = { it?.hasRepairHistory == true },
    ),
    Command(
        "recoverkey", "", "replace the key; deletes readings", destructive = true,
        offered = { it?.hasRecoverKey == true },
    ),
    Command("provision", "", "NFC setup", destructive = true, offered = { it?.hasProvision == true }),
)

/** Parses and runs one typed line. Holds the pending confirm and the reconnect throttle. */
class CgmConsole(private val host: CgmConsoleHost) {
    private class Pending(val command: Command, val args: List<String>, val atMs: Long)

    private var pending: Pending? = null
    private var lastReconnectMs: Long? = null

    suspend fun run(input: String, view: CgmConsoleView): CgmConsoleReply {
        val line = input.trim()
        if (line.isEmpty()) return CgmConsoleReply(emptyList())
        host.echo("> $line")
        val words = line.split(WHITESPACE)
        val name = words[0].lowercase()
        val args = words.drop(1)
        val waiting = pending
        pending = null
        if (name == YES && args.isEmpty()) {
            if (waiting == null) return reply(dim("nothing to confirm"))
            if (view.nowMs - waiting.atMs > CONFIRM_WINDOW_MS) return reply(err("expired; run ${waiting.command.name} again"))
            return execute(waiting.command, waiting.args, view, confirmed = true)
        }
        val result = dispatch(name, args, view)
        if (waiting == null) return result
        return CgmConsoleReply(listOf(dim("${waiting.command.name} cancelled")) + result.lines, result.effect)
    }

    private suspend fun dispatch(name: String, args: List<String>, view: CgmConsoleView): CgmConsoleReply {
        val command = commandNamed(name)
            ?: return reply(err("unknown: $name · help lists commands"))
        return execute(command, args, view, confirmed = false)
    }

    private suspend fun execute(c: Command, args: List<String>, v: CgmConsoleView, confirmed: Boolean): CgmConsoleReply {
        when (c.name) {
            "help" -> return help(args, v.row)
            "links" -> return reply(links(v))
            "db" -> return reply(db(v))
            "last" -> return count(args, 5, 50) { n -> last(n, v.zone) }
            "raw" -> return count(args, 1, 10) { n -> raw(n, v) }
            "decode" -> return count(args, 1, 10) { n -> decoded(n, v) }
            "clear" -> return CgmConsoleReply(emptyList(), CgmConsoleEffect.Clear)
            "exit" -> return CgmConsoleReply(emptyList(), CgmConsoleEffect.Exit)
            "grep" -> {
                val text = args.joinToString(" ").ifEmpty { null }
                return CgmConsoleReply(listOf(out(if (text == null) "grep off" else "grep $text")), CgmConsoleEffect.Grep(text))
            }
            "save" -> {
                host.save()
                return reply(out("pick a file"))
            }
            "scan" -> {
                if (v.panel.scanning) return reply(out("already scanning"))
                host.scan()
                return reply(out("scanning"))
            }
        }
        val row = v.row ?: return reply(err("sensor not listed"))
        return when (c.name) {
            "info" -> reply(info(row, v))
            "status" -> reply(status(row))
            "caps" -> reply(caps(row))
            "rssi" -> reply(out(row.rssiDbm?.let { "$it dBm" } ?: "no RSSI yet"))
            "tel" -> reply(telemetry(row, v.zone))
            "decrypt" -> if (row.hasFrameCrypto) decrypt(args, v) else reply(err(NO_FRAME_CRYPTO))
            "encrypt" -> if (row.hasFrameCrypto) encrypt(args) else reply(err(NO_FRAME_CRYPTO))
            "connect" -> when {
                row.active && row.canReconnect -> reply(out("on, not linked · reconnect rebuilds it"))
                row.active -> reply(out("already on"))
                else -> {
                    host.connect()
                    reply(out("reading on"))
                }
            }
            "disconnect" -> destructive(
                c, args, v, confirmed,
                refusal = when {
                    row.authoritative -> "main sensor · make another main first"
                    !row.active -> "not on"
                    else -> null
                },
                prompt = "stop reading ${row.name}?",
            ) { host.disconnect(); "reading off" }
            "reconnect" -> reconnect(row, v.nowMs)
            "fetch" -> when {
                !row.hasHistory -> reply(err("no history on this sensor"))
                row.fetchingHistory -> reply(out("already fetching"))
                row.historyExhausted -> reply(out("sensor holds nothing newer"))
                !row.canFetchHistory -> reply(err("not linked"))
                else -> {
                    host.fetch()
                    reply(out("fetching"))
                }
            }
            "main" -> if (row.authoritative) {
                reply(out("already main"))
            } else {
                host.makeMain()
                reply(out("main now"))
            }
            "hide" -> destructive(
                c, args, v, confirmed,
                refusal = if (row.authoritative) "main sensor stays listed" else null,
                prompt = "hide ${row.name}? readings kept",
            ) { host.hide(); "hidden" }
            "warmup" -> warmup(args, row)
            "activate" -> destructive(
                c, args, v, confirmed,
                refusal = when {
                    !row.hasActivate -> "no activation on this sensor"
                    !row.canActivate -> "not now · ${statusWord(row.status)}"
                    else -> null
                },
                prompt = "activate ${row.name}?",
            ) { host.activate(); "activation sent" }
            "bind" -> destructive(
                c, args, v, confirmed,
                refusal = if (!row.canBind) "not bindable" else null,
                prompt = "bind ${row.name}? claimed for good",
            ) { host.bind(); "bind sent" }
            "repair" -> destructive(
                c, args, v, confirmed,
                refusal = when {
                    !row.hasRepairHistory -> "no repair on this sensor"
                    !row.canRepairHistory -> "not now"
                    else -> null
                },
                prompt = "repair ${row.name}? deletes its readings, re-dates the wear",
            ) { host.repair(); "repair started" }
            "recoverkey" -> destructive(
                c, args, v, confirmed,
                refusal = if (!row.canRecoverKey) "no key to recover" else null,
                prompt = "recover key for ${row.name}? deletes its readings, replaces the key",
            ) { host.recoverKey(); "recovery started" }
            "provision" -> destructive(
                c, args, v, confirmed,
                refusal = if (!row.canProvision) "no NFC setup for this sensor" else null,
                prompt = "provision ${row.name}? this app becomes its reader",
            ) { host.provision(); "NFC setup open" }
            else -> reply(err("unknown: ${c.name}"))
        }
    }

    /** The refusal is asked again on `yes`: the sensor may have changed in between. */
    private inline fun destructive(
        c: Command,
        args: List<String>,
        v: CgmConsoleView,
        confirmed: Boolean,
        refusal: String?,
        prompt: String,
        act: () -> String,
    ): CgmConsoleReply {
        if (refusal != null) return reply(err(refusal))
        if (!confirmed) {
            pending = Pending(c, args, v.nowMs)
            return reply(warn("$prompt · yes to confirm, ${CONFIRM_WINDOW_MS / 1000} s"))
        }
        return reply(out(act()))
    }

    /** Throttled: a reconnect drops every hold-off its family keeps on the sensor. */
    private fun reconnect(row: CgmSensorRow, nowMs: Long): CgmConsoleReply {
        if (!row.active) return reply(err("not on · connect first"))
        val last = lastReconnectMs
        if (last != null && nowMs - last < RECONNECT_GAP_MS) {
            return reply(warn("again in ${(RECONNECT_GAP_MS - (nowMs - last) + 999) / 1000} s"))
        }
        lastReconnectMs = nowMs
        host.reconnect()
        return reply(out("reconnecting"))
    }

    private fun warmup(args: List<String>, row: CgmSensorRow): CgmConsoleReply {
        val range = CgmSourceDescriptor.WARMUP_WINDOW_RANGE
        if (args.isEmpty()) return reply(out("warm-up ${row.warmupWindowMin} min"))
        val min = args.singleOrNull()?.toIntOrNull()?.takeIf { it in range }
            ?: return reply(err("warmup ${range.first}–${range.last} min"))
        host.setWarmupMin(min)
        return reply(out("warm-up $min min"))
    }

    private fun decrypt(args: List<String>, v: CgmConsoleView): CgmConsoleReply {
        // A count is at most 2 digits, a frame at least 3 B of hex.
        if (args.size <= 1 && args.firstOrNull().orEmpty().length <= 2) {
            return count(args, 1, 10) { n -> decryptLogged(n, v) }
        }
        val frame = bytesOf(args) ?: return reply(err(BAD_HEX))
        return reply(
            host.openFrames(listOf(CgmCapturedChunk(null, frame))).orRefuse { opened -> frameLines(null, opened.singleOrNull()) },
        )
    }

    /** The newest [n] received chunks, each opened after the chunks the stream saw before it. */
    private fun decryptLogged(n: Int, v: CgmConsoleView): List<CgmReplyLine> {
        val chunks = lastOf(v.entries, n + ASSEMBLY_LOOKBACK) { it.kind == CgmLogKind.RX && it.bytes != null }
        if (chunks.isEmpty()) return listOf(out("no raw frames"))
        return host.openFrames(chunks.map { CgmCapturedChunk(it.channel, it.bytes!!) }).orRefuse { opened ->
            val byChunk = opened.associateBy { it.chunk }
            (maxOf(0, chunks.size - n) until chunks.size).flatMap { i ->
                val e = chunks[i]
                frameLines("${clockOf(e.wallMs, v.zone)} ${e.channel.orEmpty()}", byChunk[i])
            }
        }
    }

    private fun encrypt(args: List<String>): CgmConsoleReply {
        val kind = args.getOrNull(0)?.toIntOrNull()
        val sequence = args.getOrNull(1)?.toIntOrNull()
        val plaintext = bytesOf(args.drop(2))
        if (kind == null || sequence == null || plaintext == null) return reply(err("encrypt kind seq hex"))
        return reply(host.sealFrame(kind, sequence, plaintext).orRefuse { listOf(out(hexOf(it))) })
    }

    private suspend fun db(v: CgmConsoleView): List<CgmReplyLine> {
        val s = host.readingStats()
        if (s.total == 0) return listOf(out("no readings on record"))
        return buildList {
            add(kv("readings", "${s.total} · ${s.measured} measured"))
            s.firstMs?.let { add(kv("first", stampOf(it, v.zone))) }
            s.lastMs?.let { add(kv("last", stampOf(it, v.zone))) }
            if (s.firstMs != null && s.lastMs != null) add(kv("span", fullDuration(s.lastMs - s.firstMs)))
        }
    }

    private suspend fun last(n: Int, zone: ZoneId): List<CgmReplyLine> {
        val readings = host.lastReadings(n)
        if (readings.isEmpty()) return listOf(out("no readings on record"))
        return readings.asReversed().map { r ->
            val tags = listOfNotNull(
                r.provenance.takeIf { it != ReadingProvenance.MEASURED }?.name?.lowercase(),
                r.flag.takeIf { it != ReadingFlag.NORMAL }?.name?.lowercase(),
            )
            val parts = listOfNotNull(
                stampOf(r.tsMs, zone),
                r.bgMgdl?.let { "$it mg/dL" } ?: "—",
                r.trendTenthsPerMin?.let(::perMin),
                tags.takeIf { it.isNotEmpty() }?.joinToString(" "),
            )
            CgmReplyLine(parts.joinToString("  "), if (tags.isEmpty()) CgmReplyTone.OUT else CgmReplyTone.DIM)
        }
    }

    private fun help(args: List<String>, row: CgmSensorRow?): CgmConsoleReply {
        val asked = args.firstOrNull()?.lowercase()
        if (asked != null) {
            val c = commandNamed(asked) ?: return reply(err("unknown: $asked"))
            return reply(helpLine(c))
        }
        return CgmConsoleReply(COMMANDS.filter { it.offered(row) }.map(::helpLine))
    }

    private companion object {
        const val YES = "yes"
        val WHITESPACE = Regex("\\s+")
        const val NO_FRAME_CRYPTO = "no encrypt or decrypt on this sensor"
        const val BAD_HEX = "hex: whole bytes, 0–9 a–f"

        /** Received chunks read before the window, so a frame split over notifies opens whole. */
        const val ASSEMBLY_LOOKBACK = 4
    }
}

const val CONFIRM_WINDOW_MS = 30_000L
const val RECONNECT_GAP_MS = 30_000L

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun stampOf(ms: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

private fun reply(vararg lines: CgmReplyLine) = CgmConsoleReply(lines.toList())
private fun reply(lines: List<CgmReplyLine>) = CgmConsoleReply(lines)
private fun out(text: String) = CgmReplyLine(text)
private fun dim(text: String) = CgmReplyLine(text, CgmReplyTone.DIM)
private fun warn(text: String) = CgmReplyLine(text, CgmReplyTone.WARN)
private fun err(text: String) = CgmReplyLine(text, CgmReplyTone.ERR)
private fun kv(key: String, value: String, tone: CgmReplyTone = CgmReplyTone.OUT) =
    CgmReplyLine("${key.padEnd(9)}$value", tone)

private fun commandNamed(name: String): Command? = COMMANDS.firstOrNull { it.name == name || name in it.aliases }

/** Rest of the first offered name [typed] starts, table order; empty once an argument begins. */
internal fun completionOf(typed: String, row: CgmSensorRow?): String {
    val word = typed.trimStart().lowercase()
    if (word.isEmpty() || word.any(Char::isWhitespace)) return ""
    for (c in COMMANDS) {
        if (!c.offered(row)) continue
        if (c.name.length > word.length && c.name.startsWith(word)) return c.name.substring(word.length)
        for (alias in c.aliases) if (alias.length > word.length && alias.startsWith(word)) return alias.substring(word.length)
    }
    return ""
}

private fun helpLine(c: Command): CgmReplyLine {
    val names = (listOf(c.name) + c.aliases).joinToString("|")
    val usage = if (c.args.isEmpty()) names else "$names ${c.args}"
    return CgmReplyLine("${usage.padEnd(14)} ${c.help}${if (c.destructive) " · yes" else ""}")
}

private inline fun <T> CgmFrameResult<T>.orRefuse(done: (T) -> List<CgmReplyLine>): List<CgmReplyLine> = when (this) {
    is CgmFrameResult.Done -> done(value)
    is CgmFrameResult.Refused -> listOf(err(reason))
    CgmFrameResult.NotLinked -> listOf(err("not linked"))
}

/** [head] null for a typed frame; a null [f] is a chunk still waiting for the rest of its frame. */
private fun frameLines(head: String?, f: CgmOpenedFrame?): List<CgmReplyLine> {
    fun line(vararg parts: String) = listOfNotNull(head, *parts).joinToString(" · ")
    return when {
        f == null -> listOf(dim(line("partial")))
        f.sequence == null -> listOf(warn(line("under 3 B")))
        f.plaintext == null -> listOf(warn(line("seq ${f.sequence}", "no tag verifies")))
        else -> listOfNotNull(
            dim(line("seq ${f.sequence}", "kind ${f.kind}")),
            f.plaintext.takeIf { it.isNotEmpty() }?.let { out(hexOf(it)) },
        )
    }
}

/** Hex pairs, spaces allowed as `raw` prints them; null unless whole bytes. */
private fun bytesOf(words: List<String>): ByteArray? {
    val s = words.joinToString("")
    if (s.isEmpty() || s.length % 2 != 0) return null
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        val hi = s[2 * i].digitToIntOrNull(16) ?: return null
        val lo = s[2 * i + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private inline fun count(args: List<String>, default: Int, max: Int, lines: (Int) -> List<CgmReplyLine>): CgmConsoleReply {
    val n = if (args.isEmpty()) default else args.singleOrNull()?.toIntOrNull()?.takeIf { it in 1..max }
    return if (n == null) reply(err("n 1–$max")) else reply(lines(n))
}

private fun roleOf(row: CgmSensorRow) = when {
    row.authoritative -> "main"
    row.active -> "use"
    else -> "idle"
}

private fun info(row: CgmSensorRow, v: CgmConsoleView): List<CgmReplyLine> = buildList {
    add(kv("name", "${row.name} · ${row.ordinalLabel}"))
    add(kv("id", row.id))
    add(kv("role", roleOf(row)))
    addAll(status(row))
    row.sensorAgeMin?.let { add(kv("age", fullDuration(it * 60_000L))) }
    row.expiryMs?.let { at ->
        val left = at - v.nowMs
        add(kv("expires", stampOf(at, v.zone) + if (left >= 0) " · in ${fullDuration(left)}" else " · passed", tone(left < 0)))
    }
    add(kv("warm-up", "${row.warmupWindowMin} min"))
    row.ratedCycleDays?.let { add(kv("rated", "$it d")) }
    add(kv("rssi", row.rssiDbm?.let { "$it dBm" } ?: "—"))
    if (!row.admitted) add(warn("over the ${v.panel.maxSessions}-link budget"))
}

private fun status(row: CgmSensorRow): List<CgmReplyLine> = buildList {
    val flags = listOfNotNull(
        statusWord(row.status),
        "fetching".takeIf { row.fetchingHistory },
        "sensor holds nothing newer".takeIf { row.historyExhausted },
    )
    add(kv("status", flags.joinToString(" · ")))
    row.failureNote?.let { add(kv("failure", it, CgmReplyTone.WARN)) }
}

private fun caps(row: CgmSensorRow): List<CgmReplyLine> = listOf(
    cap("activate", row.hasActivate, row.canActivate),
    cap("fetch", row.hasHistory, row.canFetchHistory),
    cap("bind", row.canBind, row.canBind),
    cap("repair", row.hasRepairHistory, row.canRepairHistory),
    cap("recoverkey", row.hasRecoverKey, row.canRecoverKey),
    cap("provision", row.hasProvision, row.canProvision),
    cap("reconnect", row.active, row.canReconnect),
)

private fun cap(name: String, has: Boolean, now: Boolean): CgmReplyLine = when {
    !has -> CgmReplyLine("${name.padEnd(11)}no", CgmReplyTone.DIM)
    now -> CgmReplyLine("${name.padEnd(11)}yes · now")
    else -> CgmReplyLine("${name.padEnd(11)}yes · not now")
}

private fun telemetry(row: CgmSensorRow, zone: ZoneId): List<CgmReplyLine> {
    val t = row.telemetry ?: return listOf(out("no telemetry"))
    return buildList {
        add(kv("sampled", clockOf(t.sampledAtMs, zone)))
        t.tempCx100?.let { add(kv("temp", "${hundredths(it)} °C")) }
        t.batteryRaw?.let { add(kv("battery", it.toString())) }
        t.iwX100?.let { add(kv("iw", hundredths(it))) }
        t.ibX100?.let { add(kv("ib", hundredths(it))) }
        t.electrodesMv?.takeIf { it.isNotEmpty() }?.let { add(kv("elec", it.joinToString("/") + " mV")) }
        t.errorCode?.let { add(kv("error", it.toString(), tone(it != 0))) }
        t.trendCode?.let { add(kv("trend", it.toString())) }
        t.arrow?.let { add(kv("arrow", it.name.lowercase().replace('_', ' '))) }
    }
}

private fun links(v: CgmConsoleView): List<CgmReplyLine> = buildList {
    val on = v.panel.sensors.count { it.active }
    add(kv("links", "$on on · ${v.panel.maxSessions} max", tone(on > v.panel.maxSessions)))
    v.row?.let { row ->
        add(
            kv(
                "this",
                when {
                    !row.active -> "not on"
                    row.admitted -> "admitted"
                    else -> "over budget"
                },
                tone(!row.admitted),
            ),
        )
    }
    if (v.panel.unidentified > 0) {
        val best = v.panel.unidentifiedRssiDbm?.let { " · best $it dBm" }.orEmpty()
        add(kv("unknown", "${v.panel.unidentified} heard$best"))
    }
    if (v.panel.scanning) add(kv("scan", "running"))
}

private fun raw(n: Int, v: CgmConsoleView): List<CgmReplyLine> {
    val frames = lastOf(v.entries, n) { it.kind == CgmLogKind.RX && it.bytes != null }
    if (frames.isEmpty()) return listOf(out("no raw frames"))
    return frames.flatMap { e ->
        listOf(dim("${clockOf(e.wallMs, v.zone)} ${e.channel.orEmpty()}"), out(hexOf(e.bytes!!)))
    }
}

/** The chunks [raw] lists, each with the lines decoded from it. */
private fun decoded(n: Int, v: CgmConsoleView): List<CgmReplyLine> {
    val chunks = lastOf(v.entries, n) { it.kind == CgmLogKind.RX && it.bytes != null }
    if (chunks.isEmpty()) return listOf(out("no raw frames"))
    val wanted = chunks.mapTo(HashSet()) { it.monoNs }
    val newestFirst = HashMap<Long, MutableList<CgmLogEntry>>()
    // A chunk's lines are logged after it, so the walk stops at the oldest chunk.
    for (i in v.entries.indices.reversed()) {
        val e = v.entries[i]
        if (e === chunks[0]) break
        val rx = e.rxNs ?: continue
        if (e.kind == CgmLogKind.DEC && rx in wanted) newestFirst.getOrPut(rx, ::ArrayList) += e
    }
    return chunks.flatMap { c ->
        val head = "${clockOf(c.wallMs, v.zone)} ${c.channel.orEmpty()}"
        val lines = newestFirst[c.monoNs] ?: return@flatMap listOf(dim("$head · no decode"))
        listOf(dim(head)) + lines.asReversed().flatMap { e ->
            listOfNotNull(out(e.text), e.bytes?.takeIf { it.isNotEmpty() }?.let { dim(hexOf(it)) })
        }
    }
}

/** The newest [n] matches, oldest first. */
private inline fun lastOf(entries: List<CgmLogEntry>, n: Int, match: (CgmLogEntry) -> Boolean): List<CgmLogEntry> {
    val found = ArrayList<CgmLogEntry>(n)
    for (i in entries.indices.reversed()) {
        if (match(entries[i])) found.add(entries[i])
        if (found.size == n) break
    }
    return found.asReversed()
}

private fun tone(bad: Boolean) = if (bad) CgmReplyTone.WARN else CgmReplyTone.OUT

/** Tenths of mg/dL per minute, signed. */
private fun perMin(tenths: Int): String {
    val a = abs(tenths)
    return "${if (tenths < 0) "-" else "+"}${a / 10}.${a % 10}/min"
}
