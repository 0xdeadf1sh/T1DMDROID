package com.t1dm.cgm

import android.os.SystemClock
import android.util.Log
import com.t1dm.core.model.CgmLogEntry
import com.t1dm.core.model.CgmLogKind
import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import com.t1dm.core.model.CgmSourceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** One sensor's log. Every line but raw traffic also goes to logcat under the caller's tag. */
class CgmSensorLog internal constructor(
    private val id: CgmSourceId?,
    private val logs: CgmSensorLogs?,
) {
    /** The last write's topic; the stack's ack and the peer's reply are filed beside it. */
    @Volatile
    var lastTxTopic: CgmLogTopic = CgmLogTopic.NONE
        private set

    fun d(tag: String, text: String, topic: CgmLogTopic = CgmLogTopic.NONE, opens: Boolean = false) {
        Log.d(tag, text)
        put(CgmLogKind.LOG, CgmLogLevel.D, topic, null, text, null, null, opens)
    }

    fun i(tag: String, text: String, topic: CgmLogTopic = CgmLogTopic.NONE, opens: Boolean = false) {
        Log.i(tag, text)
        put(CgmLogKind.LOG, CgmLogLevel.I, topic, null, text, null, null, opens)
    }

    fun w(tag: String, text: String, topic: CgmLogTopic = CgmLogTopic.NONE) {
        Log.w(tag, text)
        put(CgmLogKind.LOG, CgmLogLevel.W, topic, null, text, null, null, false)
    }

    fun e(tag: String, text: String, error: Throwable? = null) {
        Log.e(tag, text, error)
        val line = if (error == null) text else "$text: ${error.javaClass.simpleName}"
        put(CgmLogKind.LOG, CgmLogLevel.E, CgmLogTopic.NONE, null, line, null, null, false)
    }

    /** A link-level operation or callback; [tag] null keeps a routine one out of logcat. */
    fun gatt(
        tag: String?,
        text: String,
        level: CgmLogLevel = CgmLogLevel.I,
        topic: CgmLogTopic = CgmLogTopic.NONE,
        value: Int? = null,
        opens: Boolean = false,
    ) {
        if (tag != null) logcat(tag, level, text)
        put(CgmLogKind.GATT, level, topic, null, text, null, value, opens)
    }

    /** One advertisement as heard; [raw] null when the stack gave no scan record. */
    fun advert(raw: ByteArray?, rssi: Int, text: String) {
        if (raw != null) {
            rx("adv", raw, CgmLogTopic.ADVERT, text, opens = true, value = rssi)
        } else {
            gatt(null, text, topic = CgmLogTopic.ADVERT, value = rssi, opens = true)
        }
    }

    /** The stack's completion of the last write; logcat only when it failed. */
    fun ack(tag: String, text: String, ok: Boolean) =
        gatt(if (ok) null else tag, text, if (ok) CgmLogLevel.D else CgmLogLevel.W, lastTxTopic)

    /** [secret]: key material; only its length is kept. Returns the line's monoNs; null: unkept. */
    fun rx(
        channel: String,
        bytes: ByteArray,
        topic: CgmLogTopic = CgmLogTopic.NONE,
        text: String = "",
        opens: Boolean = false,
        secret: Boolean = false,
        value: Int? = null,
    ): Long? = traffic(CgmLogKind.RX, channel, bytes, topic, text, opens, secret, value)

    fun tx(
        channel: String,
        bytes: ByteArray,
        topic: CgmLogTopic = CgmLogTopic.NONE,
        text: String = "",
        opens: Boolean = false,
        secret: Boolean = false,
    ) {
        lastTxTopic = topic
        traffic(CgmLogKind.TX, channel, bytes, topic, text, opens, secret, null)
    }

    /** A frame's decoded form; [value] is mg/dL on a GLUCOSE line, [bytes] its plaintext. */
    fun dec(
        tag: String?,
        text: String,
        topic: CgmLogTopic = CgmLogTopic.NONE,
        value: Int? = null,
        level: CgmLogLevel = CgmLogLevel.I,
        bytes: ByteArray? = null,
        opens: Boolean = false,
    ) {
        if (tag != null) logcat(tag, level, text)
        if (logs == null) return
        put(CgmLogKind.DEC, level, topic, null, text, bytes?.copyOf(), value, opens, decodingRx.get())
    }

    private fun traffic(
        kind: CgmLogKind,
        channel: String,
        bytes: ByteArray,
        topic: CgmLogTopic,
        text: String,
        opens: Boolean,
        secret: Boolean,
        value: Int?,
    ): Long? {
        if (logs == null) return null
        val line = when {
            !secret -> text
            text.isEmpty() -> "${bytes.size} B withheld"
            else -> "$text · ${bytes.size} B withheld"
        }
        return put(kind, CgmLogLevel.I, topic, channel, line, if (secret) null else bytes.copyOf(), value, opens)
    }

    private fun logcat(tag: String, level: CgmLogLevel, text: String) {
        when (level) {
            CgmLogLevel.D -> Log.d(tag, text)
            CgmLogLevel.I -> Log.i(tag, text)
            CgmLogLevel.W -> Log.w(tag, text)
            CgmLogLevel.E -> Log.e(tag, text)
        }
    }

    private fun put(
        kind: CgmLogKind,
        level: CgmLogLevel,
        topic: CgmLogTopic,
        channel: String?,
        text: String,
        bytes: ByteArray?,
        value: Int?,
        opens: Boolean,
        rxNs: Long? = null,
    ): Long? {
        val sink = logs ?: return null
        val sensor = id ?: return null
        val monoNs = sink.monoNs()
        sink.append(
            sensor,
            CgmLogEntry(
                wallMs = sink.wallMs(),
                monoNs = monoNs,
                kind = kind,
                level = level,
                topic = topic,
                channel = channel,
                text = text,
                bytes = bytes,
                value = value,
                opens = opens,
                rxNs = rxNs,
            ),
        )
        return monoNs
    }

    companion object {
        /** Logcat only; nothing is kept. */
        val NONE = CgmSensorLog(null, null)
    }
}

