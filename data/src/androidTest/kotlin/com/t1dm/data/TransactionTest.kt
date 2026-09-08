package com.t1dm.data

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.CgmSourceEntity
import com.t1dm.data.db.KvEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [BundledSQLiteDriver] (prod config); proves suspend DAO calls join the tx, not auto-commit. */
@RunWith(AndroidJUnit4::class)
class TransactionTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            // The transaction API under test is only reachable via a configured driver.
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    /** Both rows gone only if both DAOs executed on the same confined writer connection. */
    @Test
    fun daoWritesInsideTransactionRollBackTogether() = runBlocking {
        val src = sourceEntity("aidexx:ROLLBACK")
        val boom = RuntimeException("boom")
        val thrown: RuntimeException? = try {
            db.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    db.cgmSourceDao().upsert(src)
                    db.kvDao().put(KvEntity("k", "v", 1L))
                    // A read DAO inside the writer tx confines too, seeing the uncommitted rows.
                    assertNotNull(db.cgmSourceDao().byId(src.sourceId))
                    assertEquals("v", db.kvDao().get("k"))
                    throw boom
                }
            }
            null
        } catch (e: RuntimeException) {
            e
        }
        assertSame(boom, thrown)
        assertNull("cgm_source write must roll back with the transaction", db.cgmSourceDao().byId(src.sourceId))
        assertNull("kv write must roll back with the transaction", db.kvDao().get("k"))
    }

    /** Rules out a dead DB: without a throw both writes persist. */
    @Test
    fun daoWritesInsideTransactionCommitTogether() = runBlocking {
        val src = sourceEntity("aidexx:COMMIT")
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                db.cgmSourceDao().upsert(src)
                db.kvDao().put(KvEntity("k2", "v2", 1L))
            }
        }
        assertNotNull(db.cgmSourceDao().byId(src.sourceId))
        assertEquals("v2", db.kvDao().get("k2"))
    }

    /** Promotion moves authority alone; the sensor it replaced stays active. */
    @Test
    fun upsertSource_lastAdoptionWins() = runBlocking {
        repo.upsertSource(descriptor("aidexx:A"), authoritative = true, nowMs = 1L)
        repo.upsertSource(descriptor("aidexx:B"), authoritative = true, nowMs = 2L)
        val rows = db.cgmSourceDao().observeAll().first()
        val authoritative = rows.filter { it.authoritative }
        assertEquals(1, authoritative.size)
        assertEquals("aidexx:B", authoritative.single().sourceId)
        assertEquals("both stay active", 2, rows.count { it.active })
    }

    /** upsertSource runs per enumeration; must read authoritative from stored row, not memory. */
    @Test
    fun upsertSource_reSightingPreservesAuthority() = runBlocking {
        repo.upsertSource(descriptor("aidexx:A"), authoritative = true, nowMs = 1L)
        repo.upsertSource(descriptor("aidexx:A"), authoritative = false, nowMs = 2L)
        val rows = db.cgmSourceDao().observeAll().first()
        assertEquals(1, rows.count { it.authoritative })
        assertEquals("aidexx:A", rows.single { it.authoritative }.sourceId)
    }

    @Test
    fun setAuthoritative_activatesAndUnhides() = runBlocking {
        repo.upsertSource(descriptor("aidexx:A"), authoritative = true, nowMs = 1L)
        repo.upsertSource(descriptor("aidexx:B"), authoritative = false, nowMs = 1L)
        repo.hideSource(CgmSourceId("aidexx:B"))
        assertEquals(false, db.cgmSourceDao().observeAll().first().single { it.sourceId == "aidexx:B" }.active)
        repo.setAuthoritativeSource(CgmSourceId("aidexx:B"))
        val b = db.cgmSourceDao().observeAll().first().single { it.sourceId == "aidexx:B" }
        assertEquals(true, b.authoritative)
        assertEquals(true, b.active)
        assertEquals(false, b.hidden)
    }

    /** The guard is in the SQL, so it holds whatever the caller does. */
    @Test
    fun theAuthoritativeSourceCannotBeStoppedOrHidden() = runBlocking {
        repo.upsertSource(descriptor("aidexx:A"), authoritative = true, nowMs = 1L)
        repo.deactivateSource(CgmSourceId("aidexx:A"))
        repo.hideSource(CgmSourceId("aidexx:A"))
        val a = db.cgmSourceDao().observeAll().first().single()
        assertEquals(true, a.authoritative)
        assertEquals(true, a.active)
        assertEquals(false, a.hidden)
    }

    @Test
    fun manyActiveOneAuthoritative() = runBlocking {
        val ids = (0 until 4).map { "aidexx:S$it" }
        ids.forEach { repo.upsertSource(descriptor(it), authoritative = false, nowMs = 1L) }
        repo.setAuthoritativeSource(CgmSourceId(ids[0]))
        ids.drop(1).forEach { repo.activateSource(CgmSourceId(it)) }
        val rows = db.cgmSourceDao().observeAll().first()
        assertEquals(4, rows.count { it.active })
        assertEquals(1, rows.count { it.authoritative })
        assertEquals(ids[0], rows.single { it.authoritative }.sourceId)
    }

    /** Each promotion is one atomic clear-all-then-set; a non-atomic rewrite could leave zero. */
    @Test
    fun concurrentSetAuthoritative_leavesExactlyOne() = runBlocking {
        val ids = (0 until 8).map { "aidexx:S$it" }
        ids.forEach { repo.upsertSource(descriptor(it), authoritative = false, nowMs = 1L) }
        coroutineScope {
            ids.flatMap { id ->
                (0 until 4).map { async(Dispatchers.Default) { repo.setAuthoritativeSource(CgmSourceId(id)) } }
            }.awaitAll()
        }
        val authoritative = db.cgmSourceDao().observeAll().first().filter { it.authoritative }
        assertEquals("exactly one authoritative source after concurrent promotion", 1, authoritative.size)
    }

    /** `hidden` must come from the stored row: the registry's descriptor predates the removal. */
    @Test
    fun upsertSource_preservesHiddenAcrossReSighting() = runBlocking {
        repo.upsertSource(descriptor("aidexx:A"), authoritative = true, nowMs = 1L)
        repo.upsertSource(descriptor("aidexx:B"), authoritative = false, nowMs = 1L)
        repo.hideSource(CgmSourceId("aidexx:B"))

        repo.upsertSource(descriptor("aidexx:B"), authoritative = false, nowMs = 2L)

        val b = db.cgmSourceDao().byId("aidexx:B")!!
        assertEquals("a re-sighting un-hid a removed source", true, b.hidden)
        assertEquals(2L, b.lastSeenMs)
    }

    @Test
    fun theActiveSourceIsNeverHidden() = runBlocking {
        repo.upsertSource(descriptor("aidexx:A"), authoritative = true, nowMs = 1L)
        repo.hideSource(CgmSourceId("aidexx:A"))
        assertEquals("the active source was hidden", false, db.cgmSourceDao().byId("aidexx:A")!!.hidden)

        repo.upsertSource(descriptor("aidexx:B"), authoritative = false, nowMs = 1L)
        repo.hideSource(CgmSourceId("aidexx:B"))
        repo.setAuthoritativeSource(CgmSourceId("aidexx:B"))
        assertEquals("becoming active left the source hidden", false, db.cgmSourceDao().byId("aidexx:B")!!.hidden)
    }

    private fun sourceEntity(id: String) = CgmSourceEntity(
        sourceId = id,
        vendorId = "aidexx",
        sensorModelId = CgmSensorModelId.AIDEX_X, advertName = null,
        displayName = "AiDEX X $id",
        serialSuffix = id.substringAfterLast(':'),
        authoritative = false, active = false,
        warmupWindowMin = 60,
        addedAtMs = 0L,
        lastSeenMs = null,
        hidden = false,
        ordinal = 0,
    )

    private fun descriptor(id: String) = CgmSourceDescriptor(
        id = CgmSourceId(id),
        vendorId = "aidexx",
        sensorModelId = CgmSensorModelId.AIDEX_X, advertName = null,
        displayName = "AiDEX X $id",
        serialSuffix = id.substringAfterLast(':'),
        warmupWindowMin = 60,
        passiveOnly = true,
    )
}
