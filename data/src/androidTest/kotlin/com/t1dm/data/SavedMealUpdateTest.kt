package com.t1dm.data

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.SavedMealItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the in-place edit of a saved meal ([T1dmRepository.updateSavedMeal]): the header keeps its
 * id, the portion snapshots are REPLACED, and the whole thing is one transaction.
 *
 * The stakes are set by the schema: `saved_meal_item` has no foreign key and therefore no cascade, so
 * every item row that outlives its header is a permanent orphan reachable by nothing but the full
 * wipe. Two failure modes follow, and both are asserted here — a throw between the delete and the
 * re-insert (which must roll the header write back with it) and an edit aimed at a meal deleted out
 * from under the editor (which must write nothing at all).
 *
 * Instrumented, in-memory, sensor-free. Built with [BundledSQLiteDriver] like production, since the
 * repository's `inWriteTx` reaches for the driver-based `useWriterConnection`/`immediateTransaction`
 * path (see `TransactionTest`).
 */
@RunWith(AndroidJUnit4::class)
class SavedMealUpdateTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    private fun item(name: String, grams: Double, id: Long = 0L) = SavedMealItemEntity(
        id = id,
        mealId = 0L,
        foodId = null,
        name = name,
        grams = grams,
        carbsPer100g = 25.0,
        gi = 60.0,
        customCurve = null,
    )

    private suspend fun totalItemRows(): Long =
        db.useReaderConnection { c ->
            c.usePrepared("SELECT COUNT(*) FROM saved_meal_item") { it.step(); it.getLong(0) }
        }

    /**
     * The contract in one test: the id survives, the name and `updatedAt` move, the old portions are
     * gone and only the new ones remain — and the table holds nothing beyond them, so the replacement
     * left no orphans behind.
     */
    @Test
    fun updateReplacesItemsInPlaceWithoutMintingAnId() = runBlocking {
        val id = repo.saveMeal("breakfast", listOf(item("oats", 60.0), item("milk", 200.0)), t0)

        val ok = repo.updateSavedMeal(
            id = id,
            name = "brunch",
            items = listOf(item("oats", 80.0), item("banana", 120.0), item("yoghurt", 150.0)),
            nowMs = t0 + 60_000L,
        )

        assertTrue("the meal exists, so the update must report a hit", ok)
        val headers = repo.observeSavedMeals().first()
        assertEquals("an edit must not fork a second meal", 1, headers.size)
        assertEquals("the id is the editing identity and must be stable", id, headers[0].id)
        assertEquals("brunch", headers[0].name)
        assertEquals(t0 + 60_000L, headers[0].updatedAt)

        val items = repo.savedMealItems(id)
        assertEquals(setOf("oats", "banana", "yoghurt"), items.map { it.name }.toSet())
        assertEquals(80.0, items.first { it.name == "oats" }.grams, 1e-9)
        assertTrue("every surviving row must belong to the edited meal", items.all { it.mealId == id })
        assertEquals("the replaced portions must be gone, not merely hidden", 3L, totalItemRows())
    }

    /**
     * `observeMeals()` selects from `saved_meal` alone, so an edit that touched only the item rows
     * would never invalidate the Flow and the saved-meals list would sit there stale. The header write
     * is therefore unconditional — proven here by an edit that leaves the name exactly as it was and
     * still moves `updatedAt`.
     */
    @Test
    fun itemOnlyEditStillWritesTheHeader() = runBlocking {
        val id = repo.saveMeal("lunch", listOf(item("rice", 100.0)), t0)

        assertTrue(repo.updateSavedMeal(id, "lunch", listOf(item("rice", 150.0)), t0 + 5_000L))

        val header = repo.observeSavedMeals().first().single()
        assertEquals(t0 + 5_000L, header.updatedAt)
        assertEquals(150.0, repo.savedMealItems(id).single().grams, 1e-9)
    }

    /**
     * The atomicity proof. The re-insert is made to fail (a row carrying the primary key of an item
     * belonging to ANOTHER meal violates the `saved_meal_item` PK), and the whole edit unwinds: the
     * old portions are still there and the header still carries its old name and timestamp. Were the
     * delete not inside the transaction, the meal would survive as a named, empty husk.
     */
    @Test
    fun aFailedReinsertRollsBackTheWholeEdit() = runBlocking {
        val other = repo.saveMeal("other", listOf(item("bread", 50.0)), t0)
        val collidingId = repo.savedMealItems(other).single().id
        val id = repo.saveMeal("dinner", listOf(item("pasta", 90.0), item("sauce", 40.0)), t0)

        val thrown = runCatching {
            repo.updateSavedMeal(
                id = id,
                name = "dinner v2",
                items = listOf(item("pasta", 120.0), item("clash", 10.0, id = collidingId)),
                nowMs = t0 + 60_000L,
            )
        }.exceptionOrNull()
        assertNotNull("the colliding insert must surface, not be swallowed", thrown)

        val header = repo.observeSavedMeals().first().single { it.id == id }
        assertEquals("the header write must unwind with the item replacement", "dinner", header.name)
        assertEquals(t0, header.updatedAt)
        val items = repo.savedMealItems(id)
        assertEquals(setOf("pasta", "sauce"), items.map { it.name }.toSet())
        assertEquals(90.0, items.first { it.name == "pasta" }.grams, 1e-9)
        assertEquals("the other meal is untouched and nothing was stranded", 3L, totalItemRows())
    }

    /**
     * Deleted while it was being edited — the delete affordance sits in the same row as the edit one.
     * The header UPDATE matches nothing, and the items must NOT be written: with no foreign key they
     * would be orphans no query can reach and only a full wipe removes.
     */
    @Test
    fun updateOfADeletedMealWritesNothing() = runBlocking {
        val id = repo.saveMeal("gone", listOf(item("apple", 100.0)), t0)
        repo.deleteSavedMeal(id)

        val ok = repo.updateSavedMeal(id, "resurrected", listOf(item("apple", 200.0)), t0 + 60_000L)

        assertFalse("a vanished header must be reported, not silently ignored", ok)
        assertTrue(repo.observeSavedMeals().first().isEmpty())
        assertEquals("no orphan item rows may be left behind", 0L, totalItemRows())
    }

    /** An edit down to nothing is legal at the store level; it must still leave zero item rows. */
    @Test
    fun updateToAnEmptyItemSetLeavesNoRows() = runBlocking {
        val id = repo.saveMeal("snack", listOf(item("crisps", 30.0)), t0)

        assertTrue(repo.updateSavedMeal(id, "snack", emptyList(), t0 + 1_000L))

        assertEquals(0L, totalItemRows())
        assertEquals(id, repo.observeSavedMeals().first().single().id)
    }
}
