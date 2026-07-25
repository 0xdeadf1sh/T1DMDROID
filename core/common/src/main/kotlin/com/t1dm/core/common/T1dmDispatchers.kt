package com.t1dm.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The single injected dispatcher holder (§2.3). Nothing constructs
 * dispatchers ad hoc; heavy work never lands on [main].
 */
interface T1dmDispatchers {
    val main: CoroutineDispatcher        // Main.immediate — UI only
    val default: CoroutineDispatcher     // CPU: Rust pre/post, decode, grid-stamp, stats, crypto
    val io: CoroutineDispatcher          // Room, disk, HTTP/WS, file
    val inference: CoroutineDispatcher   // SINGLE-thread: serialises ExecuTorch/Neuron across <=5 models

    /**
     * SINGLE-thread: the hill-climb minigame's 60 Hz solver loop, and nothing else.
     *
     * Deliberately neither [inference] nor a slice of [default]. A cosmetic frame loop that shared
     * the inference thread would serialise behind (or ahead of) a forecast cycle, and one that took
     * a [default] worker would compete with the §3.6-A alarm engine's own single-thread slice and
     * with every decode/grid-stamp the ingestion path runs there. Its own thread makes the isolation
     * structural rather than a scheduling accident.
     */
    val game: CoroutineDispatcher
}

class DefaultT1dmDispatchers(
    override val main: CoroutineDispatcher = Dispatchers.Main,
    override val default: CoroutineDispatcher = Dispatchers.Default,
    override val io: CoroutineDispatcher = Dispatchers.IO,
    override val inference: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "t1dm-inference") }.asCoroutineDispatcher(),
    game: CoroutineDispatcher? = null,
) : T1dmDispatchers {
    private val gameOverride = game

    /** LAZY, unlike the eagerly-constructed [inference] thread: the minigame is opened rarely and
     *  never at all in most processes (and in every test that builds this class), so spawning a
     *  thread that then parks forever on a queue nothing posts to would be pure waste. */
    override val game: CoroutineDispatcher by lazy {
        gameOverride
            ?: Executors.newSingleThreadExecutor { r -> Thread(r, "t1dm-game") }.asCoroutineDispatcher()
    }
}
