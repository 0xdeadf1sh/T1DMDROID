package com.t1dm.data

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
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
     * upsertSource's clear-all-then-set is atomic: two active upserts leave exactly one active row —
     * the later one — never two and never zero.
     */
    @Test
    fun upsertSource_lastActiveWins() = runBlocking {
        repo.upsertSource(descriptor("aidexx:A"), active = true, nowMs = 1L)
        repo.upsertSource(descriptor("aidexx:B"), active = true, nowMs = 2L)
        val active = db.cgmSourceDao().observeAll().first().filter { it.active }
        assertEquals(1, active.size)
        assertEquals("aidexx:B", active.single().sourceId)
    }

    /**
     * Concurrency/ordering: many overlapping setActiveSource calls serialize on the single writer
     * connection, and because each is one atomic clear-all-then-set, the terminal state has EXACTLY
     * one active source. A non-atomic rewrite (clear and set on separate transactions) could momentarily
     * — or, if interleaved, terminally — leave zero active.
     */
    @Test
    fun concurrentSetActive_leavesExactlyOneActive() = runBlocking {
        val ids = (0 until 8).map { "aidexx:S$it" }
        ids.forEach { repo.upsertSource(descriptor(it), active = false, nowMs = 1L) }
        coroutineScope {
            ids.flatMap { id ->
                (0 until 4).map { async(Dispatchers.Default) { repo.setActiveSource(CgmSourceId(id)) } }
            }.awaitAll()
        }
        val active = db.cgmSourceDao().observeAll().first().filter { it.active }
        assertEquals("exactly one active source after concurrent activation", 1, active.size)
    }

    private fun sourceEntity(id: String) = CgmSourceEntity(
        sourceId = id,
        vendorId = "aidexx",
        displayName = "AiDEX X $id",
        serialSuffix = id.substringAfterLast(':'),
        active = false,
        warmupWindowMin = 60,
        addedAtMs = 0L,
        lastSeenMs = null,
    )

    private fun descriptor(id: String) = CgmSourceDescriptor(
        id = CgmSourceId(id),
        vendorId = "aidexx",
        displayName = "AiDEX X $id",
        serialSuffix = id.substringAfterLast(':'),
        warmupWindowMin = 60,
        passiveOnly = true,
    )
}
