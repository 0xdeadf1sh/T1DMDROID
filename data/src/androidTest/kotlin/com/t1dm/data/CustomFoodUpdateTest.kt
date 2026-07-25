package com.t1dm.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.FoodEntity
import com.t1dm.data.db.FoodFts
import com.t1dm.data.db.SavedMealItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the in-place edit of a custom food ([T1dmRepository.updateCustomFood]): the id survives, the
 * FTS5 index follows the new name by itself, and the two ways an ungated `@Upsert` would do damage are
 * both refused.
 *
 * The stakes come from the asymmetry in [com.t1dm.data.db.FoodDao]: `deleteCustom` is gated
 * `AND custom = 1` but `upsert` is gated on nothing, and `Food.toCustomEntity` hard-sets
 * `custom = true` / `source = "user"`. So an unguarded write would (a) convert a shipped dictionary
 * row into a user food and (b) resurrect a deleted row at its old id, since Room's upsert inserts with
 * the explicit primary key when nothing conflicts. Both are asserted here, as is the invariant that
 * makes the whole edit safe: a saved meal's portions are SNAPSHOTS and must not move with the food.
 *
 * Built with the same wiring production uses — [BundledSQLiteDriver] (whose SQLite ships `fts5`, unlike
 * the HyperOS/Android-16 system build) plus the [FoodFts] `onCreate` callback, which is what puts the
 * `food_au` AFTER UPDATE trigger in place. Instrumented, in-memory, sensor-free.
 */
@RunWith(AndroidJUnit4::class)
class CustomFoodUpdateTest {

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
            // Mirror AppDatabase.build: the FTS5 virtual table and its three sync triggers are
            // Room-invisible, so onCreate must create them.
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(connection: SQLiteConnection) = FoodFts.create(connection)
            })
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    private fun food(
        name: String,
        carbs: Double = 60.0,
        gi: Double? = 55.0,
        custom: Boolean = true,
    ) = FoodEntity(
        name = name,
        brand = null,
        carbsPer100g = carbs,
        gi = gi,
        category = if (custom) "Custom" else "Grains",
        source = if (custom) "user" else "USDA FDC",
        custom = custom,
        customCurve = null,
        updatedAt = t0,
    )

    /** Insert a row and hand back the id the autoincrement minted for it. */
    private suspend fun insert(row: FoodEntity): Long {
        repo.upsertFood(row)
        return repo.searchFoods(row.name).first { it.name == row.name }.id
    }

    /**
     * The contract in one test: the edit lands under the SAME id (never a fork), and `food_fts` follows
     * without a hand-rolled reindex — the row answers to its new name and no longer to its old one,
     * which only the `food_au` delete-and-reinsert pair produces.
     */
    @Test
    fun updateKeepsTheIdAndTheFtsIndexFollowsTheNewName() = runBlocking {
        val id = insert(food("oatcake", carbs = 62.0))

        val ok = repo.updateCustomFood(
            food("ryecrisp", carbs = 71.5, gi = 64.0).copy(id = id, updatedAt = t0 + 60_000L),
        )

        assertTrue("an existing user food must accept the edit", ok)
        val stored = repo.observeCustomFoods().first().single()
        assertEquals("the id is the food's identity and must be stable", id, stored.id)
        assertEquals("ryecrisp", stored.name)
        assertEquals(71.5, stored.carbsPer100g, 1e-9)
        assertEquals(64.0, stored.gi!!, 1e-9)
        assertEquals(t0 + 60_000L, stored.updatedAt)

        assertEquals(listOf(id), repo.searchFoods("ryecrisp").map { it.id })
        assertTrue("the old name must leave the index with the row", repo.searchFoods("oatcake").isEmpty())
    }

    /**
     * `upsert` is not gated on `custom` the way `deleteCustom` is, and the custom-food mapper hard-sets
     * `custom = true`, so an unguarded edit aimed at a seed id would quietly turn a shipped dictionary
     * row into a user food — deletable, and listed under "Your foods".
     */
    @Test
    fun aSeedRowIsRefusedAndNeverBecomesAUserFood() = runBlocking {
        repo.seedFoods(listOf(food("basmati rice", carbs = 28.0, custom = false)))
        val id = repo.searchFoods("basmati").single().id

        val ok = repo.updateCustomFood(
            food("hijacked", carbs = 1.0, custom = true).copy(id = id),
        )

        assertFalse("a bundled row is read-only", ok)
        val row = repo.foodById(id)!!
        assertEquals("basmati rice", row.name)
        assertEquals(28.0, row.carbsPer100g, 1e-9)
        assertFalse("the seed row must keep its provenance", row.custom)
        assertTrue(repo.observeCustomFoods().first().isEmpty())
    }

    /**
     * Room's `@Upsert` INSERTs when nothing conflicts — with the explicit primary key. An edit carrying
     * the id of a row deleted out from under it would therefore re-create it rather than fail.
     */
    @Test
    fun aDeletedFoodIsNotResurrectedByAStaleEdit() = runBlocking {
        val id = insert(food("sourdough"))
        repo.deleteCustomFood(id)

        val ok = repo.updateCustomFood(food("sourdough v2").copy(id = id))

        assertFalse("a vanished row must be reported, not silently re-created", ok)
        assertNull(repo.foodById(id))
        assertTrue(repo.observeCustomFoods().first().isEmpty())
        assertTrue("the FTS row went with it", repo.searchFoods("sourdough").isEmpty())
    }

    /**
     * The invariant the whole feature rests on: `saved_meal_item` denormalizes carbs/GI/curve at add
     * time and carries no foreign key back to `food`, so editing the food must leave every stored meal
     * exactly as it was. Anyone "fixing" this by re-resolving components from `foodId` breaks it.
     */
    @Test
    fun editingAFoodLeavesSavedMealSnapshotsAlone() = runBlocking {
        val id = insert(food("polenta", carbs = 70.0, gi = 68.0))
        val mealId = repo.saveMeal(
            name = "supper",
            items = listOf(
                SavedMealItemEntity(
                    mealId = 0L,
                    foodId = id,
                    name = "polenta",
                    grams = 150.0,
                    carbsPer100g = 70.0,
                    gi = 68.0,
                    customCurve = null,
                ),
            ),
            nowMs = t0,
        )

        assertTrue(repo.updateCustomFood(food("polenta", carbs = 12.0, gi = 30.0).copy(id = id)))

        val item = repo.savedMealItems(mealId).single()
        assertEquals("the portion snapshot must not move with the food", 70.0, item.carbsPer100g, 1e-9)
        assertEquals(68.0, item.gi!!, 1e-9)
        assertEquals(id, item.foodId)
    }
}
