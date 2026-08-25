package com.t1dm.feature.game

/** Several may hold at once; declaration order is report order, most important first. */
enum class GameHold {
    /** The screen is no longer RESUMED. */
    Background,

    /** The opening animation is still running. */
    Intro,

    /** The exit confirmation, or the run's own terminal card. */
    Modal,
}

/** A bitmask over [GameHold]. A set, not one flag, because releases arrive out of order: an alarm
 *  clearing while the screen is still backgrounded must not resume the world. */
@JvmInline
value class GameHolds(val bits: Int) {
    fun with(hold: GameHold, on: Boolean): GameHolds =
        GameHolds(if (on) bits or (1 shl hold.ordinal) else bits and (1 shl hold.ordinal).inv())

    fun has(hold: GameHold): Boolean = bits and (1 shl hold.ordinal) != 0

    val paused: Boolean get() = bits != 0

    val primary: GameHold? get() = GameHold.entries.firstOrNull { has(it) }

    companion object {
        val NONE = GameHolds(0)
    }
}

/** Plain volatile memory, not snapshot state: written from the main thread, polled from the game
 *  thread every frame, and a hold must not recompose anything at frame rate. */
class GamePauseGate {
    @Volatile
    var holds: GameHolds = GameHolds.NONE
        private set

    val paused: Boolean get() = holds.paused

    fun set(hold: GameHold, on: Boolean) {
        holds = holds.with(hold, on)
    }
}

/**
 * The schedule is a PHASE, not a minimum delta: [dueNs] advances by exactly [periodNs] per simulated
 * frame, because a gate re-derived from the last simulated stamp has an error budget smaller than
 * ordinary vsync jitter. Returns the REAL elapsed wall clock, never the nominal period.
 */
class FrameClockPacer(private val periodNs: Long = FRAME_NS_60) {
    private val slackNs = periodNs / 4
    private var markNs = 0L
    private var dueNs = 0L
    private var started = false

    /** Milliseconds to simulate for the frame at [nowNs], or 0 to skip it. */
    fun tick(nowNs: Long, paused: Boolean): Float {
        // Not running, held, or handed a stamp that went backwards: drop the discontinuity.
        if (!started || paused || nowNs <= markNs) {
            markNs = nowNs
            dueNs = nowNs + periodNs
            started = true
            return 0f
        }
        if (nowNs < dueNs - slackNs) return 0f
        val elapsed = nowNs - markNs
        markNs = nowNs
        dueNs += periodNs
        // Behind by most of a period: re-anchor rather than fire near-empty frames to walk it off.
        if (dueNs <= nowNs + slackNs) dueNs = nowNs + periodNs
        return elapsed / 1_000_000f
    }

    companion object {
        /** Nominal 60 fps. The quarter-period slack admits ±4.2 ms of drift against a 120 Hz
         *  callback's 8.3 ms separation. No rate governor: thermal headroom does not throttle this. */
        const val FRAME_NS_60 = 16_666_667L
    }
}
