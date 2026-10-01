package com.t1dm.feature.dashboard

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelMemoTest {

    @Test fun equalInputsReuseTheBuild() = runBlocking {
        val m = Memo<Int>()
        var builds = 0
        assertEquals(1, m.get("r", 1) { ++builds })
        assertEquals(1, m.get("r", 1) { ++builds })
        assertEquals(2, m.get("r", 2) { ++builds })
        assertEquals(3, m.get("r", 1) { ++builds })
    }

    @Test fun aNullResultIsReusedToo() = runBlocking {
        val m = Memo<String?>()
        var builds = 0
        m.get(null) { builds++; null }
        m.get(null) { builds++; null }
        assertEquals(1, builds)
    }

    @Test fun aCancelledBuildStoresNothing() = runBlocking {
        val m = Memo<Int>()
        val job = launch { m.get("k") { awaitCancellation() } }
        yield()
        job.cancelAndJoin()
        assertEquals(7, m.get("k") { 7 })
    }
}
