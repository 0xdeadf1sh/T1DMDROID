package com.t1dm.feature.game

/** Several may hold at once; declaration order is report order, most important first. */
enum class GameHold {
    /** The screen is no longer RESUMED. */
    Background,

    /** The exit confirmation. */
    Modal,
}

/** A bitmask over GameHold; a set, not one flag, since releases arrive out of order. */
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

/** Plain volatile memory, not snapshot: written on main, polled on game thread every frame. */
class GamePauseGate {
    @Volatile
    var holds: GameHolds = GameHolds.NONE
        private set

    val paused: Boolean get() = holds.paused

    fun set(hold: GameHold, on: Boolean) {
        holds = holds.with(hold, on)
    }
}

/** The schedule is a PHASE, not a delta: dueNs advances exactly periodNs per simulated frame. */
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
        /** Nominal 60 fps; quarter-period slack admits ±4.2ms drift vs 120 Hz's 8.3ms gap. */
        const val FRAME_NS_60 = 16_666_667L
    }
}
