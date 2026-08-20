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

/**
 * Proves the Room 2.7 **driver-based** write transaction the repository now relies on ([T1dmRepository]
 * `inWriteTx` = `useWriterConnection { immediateTransaction { … } }`) is genuinely atomic across DAOs,
 * and that the exactly-one-active-source invariant survives last-writer and concurrent ordering.
 *
 * The DB is built with [BundledSQLiteDriver] — the production configuration — so these run against the
 * same `ConnectionPoolImpl` / connection-confinement machinery as the app (not the legacy support
 * pool). The pivotal question the migration hinged on: do suspend DAO calls made INSIDE the
 * transaction join it (share the confined writer connection) or run on their own auto-committed
 * connection? [daoWritesInsideTransactionRollBackTogether] answers it by rolling back.
 */
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
            // Match production: ship our own SQLite. The transaction API under test
            // (useWriterConnection/immediateTransaction) is only reachable via a configured driver.
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    /**
     * TWO writes through TWO different DAOs inside one `immediateTransaction`, then a throw. Both rows
     * are gone afterward — which can only happen if the DAO calls executed on the SAME confined writer
     * connection and were undone by that transaction's rollback. Had they run on separate
     * auto-committed connections, the rows would persist. This is the atomicity proof the
     * invariant-critical bodies (setActiveSource / upsertSource / mergeSampleInTx) depend on.
     */
    @Test
    fun daoWritesInsideTransactionRollBackTogether() = runBlocking {
        val src = sourceEntity("aidexx:ROLLBACK")
        val boom = RuntimeException("boom")
        val thrown: RuntimeException? = try {
            db.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    db.cgmSourceDao().upsert(src)              // write #1 → cgm_source
                    db.kvDao().put(KvEntity("k", "v", 1L))     // write #2 → kv (different table + DAO)
                    // A READ DAO inside the writer tx must also confine and see the uncommitted rows.
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

    /** The committing counterpart: without a throw both DAO writes persist (rules out a dead DB). */
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

    /**
     * upsertSource's clear-all-then-set is atomic: two adopting upserts leave exactly one authoritative
     * row — the later one — never two and never zero. Both stay ACTIVE: promotion moves what is
     * believed, and never stops the app reading the sensor it replaced.
     */
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

    /**
     * A re-sighting NEVER clears the authoritative flag. `upsertSource` runs on every enumeration, from
     * a descriptor the coordinator has held in memory, and it used to write the flag it was handed —
     * so a pass that raced a promotion could leave the table with no authoritative row at all and
     * silently stop the model, the alarms and the wire.
     */
    @Test
    fun upsertSource_reSightingPreservesAuthority() = runBlocking {
        repo.upsertSource(descriptor("aidexx:A"), authoritative = true, nowMs = 1L)
        repo.upsertSource(descriptor("aidexx:A"), authoritative = false, nowMs = 2L)
        val rows = db.cgmSourceDao().observeAll().first()
        assertEquals(1, rows.count { it.authoritative })
        assertEquals("aidexx:A", rows.single { it.authoritative }.sourceId)
    }

    /** Authority implies activity: promoting a source the app had stopped reading starts reading it. */
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

    /** Deactivating and hiding both REFUSE the authoritative source — the guard is in the SQL, so it
     *  holds whatever the caller does. */
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

    /** Several sources may be active at once; exactly one of them is authoritative. */
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

    /**
     * Concurrency/ordering: many overlapping setAuthoritativeSource calls serialize on the single writer
     * connection, and because each is one atomic clear-all-then-set, the terminal state has EXACTLY
     * one authoritative source. A non-atomic rewrite (clear and set on separate transactions) could
     * momentarily — or, if interleaved, terminally — leave zero.
     */
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

    /**
     * A removal survives the sensor being seen again. `upsertSource` runs on every sighting from a
     * descriptor the registry has held since before the removal, so it must take `hidden` from the
     * STORED row — taking it from the argument would put a removed sensor back on the list within one
     * scan, and nothing else in the suite would notice.
     */
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

    /**
     * The active source is never hidden, from either direction: [T1dmRepository.hideSource] refuses it
     * outright, and adopting a hidden source as active clears the flag. A sensor authoritative for
     * every value on screen must not be missing from the list that names it.
     */
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
