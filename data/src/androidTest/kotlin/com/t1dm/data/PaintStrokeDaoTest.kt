package com.t1dm.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.model.PaintStroke
import com.t1dm.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room verification of the `bg_paint_stroke` viewport cull (Room v8) against real SQLite.
 * The window predicate is **intersection, not containment** and inclusive at both ends — a stroke
 * wider than the window, or half scrolled off it, is still on screen and must come back — and the
 * indexed `minTsMs`/`maxTsMs` it is selected by are derived by scanning every point, so a stroke drawn
 * backwards in time is bounded correctly. The case table below is the same one the host-JVM
 * `PaintStrokeWindowTest` pins the rule with; this test replays it through the SQL.
 */
@RunWith(AndroidJUnit4::class)
class PaintStrokeDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: T1dmRepository
    private val dispatchers = DefaultT1dmDispatchers(io = Dispatchers.Default)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = T1dmRepository(db, dispatchers)
    }

    @After
    fun tearDown() = db.close()

    /** A two-point stroke spanning exactly [minTs]..[maxTs], authored at [createdAtMs]. */
    private fun stroke(minTs: Long, maxTs: Long, createdAtMs: Long = T0) = PaintStroke(
        id = 0,
        createdAtMs = createdAtMs,
        tool = "brush",
        colorArgb = 0xFFEE4488.toInt(),
        widthDp = 4.5f,
        tsMs = longArrayOf(minTs, maxTs),
        yFrac = floatArrayOf(0.2f, 0.8f),
    )

    private suspend fun visible(): List<Long> =
        repo.observePaintStrokes(WINDOW_FROM, WINDOW_TO).first().map { it.id }

    @Test
    fun windowSelectsEveryIntersectingStroke() = runTest {
        val expectVisible = mutableListOf<Long>()
        for (c in CASES) {
            val id = repo.addPaintStroke(stroke(c.minTsMs, c.maxTsMs))
            if (c.visible) expectVisible.add(id)
        }
        assertEquals(expectVisible, visible())
    }

    @Test
    fun strokeSurvivesTheRoundTripThroughTheBlob() = runTest {
        val ts = longArrayOf(T0 + 1_500, T0 + 1_100, T0 + 1_900)
        val id = repo.addPaintStroke(
            PaintStroke(
                id = 0, createdAtMs = T0, tool = "marker", colorArgb = 0x8800AAFF.toInt(),
                widthDp = 12.25f, tsMs = ts, yFrac = floatArrayOf(0.1f, 0.5f, 1.4f),
            ),
        )
        val back = repo.observePaintStrokes(WINDOW_FROM, WINDOW_TO).first().single()
        assertEquals(id, back.id)
        assertEquals("marker", back.tool)
        assertEquals(0x8800AAFF.toInt(), back.colorArgb)
        assertEquals(12.25f, back.widthDp, 0f)
        assertEquals(ts.toList(), back.tsMs.toList())
        assertEquals("out-of-panel y is preserved, not clamped", 1.4f, back.yFrac[2], 0f)
    }

    /** The extreme is in the middle of a doubled-back stroke; bounds must not come off the ends. */
    @Test
    fun aStrokeDrawnBackwardsIsStillFoundByItsTrueSpan() = runTest {
        repo.addPaintStroke(
            PaintStroke(
                id = 0, createdAtMs = T0, tool = "brush", colorArgb = -1, widthDp = 2f,
                tsMs = longArrayOf(T0 + 4_000, T0 + 1_500, T0 + 4_200),
                yFrac = floatArrayOf(0.1f, 0.2f, 0.3f),
            ),
        )
        assertEquals("its middle sample reaches into the window", 1, visible().size)
    }

    @Test
    fun strokesArriveInAuthoringOrder() = runTest {
        val late = repo.addPaintStroke(stroke(T0 + 1_100, T0 + 1_200, createdAtMs = T0 + 90_000))
        val early = repo.addPaintStroke(stroke(T0 + 1_300, T0 + 1_400, createdAtMs = T0 + 10_000))
        assertEquals("later strokes paint over earlier ones", listOf(early, late), visible())
    }

    @Test
    fun anEmptyStrokeIsRefused() = runTest {
        try {
            repo.addPaintStroke(
                PaintStroke(0, T0, "brush", -1, 2f, LongArray(0), FloatArray(0)),
            )
            org.junit.Assert.fail("a point-less stroke has no bounds and must not be stored")
        } catch (e: IllegalArgumentException) {
            assertTrue(!e.message.isNullOrBlank())
        }
    }

    @Test
    fun deleteRemovesOnlyTheNamedStroke() = runTest {
        val a = repo.addPaintStroke(stroke(T0 + 1_100, T0 + 1_200))
        val b = repo.addPaintStroke(stroke(T0 + 1_300, T0 + 1_400))
        repo.deletePaintStroke(a)
        assertEquals(listOf(b), visible())
        repo.deleteAllPaintStrokes()
        assertEquals(emptyList<Long>(), visible())
    }

    private data class Case(val name: String, val minTsMs: Long, val maxTsMs: Long, val visible: Boolean)

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val WINDOW_FROM = T0 + 1_000L
        const val WINDOW_TO = T0 + 2_000L

        val CASES = listOf(
            Case("wholly before the window", T0, T0 + 500, false),
            Case("wholly after the window", T0 + 3_000, T0 + 4_000, false),
            Case("ends exactly on the left edge", T0, WINDOW_FROM, true),
            Case("starts exactly on the right edge", WINDOW_TO, T0 + 5_000, true),
            Case("one instant just left of the edge", WINDOW_FROM - 1, WINDOW_FROM - 1, false),
            Case("one instant on the left edge", WINDOW_FROM, WINDOW_FROM, true),
            Case("contained in the window", T0 + 1_200, T0 + 1_800, true),
            Case("straddles the left edge", T0 + 500, T0 + 1_500, true),
            Case("straddles the right edge", T0 + 1_500, T0 + 2_500, true),
            Case("wider than the window on both sides", T0, T0 + 9_000, true),
        )
    }
}
