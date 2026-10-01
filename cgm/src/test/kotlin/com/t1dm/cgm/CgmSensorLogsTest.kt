package com.t1dm.cgm

import com.t1dm.core.model.CgmLogKind
import com.t1dm.core.model.CgmLogLevel
import com.t1dm.core.model.CgmLogTopic
import com.t1dm.core.model.CgmSourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class CgmSensorLogsTest {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val id = CgmSourceId("ct5:2912345678")
    private var clock = 1_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cgmlog").toFile()
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun logs(maxFileBytes: Long = CgmSensorLogs.MAX_FILE_BYTES) =
        CgmSensorLogs(dir, scope, Dispatchers.IO, wallMs = { clock }, monoNs = { clock * 1_000_000 }, maxFileBytes = maxFileBytes)

    @Test
    fun `every field survives the file`() = runBlocking {
        val logs = logs()
        val log = logs.of(id)
        log.rx("0x1001", byteArrayOf(0x35, 1, -2), CgmLogTopic.GLUCOSE, "op 0x35", opens = true, value = -71)
        clock += 5
        log.dec(null, "push #7 bg=120", CgmLogTopic.GLUCOSE, value = 120, bytes = byteArrayOf(9))
        clock += 5
        log.w("T", "counter jumped")

        val all = logs.snapshot(id)
        assertEquals(3, all.size)
        val rx = all[0]
        assertEquals(CgmLogKind.RX, rx.kind)
        assertEquals(CgmLogLevel.I, rx.level)
        assertEquals(CgmLogTopic.GLUCOSE, rx.topic)
        assertEquals("0x1001", rx.channel)
        assertEquals("op 0x35", rx.text)
        assertArrayEquals(byteArrayOf(0x35, 1, -2), rx.bytes)
        assertEquals(-71, rx.value)
        assertTrue(rx.opens)
        assertEquals(1_000L, rx.wallMs)
        assertEquals(1_000_000_000L, rx.monoNs)
        assertEquals(120, all[1].value)
        assertFalse(all[1].opens)
        assertEquals(CgmLogLevel.W, all[2].level)
        assertNull(all[2].channel)
        assertNull(all[2].bytes)
    }

    @Test
    fun `a line decoded from a chunk names it, across a suspension and through the file`() = runBlocking {
        val logs = logs()
        val log = logs.of(id)
        val rx = log.rx("0x1001", byteArrayOf(0x35))
        clock += 5
        decodingChunk(rx) {
            withContext(Dispatchers.Default) { yield() }
            log.dec(null, "push #7")
        }
        log.dec(null, "patchControl ← top-up")

        val all = logs.snapshot(id)
        assertEquals(1_000_000_000L, rx)
        assertNull(all[0].rxNs)
        assertEquals(rx, all[1].rxNs)
        assertNull(all[2].rxNs)
    }

    @Test
    fun `a secret keeps only its length`() = runBlocking {
        val logs = logs()
        logs.of(id).rx("F001", ByteArray(16) { 7 }, secret = true)
        val e = logs.snapshot(id).single()
        assertNull(e.bytes)
        assertEquals("16 B withheld", e.text)
    }

    @Test
    fun `rotation keeps two generations, oldest first`() = runBlocking {
        val logs = logs(maxFileBytes = 400)
        val log = logs.of(id)
        repeat(40) { log.i("T", "line $it") }
        val all = logs.snapshot(id)
        assertTrue(all.size in 2 until 40)
        val numbers = all.map { it.text.removePrefix("line ").toInt() }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(39, numbers.last())
        assertTrue(File(dir, CgmSensorLogs.fileName(id) + CgmSensorLogs.OLDER_SUFFIX).isFile)
    }

    @Test
    fun `a torn tail loses only the torn record`() = runBlocking {
        val first = logs()
        first.of(id).i("T", "one")
        first.of(id).i("T", "two")
        first.snapshot(id)
        val file = File(dir, CgmSensorLogs.fileName(id))
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 3) }
        assertEquals(listOf("one"), logs().snapshot(id).map { it.text })
    }

    @Test
    fun `follow hands the file, then each new line once`() = runBlocking {
        val logs = logs()
        val log = logs.of(id)
        log.i("T", "before")
        var latest: List<String> = emptyList()
        val watching = async {
            withTimeoutOrNull(1_500) { logs.follow(id).collect { list -> latest = list.map { it.text } } }
        }
        // Before, during or after the snapshot: whichever, it must show exactly once.
        log.i("T", "after")
        watching.await()
        assertEquals(listOf("before", "after"), latest)
    }

    @Test
    fun `adverts are kept only for listed sensors`() {
        val logs = logs()
        assertNull(logs.advertLog(id))
        logs.setListed(setOf(id))
        assertTrue(logs.advertLog(id) != null)
    }

    @Test
    fun `clearAll deletes every file`() = runBlocking {
        val logs = logs()
        logs.of(id).i("T", "x")
        logs.snapshot(id)
        logs.clearAll()
        assertEquals(0, dir.listFiles()!!.size)
        assertTrue(logs.snapshot(id).isEmpty())
    }

    @Test
    fun `advert bytes stop at the first empty structure`() {
        val padded = byteArrayOf(2, 1, 6, 3, -1, 1, 2) + ByteArray(55)
        assertArrayEquals(byteArrayOf(2, 1, 6, 3, -1, 1, 2), advertBytes(padded))
        assertNull(advertBytes(null))
    }
}
