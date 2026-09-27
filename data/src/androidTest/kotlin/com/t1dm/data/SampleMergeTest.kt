package com.t1dm.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.SampleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = T1dmRepository(db, DefaultT1dmDispatchers(io = Dispatchers.Default))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun recordSteps_keepsTheRowsOtherSeries() = runBlocking {
        val ts = 1_700_000_100_000L
        db.sampleDao().upsert(
            SampleEntity(
                ts = ts,
                tzOffsetMin = 60,
                bgMgdl = 123,
                bgSource = "src",
                bgProvenance = ReadingProvenance.MEASURED,
                bgFlag = ReadingFlag.NORMAL,
                steps = null,
                mood = 4,
                hr = null,
                sleep = null,
                exercise = 7.5,
                updatedAt = 1L,
            ),
        )

        repo.recordSteps(ts, tzOffsetMin = 0, steps = 250, nowMs = 2L)

        val row = requireNotNull(db.sampleDao().byTs(ts))
        assertEquals(250, row.steps)
        assertEquals(123, row.bgMgdl)
        assertEquals("src", row.bgSource)
        assertEquals(4, row.mood)
        assertEquals(7.5, requireNotNull(row.exercise), 0.0)
        assertEquals("tz_offset is the row's, not the step writer's", 60, row.tzOffsetMin)
    }
}