private val decodingRx = ThreadLocal<Long?>()

/** DEC lines logged in [block] link to the received chunk whose line has monoNs [rxNs]. */
internal suspend fun <T> decodingChunk(rxNs: Long?, block: suspend CoroutineScope.() -> T): T =
    withContext(decodingRx.asContextElement(rxNs), block)

internal fun CgmSensorLogs?.forSensor(id: CgmSourceId): CgmSensorLog = this?.of(id) ?: CgmSensorLog.NONE

/** A legacy scan record is zero-padded to 62 B; cut at the first zero-length AD structure. */
internal fun advertBytes(record: ByteArray?): ByteArray? {
    if (record == null) return null
    var i = 0
    while (i < record.size) {
        val len = record[i].toInt() and 0xFF
        if (len == 0) break
        i += len + 1
    }
    return record.copyOf(minOf(i, record.size))
}

/** Two generations of [maxFileBytes] per sensor, one writer coroutine; [dir] null keeps nothing. */
class CgmSensorLogs(
    private val dir: File?,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    internal val wallMs: () -> Long = System::currentTimeMillis,
    internal val monoNs: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
) {
    private val handles = ConcurrentHashMap<CgmSourceId, CgmSensorLog>()

    /** Sensors the panel lists; written from the registry's pass, read from scan threads. */
    @Volatile
    private var listed: Set<CgmSourceId> = emptySet()

    private sealed interface Op {
        class Append(val id: CgmSourceId, val entry: CgmLogEntry) : Op
        class Snapshot(val id: CgmSourceId, val reply: CompletableDeferred<Taken>) : Op
        class Clear(val reply: CompletableDeferred<Unit>) : Op
    }

    private class Taken(val entries: List<CgmLogEntry>, val lastSeq: Long)

    private sealed interface Event
    private class Line(val id: CgmSourceId, val seq: Long, val entry: CgmLogEntry) : Event
    private class Loaded(val taken: Taken) : Event

    private val ops = Channel<Op>(Channel.UNLIMITED)

    /** A slow viewer loses its oldest lines, never the writer's time. */
    private val lines = MutableSharedFlow<Event>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private class Out(val file: File, val stream: DataOutputStream, var size: Long)

    /** Writer coroutine only. */
    private val outs = HashMap<CgmSourceId, Out>()
    private var seq = 0L
    private val scratch = ByteArrayOutputStream(256)
    private val scratchOut = DataOutputStream(scratch)

    init {
        scope.launch(io) { writer() }
    }

    fun of(id: CgmSourceId): CgmSensorLog = handles.computeIfAbsent(id) { CgmSensorLog(it, this) }

    /** Adverts are logged for these only: a stranger's sensor must leave no file. */
    fun setListed(ids: Set<CgmSourceId>) {
        listed = ids
    }

    /** Null for a sensor the panel does not list. */
    fun advertLog(id: CgmSourceId): CgmSensorLog? = if (id in listed) of(id) else null

    internal fun append(id: CgmSourceId, entry: CgmLogEntry) {
        ops.trySend(Op.Append(id, entry))
    }

    /** Everything kept for [id], oldest first, as of now. */
    suspend fun snapshot(id: CgmSourceId): List<CgmLogEntry> = take(id).entries

    private suspend fun take(id: CgmSourceId): Taken {
        val reply = CompletableDeferred<Taken>()
        ops.send(Op.Snapshot(id, reply))
        return reply.await()
    }

    /** The whole log, then the whole log again on every append; lists are never mutated. */
    fun follow(id: CgmSourceId): Flow<List<CgmLogEntry>> = flow {
        var cutoff = Long.MAX_VALUE
        var arr: Array<CgmLogEntry?> = emptyArray()
        var size = 0
        lines.onSubscription { emit(Loaded(take(id))) }.collect { ev ->
            when (ev) {
                is Loaded -> {
                    cutoff = ev.taken.lastSeq
                    val all = ev.taken.entries
                    arr = arrayOfNulls(maxOf(all.size * 2, MIN_FOLLOW_CAPACITY))
                    all.forEachIndexed { i, e -> arr[i] = e }
                    size = all.size
                    emit(Frozen(arr, size))
                }
                is Line -> if (ev.id == id && ev.seq > cutoff) {
                    if (size == arr.size) arr = arr.copyOf(arr.size * 2)
                    // Past every published size: a list already handed out never reads it.
                    arr[size++] = ev.entry
                    emit(Frozen(arr, size))
                }
            }
        }
    }

    private class Frozen(private val arr: Array<CgmLogEntry?>, override val size: Int) : AbstractList<CgmLogEntry>() {
        override fun get(index: Int): CgmLogEntry {
            if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
            return arr[index]!!
        }
    }

    /** Deletes every sensor's log. */
    suspend fun clearAll() {
        val reply = CompletableDeferred<Unit>()
        ops.send(Op.Clear(reply))
        reply.await()
    }

    private suspend fun writer() {
        prune()
        while (true) {
            val first = ops.receiveCatching().getOrNull() ?: return
            handle(first)
            while (true) handle(ops.tryReceive().getOrNull() ?: break)
            flushAll()
        }
    }

    private fun handle(op: Op) {
        when (op) {
            is Op.Append -> {
                val s = ++seq
                write(op.id, op.entry)
                lines.tryEmit(Line(op.id, s, op.entry))
            }
            is Op.Snapshot -> {
                flushAll()
                op.reply.complete(Taken(readAll(op.id), seq))
            }
            is Op.Clear -> {
                closeAll()
                dir?.listFiles()?.forEach { it.delete() }
                op.reply.complete(Unit)
            }
        }
    }

    private fun write(id: CgmSourceId, e: CgmLogEntry) {
        if (dir == null) return
        scratch.reset()
        encode(scratchOut, e)
        val len = scratch.size()
        try {
            var out = outs[id] ?: open(id) ?: return
            if (out.size + RECORD_HEADER + len > maxFileBytes) {
                rotate(id, out)
                out = open(id) ?: return
            }
            out.stream.writeInt(len)
            scratch.writeTo(out.stream)
            out.size += RECORD_HEADER + len
        } catch (x: IOException) {
            Log.w(TAG, "log write for ${id.value} failed: ${x.javaClass.simpleName}")
            outs.remove(id)?.let { runCatching { it.stream.close() } }
        }
    }

    private fun open(id: CgmSourceId): Out? {
        val d = dir ?: return null
        return try {
            d.mkdirs()
            val file = File(d, fileName(id))
            val fresh = !file.isFile || file.length() == 0L
            val stream = DataOutputStream(BufferedOutputStream(FileOutputStream(file, true), BUFFER_BYTES))
            if (fresh) {
                stream.write(MAGIC)
                stream.writeByte(VERSION)
            }
            Out(file, stream, file.length() + if (fresh) MAGIC.size + 1L else 0L).also { outs[id] = it }
        } catch (x: IOException) {
            Log.w(TAG, "log open for ${id.value} failed: ${x.javaClass.simpleName}")
            null
        }
    }

    private fun rotate(id: CgmSourceId, out: Out) {
        runCatching { out.stream.close() }
        outs.remove(id)
        val older = File(out.file.path + OLDER_SUFFIX)
        older.delete()
        out.file.renameTo(older)
    }

    private fun flushAll() {
        for ((id, out) in outs.entries.toList()) {
            try {
                out.stream.flush()
            } catch (x: IOException) {
                Log.w(TAG, "log flush for ${id.value} failed: ${x.javaClass.simpleName}")
                runCatching { out.stream.close() }
                outs.remove(id)
            }
        }
    }

    private fun closeAll() {
        outs.values.forEach { runCatching { it.stream.close() } }
        outs.clear()
    }

    private fun prune() {
        val cutoff = wallMs() - RETAIN_MS
        dir?.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun readAll(id: CgmSourceId): List<CgmLogEntry> {
        val d = dir ?: return emptyList()
        val out = ArrayList<CgmLogEntry>()
        val current = File(d, fileName(id))
        readFile(File(current.path + OLDER_SUFFIX), out)
        readFile(current, out)
        return out
    }

    /** Stops at the first damaged record: a kill mid-write leaves at most a torn tail. */
    private fun readFile(file: File, out: MutableList<CgmLogEntry>) {
        if (!file.isFile) return
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER_BYTES)).use { din ->
                val magic = ByteArray(MAGIC.size)
                din.readFully(magic)
                if (!magic.contentEquals(MAGIC) || din.readUnsignedByte() != VERSION) return
                var buf = ByteArray(1024)
                while (true) {
                    val len = try {
                        din.readInt()
                    } catch (_: EOFException) {
                        return
                    }
                    if (len !in 1..MAX_RECORD_BYTES) return
                    if (len > buf.size) buf = ByteArray(maxOf(len, buf.size * 2))
                    din.readFully(buf, 0, len)
                    out += decode(buf, len) ?: return
                }
            }
        } catch (_: IOException) {
            // What was read before the damage stands.
        }
    }

    internal companion object {
        const val TAG = "CgmLog"

        /** Two generations per sensor: about a week of a 3-minute sensor with 15 s RSSI polls. */
        const val MAX_FILE_BYTES = 4L * 1024 * 1024

        const val RETAIN_MS = 30L * 24 * 60 * 60 * 1000

        const val OLDER_SUFFIX = ".1"

        private val MAGIC = byteArrayOf('T'.code.toByte(), '1'.code.toByte(), 'C'.code.toByte(), 'L'.code.toByte())
        private const val VERSION = 1
        private const val RECORD_HEADER = 4
        private const val MAX_RECORD_BYTES = 1 shl 20
        private const val BUFFER_BYTES = 16 * 1024
        private const val MIN_FOLLOW_CAPACITY = 256

        /** UTF-8 of this many chars stays under the u16 length prefix. */
        private const val MAX_TEXT_CHARS = 8_000

        private const val F_OPENS = 1
        private const val F_BYTES = 2
        private const val F_VALUE = 4
        private const val F_CHANNEL = 8
        private const val F_RX = 16

        private val KINDS = CgmLogKind.entries
        private val LEVELS = CgmLogLevel.entries
        private val TOPICS = CgmLogTopic.entries

        fun fileName(id: CgmSourceId): String =
            id.value.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }.joinToString("") + ".log"

        fun encode(out: DataOutputStream, e: CgmLogEntry) {
            val flags = (if (e.opens) F_OPENS else 0) or
                (if (e.bytes != null) F_BYTES else 0) or
                (if (e.value != null) F_VALUE else 0) or
                (if (e.channel != null) F_CHANNEL else 0) or
                (if (e.rxNs != null) F_RX else 0)
            out.writeByte(e.kind.ordinal)
            out.writeByte(e.level.ordinal)
            out.writeByte(e.topic.ordinal)
            out.writeByte(flags)
            out.writeLong(e.wallMs)
            out.writeLong(e.monoNs)
            e.channel?.let { writeString(out, it) }
            writeString(out, e.text)
            e.bytes?.let {
                out.writeInt(it.size)
                out.write(it)
            }
            e.value?.let { out.writeInt(it) }
            // Last: a build that predates it stops reading before it.
            e.rxNs?.let { out.writeLong(it) }
        }

        private fun writeString(out: DataOutputStream, s: String) {
            val b = (if (s.length > MAX_TEXT_CHARS) s.substring(0, MAX_TEXT_CHARS) else s).encodeToByteArray()
            out.writeShort(b.size)
            out.write(b)
        }

        /** Null on anything malformed, including an enum ordinal a newer build wrote. */
        fun decode(buf: ByteArray, len: Int): CgmLogEntry? = try {
            decodeOrNull(ByteBuffer.wrap(buf, 0, len), buf)
        } catch (_: RuntimeException) {
            null
        }

        private fun decodeOrNull(bb: ByteBuffer, buf: ByteArray): CgmLogEntry? {
            val kind = KINDS.getOrNull(bb.get().toInt())
            val level = LEVELS.getOrNull(bb.get().toInt())
            val topic = TOPICS.getOrNull(bb.get().toInt())
            val flags = bb.get().toInt()
            val wallMs = bb.long
            val monoNs = bb.long
            val channel = if (flags and F_CHANNEL != 0) readString(bb, buf) else null
            val text = readString(bb, buf)
            val bytes = if (flags and F_BYTES != 0) {
                val n = bb.int
                // A damaged length must not become a gigabyte allocation.
                if (n !in 0..bb.remaining()) return null
                ByteArray(n).also { bb.get(it) }
            } else {
                null
            }
            val value = if (flags and F_VALUE != 0) bb.int else null
            val rxNs = if (flags and F_RX != 0) bb.long else null
            if (kind == null || level == null || topic == null) return null
            return CgmLogEntry(wallMs, monoNs, kind, level, topic, channel, text, bytes, value, flags and F_OPENS != 0, rxNs)
        }

        private fun readString(bb: ByteBuffer, buf: ByteArray): String {
            val n = bb.short.toInt() and 0xFFFF
            val start = bb.position()
            bb.position(start + n)
            return buf.decodeToString(start, start + n)
        }
    }
}
