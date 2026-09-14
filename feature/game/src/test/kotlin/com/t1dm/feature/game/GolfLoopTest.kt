package com.t1dm.feature.game

import androidx.compose.runtime.BroadcastFrameClock
import com.t1dm.core.common.GolfWorld
import com.t1dm.core.model.BallState
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.GolfCup
import com.t1dm.core.model.GolfRun
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs
import org.junit.Test

private const val MS = 1_000_000L
private const val FRAME = 20 * MS

/** Frames of [FRAME] that cover [DOWNSWING_S], the lead from release to strike. */
private const val DOWNSWING_FRAMES = 7

/** Shipped opening takes ~160 frames; cap fails rather than hangs if it never ends. */
private const val OPEN_FRAME_CAP = 600

/** Fake world; records the thread every call arrived on and refuses a shot in flight. */
private class FakeGolfWorld : GolfWorld {
    override val trackLength = 400f

    override val cup = GolfCup(x0 = 390f, x1 = 400f, rimY = 40f, depth = 7f)

    val callThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile var steps = 0
    @Volatile var totalDtMs = 0f
    @Volatile var tees = 0
    @Volatile var shots = 0
    @Volatile var refusedShots = 0
    @Volatile var closed = false

    /** The real world latches this in Rust and short-circuits `step` on it. */
    @Volatile var run = GolfRun.Playing
    @Volatile var atRest = true
    @Volatile var strokes = 0
    @Volatile var penalties = 0

    var lastTeeX = Float.NaN
        private set
    var lastShotVx = Float.NaN
        private set
    var lastShotVy = Float.NaN
        private set

    private var x = 0f

    override fun step(dtMs: Float): BallState {
        callThreads += Thread.currentThread().name
        steps++
        totalDtMs += dtMs
        if (!atRest) x += dtMs * 0.02f
        return state()
    }

    override fun state(): BallState {
        callThreads += Thread.currentThread().name
        return BallState(
            x = x, y = 44f, vx = if (atRest) 0f else 20f, vy = 0f, angle = 0f,
            atRest = atRest, airborne = false, strokes = strokes, penalties = penalties,
            impact = 0f, run = run,
        )
    }

    override fun shoot(vx: Float, vy: Float): BallState {
        callThreads += Thread.currentThread().name
        if (!atRest || run != GolfRun.Playing) {
            refusedShots++
            return state()
        }
        shots++
        strokes++
        atRest = false
        lastShotVx = vx
        lastShotVy = vy
        return state()
    }

    override fun teeAt(x: Float): BallState {
        tees++
        lastTeeX = x
        this.x = x
        atRest = true
        strokes = 0
        penalties = 0
        run = GolfRun.Playing
        return state()
    }

    override fun reset(): BallState = teeAt(lastTeeX)

    override fun close() {
        closed = true
    }
}

/** What the loop asked of the senses, and on which thread. */
private class RecordingGolfFeel : GolfFeelSink {
    @Volatile var frames = 0
    @Volatile var holds = 0
    val callThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    override fun frame(s: BallState) {
        callThreads += Thread.currentThread().name
        frames++
    }

    override fun hold() {
        callThreads += Thread.currentThread().name
        holds++
    }
}

private fun readings(n: Int): List<CgmReading> {
    val t0 = 1_700_000_000_000L / 300_000L * 300_000L
    return List(n) { i ->
        CgmReading(
            sourceId = CgmSourceId("test"),
            tsMs = t0 + i * 300_000L,
            bgMgdl = 100 + (i % 40),
            trendTenthsPerMin = 0,
            minFromStart = 120 + i * 5,
            quality = 1,
            provenance = ReadingProvenance.MEASURED,
            flag = ReadingFlag.NORMAL,
            tzOffsetMin = 0,
            rxWallMs = t0 + i * 300_000L,
            rssi = -60,
        )
    }
}

/** BroadcastFrameClock isn't AndroidUiFrameClock; Choreographer plumbing isn't covered. */
class GolfLoopTest {

    private class Harness(val zoom: GameZoom) {
        val world = FakeGolfWorld()
        val bus = GolfFrameBus()
        val camera = GameCamera()
        val controls = GolfControls()
        val viewport = GameViewport().apply { widthPx = 1080f; heightPx = 2000f }
        val gate = GamePauseGate()
        val commands = GolfCommands()
        val hud = GolfHudState()
        val live = LiveReadingRef()
        val clock = BroadcastFrameClock()
        val feel = RecordingGolfFeel()

