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
import com.t1dm.data.meals.FoodSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented verification of the fresh-install seed path (Phase 7C, meal-builder
 * catalogue). Builds a DB with the SAME wiring production uses — the [BundledSQLiteDriver] (whose
 * SQLite ships `fts5`, unlike the HyperOS/Android-16 system build) plus the [FoodFts] `onCreate`
 * callback — seeds the full [FoodSeed] catalogue, and asserts (a) every row lands in `food` and
 * (b) the external-content FTS5 index is searchable, including a row unique to the Phase-7C growth,
 * which proves the `food_ai` insert trigger repopulated `food_fts`.
 *
 * The re-seed migration [com.t1dm.data.db.MigrationRunner.MIGRATION_5_6] (the upgrade path) is
 * schema-validated in [MigrationTest]; this test covers the count + FTS behaviour on the fresh path.
 */
@RunWith(AndroidJUnit4::class)
class FoodSeedDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository
    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            // Mirror AppDatabase.build: the FTS5 virtual table is Room-invisible, so onCreate must
            // create it, and the bundled driver supplies the fts5 module the OEM SQLite omits.
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

    private suspend fun seed() = repo.seedFoods(
        FoodSeed.ROWS.map { r ->
            FoodEntity(
                name = r.name,
                brand = r.brand,
                carbsPer100g = r.carbsPer100g,
                gi = r.gi,
                category = r.category,
                source = FoodSeed.SOURCE,
                custom = false,
                customCurve = null,
                updatedAt = 0L,
            )
        },
    )

    @Test
    fun seedsEveryCatalogueRow() = runTest {
        seed()
        assertEquals(FoodSeed.ROWS.size, repo.foodCount())
        assertTrue("catalogue too small: ${repo.foodCount()}", repo.foodCount() >= 300)
    }

    @Test
    fun ftsFindsSeededFoods() = runTest {
        seed()
        // A pre-existing Phase-4 staple and a Phase-7C addition both resolve through food_fts.
        assertTrue(repo.searchFoods("rice").any { it.name.contains("rice", ignoreCase = true) })
        assertTrue(
            "FTS trigger did not index the new 'Papaya' row",
            repo.searchFoods("papaya").any { it.name.equals("Papaya", ignoreCase = true) },
        )
        assertTrue(repo.searchFoods("burrito").any { it.name.equals("Burrito", ignoreCase = true) })
        // Prefix search (repository appends `*`): "choc" reaches chocolate items.
        assertTrue(repo.searchFoods("choc").isNotEmpty())
    }
}
