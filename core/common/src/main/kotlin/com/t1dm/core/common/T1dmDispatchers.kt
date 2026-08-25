package com.t1dm.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** The single injected dispatcher holder (§2.3); heavy work never lands on [main]. */
interface T1dmDispatchers {
    val main: CoroutineDispatcher        // Main.immediate — UI only
    val default: CoroutineDispatcher     // CPU: Rust pre/post, decode, grid-stamp, stats, crypto
    val io: CoroutineDispatcher          // Room, disk, HTTP/WS, file
    val inference: CoroutineDispatcher   // SINGLE-thread: serialises ExecuTorch/Neuron across <=5 models

    /** SINGLE-thread: the minigame's 60 Hz solver loop, and nothing else. Deliberately neither
     *  [inference] nor a slice of [default]. */
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

    /** LAZY, unlike the eagerly-constructed [inference] thread: most processes never open the minigame. */
    override val game: CoroutineDispatcher by lazy {
        gameOverride
            ?: Executors.newSingleThreadExecutor { r -> Thread(r, "t1dm-game") }.asCoroutineDispatcher()
    }
}
