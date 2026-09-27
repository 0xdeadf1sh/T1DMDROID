package com.t1dm.alerts

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persisted config held live; updates apply in call order and survive caller cancellation. */
class LiveConfig<T>(initial: T) {

    private val updates = Mutex()
    private val publishLock = Any()
    private var sink: ((T) -> Unit)? = null

    private val _flow = MutableStateFlow(initial)
    val flow: StateFlow<T> = _flow.asStateFlow()

    val value: T get() = _flow.value

    /** False while [value] is still the coded initial. */
    @Volatile
    var hydrated: Boolean = false
        private set

    /** Replays [value] to a new sink once hydrated. */
    fun setSink(sink: ((T) -> Unit)?) {
        synchronized(publishLock) {
            this.sink = sink
            if (hydrated) sink?.invoke(_flow.value)
        }
    }

    /** A failed [read] keeps [value] and returns the error; a failed [write] throws. */
    suspend fun update(write: suspend () -> Unit = {}, read: suspend () -> T): Result<T> =
        withContext(NonCancellable) {
            updates.withLock {
                write()
                runCatching { read() }.onSuccess { publish(it) }
            }
        }

    private fun publish(v: T) {
        synchronized(publishLock) {
            _flow.value = v
            hydrated = true
            sink?.invoke(v)
        }
    }
}