        /** Arbitrary: the figure is a function OF the tuning, not a copy of a shipped number. */
        val golfer = Golfer(3f, 60f)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, GAME_THREAD) }

        fun CoroutineScope.startLoop(track: com.t1dm.ui.game.GameTrack, teeAtX: Float): Job =
            launch(executor.asCoroutineDispatcher()) {
                try {
                    runGolfLoop(
                        teeAtX,
                        {},
                        0f,
                        world, track, bus, camera, zoom, controls, viewport,
                        gate, commands, hud, live, ZoneId.of("UTC"), feel, golfer,
                    )
                } finally {
                    world.close()
                }
            }

        suspend fun frame(nanos: Long) {
            // A frame sent before the loop awaits is lost; wait for the awaiter, not a sleep.
            settle()
            clock.sendFrame(nanos)
        }

        /** Block until the loop is back awaiting the clock: every frame sent has been processed. */
        suspend fun settle() {
            while (!clock.hasAwaiters) delay(1)
        }
    }

    /** zoom defaults to NO drop: shipped 0.42s hold swallows every step-counting burst here. */
    private fun harness(
        teeAtX: Float = 0f,
        zoom: GameZoom = GameZoom(revealS = 0f),
        block: suspend Harness.(Job) -> Unit,
    ) {
        val h = Harness(zoom)
        val track = runBlocking {
            loadGameScene(readings(300), emptyList(), UnitSpace.MgDl, null, 20, 250).track
        }
        assertTrue("the synthetic day must be playable", track.isPlayable)
        try {
            runBlocking(h.clock) {
                val job = with(h) { startLoop(track, teeAtX) }
                h.block(job)
                job.cancel()
                job.join()
            }
        } finally {
            h.executor.shutdownNow()
        }
    }

    @Test
    fun `every solver call lands on the dedicated game thread`() = harness { job ->
        var t = 1_000L * MS
        repeat(8) {
            t += FRAME
            frame(t)
        }
        settle()
        commands.fire(30f, 20f)
        t += FRAME
        frame(t)
        settle()
        job.cancel()
        job.join()
        assertTrue("the world was touched at all", world.callThreads.isNotEmpty())
        // The coroutines debug agent decorates thread names with " @coroutine#n".
        val names = world.callThreads.map { it.substringBefore(" @") }.toSet()
        assertEquals(setOf(GAME_THREAD), names)
        assertFalse(names.contains(Thread.currentThread().name.substringBefore(" @")))
    }

    @Test
    fun `the first frame tees the ball without simulating it`() = harness { job ->
        frame(1_000L * MS)
        settle()
        job.cancel()
        job.join()
        assertEquals(0, world.steps)
        assertEquals(1, world.tees)
        // Still published, so the screen has something to draw before the first tick.
        assertTrue(bus.tick >= 1L)
    }

    @Test
    fun `the opening holds the solver and shows the ball only once the zoom has settled`() =
        harness(zoom = GameZoom()) { job ->
            // A real zoom to perform: without these the target span falls back to the current one.
            viewport.visibleWidthM = 900f
            viewport.settledWidthM = 120f
            var t = 1_000L * MS
            frame(t)
            settle()
            assertFalse("the ball must not be drawn on the tee frame", bus.published.ballShown)

            val tickAfterTee = bus.tick
            repeat(6) {
                t += FRAME
                frame(t)
            }
            settle()
            assertEquals("the solver must stay frozen through the opening", 0, world.steps)
            assertTrue("the opening must still publish", bus.tick > tickAfterTee)

            var frames = 0
            while (frames < OPEN_FRAME_CAP && bus.published.ballLiftM > 1e-4f) {
                t += FRAME
                frame(t)
                frames++
            }
            settle()
            job.cancel()
            job.join()
            assertTrue("the opening never completed in $frames frames", frames < OPEN_FRAME_CAP)
            assertTrue("the ball must be shown once open", bus.published.ballShown)
            assertTrue("the solver must run once the opening is done", world.steps > 0)
        }

    @Test
    fun `frames advance the world and publish exactly one buffer each`() = harness { job ->
        var t = 1_000L * MS
        frame(t)
        settle()
        val tickAfterTee = bus.tick
        repeat(10) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals(10, world.steps)
        assertEquals(10L, bus.tick - tickAfterTee)
        assertEquals(200f, world.totalDtMs, 0.5f)
    }

    @Test
    fun `a hold freezes the world and releasing it resumes without a lurch`() = harness { job ->
        var t = 1_000L * MS
        frame(t)
        t += FRAME
        frame(t)
        settle()
        val stepsBefore = world.steps

        gate.set(GameHold.Background, true)
        // A long absence, delivered as frames — the case where the window stays attached.
        repeat(20) {
            t += 500L * MS
            frame(t)
        }
        settle()
        assertEquals("nothing simulated while held", stepsBefore, world.steps)

        gate.set(GameHold.Background, false)
        val dtBefore = world.totalDtMs
        t += FRAME
        frame(t)
        while (world.steps == stepsBefore) delay(1)
        job.cancel()
        job.join()
        val resumedDt = world.totalDtMs - dtBefore
        assertTrue("resumed with $resumedDt ms, not the whole absence", abs(resumedDt - 20f) < 5f)
    }

    @Test
    fun `a shot is taken once, when the downswing reaches the ball`() = harness { job ->
        var t = 1_000L * MS
        frame(t)
        t += FRAME
        frame(t)
        settle()
        assertEquals(0, world.shots)

        commands.fire(31f, 27f)
        t += FRAME
        frame(t)
        settle()
        assertEquals("the release is not yet the strike", 0, world.shots)
        repeat(DOWNSWING_FRAMES) {
            t += FRAME
            frame(t)
        }
        settle()
        assertEquals("exactly one shot leaves the queue", 1, world.shots)
        assertEquals(31f, world.lastShotVx, 1e-4f)
        assertEquals(27f, world.lastShotVy, 1e-4f)

        repeat(10) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals("and a consumed shot is not replayed", 1, world.shots)
        assertEquals(1, world.strokes)
    }

    @Test
    fun `a shot fired at a moving ball is refused, not counted`() = harness { job ->
        var t = 1_000L * MS
        frame(t)
        t += FRAME
        frame(t)
        settle()
        commands.fire(40f, 20f)
        repeat(1 + DOWNSWING_FRAMES) {
            t += FRAME
            frame(t)
        }
        settle()
        assertEquals(1, world.strokes)
        assertFalse("the ball is moving now", world.atRest)

        commands.fire(40f, 20f)
        repeat(1 + DOWNSWING_FRAMES) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals("the world refused it", 1, world.refusedShots)
        assertEquals("and the scorecard did not move", 1, world.strokes)
    }

    @Test
    fun `a terminal round holds the loop as hard as any other hold`() = harness { job ->
        var t = 1_000L * MS
        frame(t)
        t += FRAME
        frame(t)
        settle()

        world.run = GolfRun.Holed
        t += FRAME
        frame(t)
        settle()
        // Holing is simulated; it must reach the HUD at once or the end card never appears.
        val stepsAtHole = world.steps
        val tickAtHole = bus.tick
        assertEquals(GolfRun.Holed, hud.value.run)

        val describedAtHole = feel.frames
        repeat(20) {
            t += FRAME
            frame(t)
        }
        settle()
        assertEquals("a frozen scene is not stepped", stepsAtHole, world.steps)
        assertEquals("…nor re-published", tickAtHole, bus.tick)
        assertEquals(describedAtHole, feel.frames)
        assertTrue("and every held frame says so", feel.holds >= 20)

        commands.reset = true
        t += FRAME
        frame(t)
        while (world.tees == 1) delay(1)
        job.cancel()
        job.join()
        assertEquals(GolfRun.Playing, world.run)
        assertEquals(0, world.strokes)
    }

    @Test
    fun `the world is closed when the loop is cancelled`() = harness { job ->
        frame(1_000L * MS)
        settle()
        job.cancel()
        job.join()
        assertTrue(world.closed)
    }

    @Test
    fun `an un-laid-out viewport banks no time`() = harness { job ->
        viewport.widthPx = 0f
        viewport.heightPx = 0f
        var t = 1_000L * MS
        repeat(5) {
            t += 1_000L * MS
            frame(t)
        }
        settle()
        assertEquals(0, world.steps)

        viewport.widthPx = 1080f
        viewport.heightPx = 2000f
        t += FRAME
        frame(t)
        t += FRAME
        frame(t)
        while (world.steps == 0) delay(1)
        job.cancel()
        job.join()
        // The five seconds spent waiting for layout must not arrive as the opening timestep.
        assertTrue("first dt was ${world.totalDtMs} ms", world.totalDtMs < 60f)
    }

    @Test
    fun `the senses ride the simulated frames, on the game thread`() = harness { job ->
        var t = 1_000L * MS
        frame(t)
        repeat(10) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals("one frame described per step simulated", world.steps, feel.frames)
        // Exactly one hold: the tee. A live round holds nothing.
        assertEquals(1, feel.holds)
        val names = feel.callThreads.map { it.substringBefore(" @") }.toSet()
        assertEquals(setOf(GAME_THREAD), names)
    }

    @Test
    fun `the tee lands at the tap, not at the track origin`() = harness(teeAtX = 137f) { job ->
        var t = 1_000L * MS
        frame(t)
        settle()
        job.cancel()
        job.join()
        assertEquals("teed at the tap", 137f, world.lastTeeX, 0.001f)
        assertEquals("and the published frame agrees", 137f, bus.published.x, 0.001f)
        assertEquals("the tee mark too", 137f, bus.published.teeX, 0.001f)
        assertTrue("the figure is placed with it", bus.published.golferShown)
        assertTrue("behind the ball", bus.published.golferX < bus.published.x)
    }

    @Test
    fun `a stale shot queued before the tee never fires`() = harness { job ->
        commands.fire(50f, 50f)
        frame(1_000L * MS)
        settle()
        var t = 1_000L * MS
        repeat(5) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals("a shot aimed at a world that no longer exists is dropped", 0, world.shots)
    }

    private companion object {
        const val GAME_THREAD = "t1dm-golf-under-test"
    }
}
