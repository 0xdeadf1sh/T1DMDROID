package com.t1dm.alerts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LiveConfigTest {

    @Test
    fun `a read error keeps the last value`() = runTest {
        val live = LiveConfig(1)
        assertTrue(live.update { throw IOException("disk") }.isFailure)
        assertEquals(1, live.value)
        assertFalse(live.hydrated)

        live.update { 2 }
        assertTrue(live.update { throw IOException("disk") }.isFailure)
        assertEquals(2, live.value)
        assertTrue(live.hydrated)
    }

    @Test
    fun `a cancelled caller still publishes`() = runTest {
        val live = LiveConfig(0)
        val slowRead = CompletableDeferred<Unit>()
        val caller = launch { live.update { slowRead.await(); 7 } }
        runCurrent()
        caller.cancel()
        slowRead.complete(Unit)
        advanceUntilIdle()
        assertEquals(7, live.value)
        assertTrue(live.hydrated)
    }

    @Test
    fun `a sink set after hydration receives the value`() = runTest {
        val live = LiveConfig(0)
        var got: Int? = null
        live.setSink { got = it }
        assertNull(got)
        live.setSink(null)

        live.update { 5 }
        live.setSink { got = it }
        assertEquals(5, got)
    }

    @Test
    fun `two overlapping updates end on the later write`() = runTest {
        val live = LiveConfig(0)
        var stored = 0
        val slowRead = CompletableDeferred<Unit>()
        launch {
            live.update(write = { stored = 1 }) {
                val read = stored
                slowRead.await()
                read
            }
        }
        runCurrent()
        launch { live.update(write = { stored = 2 }) { stored } }
        runCurrent()
        slowRead.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, stored)
        assertEquals(2, live.value)
    }
}
