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
            // Mirror AppDatabase.build: the FTS5 table is Room-invisible, and the OEM SQLite the
            // bundled driver replaces has no fts5 module at all.
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
        assertTrue(repo.searchFoods("rice").any { it.name.contains("rice", ignoreCase = true) })
        assertTrue(
            "FTS trigger did not index the new 'Papaya' row",
            repo.searchFoods("papaya").any { it.name.equals("Papaya", ignoreCase = true) },
        )
        assertTrue(repo.searchFoods("burrito").any { it.name.equals("Burrito", ignoreCase = true) })
        // The repository appends `*`, so this is a prefix search.
        assertTrue(repo.searchFoods("choc").isNotEmpty())
    }
}
