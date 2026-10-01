package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What may be WRITTEN DOWN vs what it hears: storage follows relationship, not proximity. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedCgmRegistryTest {

    @Test
    fun `a sensor merely within earshot is listed and never written down`() = runTest {
        val repo = RecordingRepository()
        val registry = registry(repo, driver(candidate(STRANGER, adoptable = false)))

        registry.start()
        runCurrent()

        assertEquals("it is still listed, so the panel can offer it", listOf(STRANGER), registry.sources.value.ids())
        assertEquals("but nothing is stored", emptyList<String>(), repo.upserts)
        assertNull("and it is certainly not adopted", registry.authoritative.value)
    }

    @Test
    fun `switching a discovered sensor on is what writes it down`() = runTest {
        val repo = RecordingRepository()
        val registry = registry(repo, driver(candidate(STRANGER, adoptable = false)))
        registry.start()
        runCurrent()
        assertEquals(emptyList<String>(), repo.upserts)

        registry.activate(CgmSourceId(STRANGER))
        runCurrent()

        // Row must be written BEFORE the flag: activate is an UPDATE, lost on a row not yet there.
        assertEquals(listOf(STRANGER), repo.upserts)
        assertEquals(listOf(STRANGER), repo.activated)
    }

    @Test
    fun `a sensor this app holds the key for is written down without being asked`() = runTest {
        // Adoptable = a relationship exists already: the secret is held, or the user paired it.
        val repo = RecordingRepository()
        val registry = registry(repo, driver(candidate(OURS, adoptable = true)))

        registry.start()
        runCurrent()

        assertEquals(listOf(OURS), repo.upserts)
        assertEquals("the first adoptable sensor ever seen is believed", CgmSourceId(OURS), registry.authoritative.value)
    }

    @Test
    fun `a sensor already on record keeps being refreshed`() = runTest {
        // The gate must not strand a sensor predating it, whose family can't call it adoptable.
        val stored = descriptor(OURS)
        val repo = RecordingRepository(stored = listOf(stored))
        val registry = registry(repo, driver(candidate(OURS, adoptable = false)))

        registry.start()
        runCurrent()

        assertEquals(listOf(OURS), registry.sources.value.ids())
        assertEquals("a row on record is still touched", listOf(OURS), repo.upserts)
    }

    /** "No session built" is NOT "a session gave nothing back"; family sees only the latter. */
    @Test
    fun `a sensor that could not be connected to at all is not reported as having yielded nothing`() = runTest {
        val driver = UnreachableDriver(listOf(candidate(OURS, adoptable = true)))
        val registry = registry(RecordingRepository(), driver)

        registry.start()
        runCurrent()
        // The session loop waits RETRY_MAX_MS on the unbuildable path each cycle.
        repeat(4) {
            advanceTimeBy(60_000)
            runCurrent()
        }

        assertTrue("the sensor was adopted, so sessions really were attempted", registry.activeIds.value.isNotEmpty())
        assertEquals("and not one of those attempts was a session that yielded nothing", 0, driver.yieldedNothing)
    }

    @Test
    fun `a rescan runs an enumeration pass now and tells every family`() = runTest {
        val driver = CountingDriver()
        val registry = registry(RecordingRepository(), driver)
        registry.start()
        runCurrent()
        assertEquals(1, driver.enumerations)

        registry.rescanNow()
        runCurrent()

        assertEquals(1, driver.rescans)
        assertEquals("without waiting out the tick", 2, driver.enumerations)
    }

    @Test
    fun `a search the user asked for reads as running until its own sweep is done`() = runTest {
        val driver = CountingDriver()
        val registry = registry(RecordingRepository(), driver)
        registry.start()
        runCurrent()
        assertFalse("nothing is running before the user asks", registry.scanning.value)

        registry.rescanNow()
        assertTrue("the button must change on the press, not a pass later", registry.scanning.value)

        runCurrent()
        assertFalse(registry.scanning.value)
    }

    @Test
    fun `a reconnect tears the held session down and builds its successor at once`() = runTest {
        val driver = SessionDriver(listOf(candidate(OURS, adoptable = true)))
        val registry = registry(RecordingRepository(), driver)
        registry.start()
        runCurrent()
        assertEquals(1, driver.sessions.size)
        assertEquals(CgmSourceStatus.Live, driver.sessions[0].status.value)

        registry.reconnect(CgmSourceId(OURS))
        runCurrent()

        assertEquals("the family forgets its hold-offs first", listOf(OURS), driver.forgotten)
        assertTrue("the old link is closed", driver.sessions[0].closed)
        assertEquals("and a new one is built without waiting out the tick", 2, driver.sessions.size)
    }

    @Test
    fun `a key recovery tears the held session down before it touches storage`() = runTest {
        val driver = SessionDriver(listOf(candidate(OURS, adoptable = true)), tailMs = 1_000)
        val registry = registry(RecordingRepository(), driver)
        registry.start()
        runCurrent()
        assertEquals(1, driver.sessions.size)

        registry.recoverKey(CgmSourceId(OURS))
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals("the old link is closed first", true, driver.closedAtRecovery)
        assertEquals("and its in-flight write has landed", true, driver.tailDoneAtRecovery)
        assertEquals("a successor is built, key found or not", 2, driver.sessions.size)
    }

    /** §14 phase 6 clean disconnect: the press ends the link now, not at the next tick. */
    @Test
    fun `stopping a sensor tears its link down without waiting out the tick`() = runTest {
        val driver = SessionDriver(
            listOf(candidate(OURS, adoptable = true), candidate(STRANGER, adoptable = true)),
        )
        val registry = registry(RecordingRepository(), driver)
        registry.start()
        runCurrent()
        // OURS was adopted first (authoritative); STRANGER's session waits for a pass.
        registry.activate(CgmSourceId(STRANGER))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, driver.sessions.size)

        registry.deactivate(CgmSourceId(STRANGER))
        runCurrent()

        assertFalse(CgmSourceId(STRANGER) in registry.activeIds.value)
        assertTrue("the press ends the link, no 30 s tick in between", driver.sessions[1].closed)
    }

    @Test
    fun `a sensor's wear is what it states, else what it stated before, else its family's rating`() = runTest {
        suspend fun wear(driver: CgmFamilyDriver): Int? {
            val registry = registry(RecordingRepository(stored = listOf(descriptor(OURS))), driver)
            registry.start()
            runCurrent()
            return registry.lifetimeMinOf(CgmSourceId(OURS)).first()
        }
        val found = listOf(candidate(OURS, adoptable = true))

        assertNull("nothing known, nothing assumed", wear(LifetimeDriver(found)))
        assertEquals(16 * 1440, wear(LifetimeDriver(found, ratedDays = 16)))
        assertEquals(21_600, wear(LifetimeDriver(found, ratedDays = 16, stored = 21_600)))
        assertEquals(20_160, wear(LifetimeDriver(found, ratedDays = 16, stored = 21_600, stated = 20_160)))
    }

    @Test
    fun `a sensor's start is what its session holds, else what storage holds, else unknown`() = runTest {
        suspend fun start(driver: CgmFamilyDriver): Long? {
            val registry = registry(RecordingRepository(stored = listOf(descriptor(OURS))), driver)
            registry.start()
            runCurrent()
            return registry.sensorStartMsOf(CgmSourceId(OURS)).first()
        }
        val found = listOf(candidate(OURS, adoptable = true))

        assertNull("nothing held, minFromStart dates it", start(StartDriver(found)))
        assertEquals(1_000L, start(StartDriver(found, stored = 1_000L)))
        assertEquals(2_000L, start(StartDriver(found, stored = 1_000L, held = 2_000L)))
    }

    private fun List<CgmSourceDescriptor>.ids() = map { it.id.value }

    private fun TestScope.registry(repo: CgmRepository, vararg drivers: CgmFamilyDriver) =
        ConnectedCgmRegistry(repo, backgroundScope, drivers.toList()) { currentTime }

    private fun candidate(id: String, adoptable: Boolean) =
        CgmFamilyCandidate(descriptor = descriptor(id), adoptable = adoptable)

    private fun descriptor(id: String) = CgmSourceDescriptor(
        id = CgmSourceId(id),
        vendorId = VENDOR,
        sensorModelId = "$VENDOR:model",
        advertName = null,
        displayName = id,
        serialSuffix = id.substringAfter(':'),
        warmupWindowMin = 45,
        passiveOnly = false,
    )

    private fun driver(vararg found: CgmFamilyCandidate) = object : CgmFamilyDriver {
        override val vendorId = VENDOR
        override suspend fun candidates(active: Set<CgmSourceId>, connected: Set<CgmSourceId>) = found.toList()

        /** No session is ever built: under test is what gets STORED, not what gets connected. */
        override suspend fun createSession(descriptor: CgmSourceDescriptor, scope: CoroutineScope) = null
    }

    private class UnreachableDriver(private val found: List<CgmFamilyCandidate>) : CgmFamilyDriver {
        var yieldedNothing = 0
            private set

        override val vendorId = VENDOR
        override suspend fun candidates(active: Set<CgmSourceId>, connected: Set<CgmSourceId>) = found

        override suspend fun createSession(descriptor: CgmSourceDescriptor, scope: CoroutineScope) = null

        override suspend fun sessionYieldedNothing(descriptor: CgmSourceDescriptor) {
            yieldedNothing++
        }
    }

    private class CountingDriver : CgmFamilyDriver {
        var enumerations = 0
            private set
        var rescans = 0
            private set

        override val vendorId = VENDOR

        override suspend fun candidates(active: Set<CgmSourceId>, connected: Set<CgmSourceId>): List<CgmFamilyCandidate> {
            enumerations++
            return emptyList()
        }

        override suspend fun createSession(descriptor: CgmSourceDescriptor, scope: CoroutineScope) = null

        override fun rescan() {
            rescans++
        }
    }

    /** Goes Live on [start] and stays there, so only a cancellation ends it. */
    private class FakeSession(override val descriptor: CgmSourceDescriptor) : ConnectedCgmSession {
        override val status = MutableStateFlow(CgmSourceStatus.Scanning)
        override val rssi = MutableStateFlow<Int?>(null)
        override val statedLifetimeMin = MutableStateFlow<Int?>(null)
        override val sensorStartMs = MutableStateFlow<Long?>(null)
        var closed = false
            private set

        override fun readings(): Flow<CgmReading> = emptyFlow()

        override fun start() {
            status.value = CgmSourceStatus.Live
        }

        override fun close() {
            closed = true
        }

        override fun markSignalLost() {
            status.value = CgmSourceStatus.SignalLost
        }
    }

    private class SessionDriver(
        private val found: List<CgmFamilyCandidate>,
        /** How long a session-scope child keeps writing once cancelled; null launches none. */
        private val tailMs: Long? = null,
    ) : CgmFamilyDriver {
        val sessions = mutableListOf<FakeSession>()
        val forgotten = mutableListOf<String>()
        private val tailsDone = mutableSetOf<FakeSession>()
        var closedAtRecovery: Boolean? = null
            private set
        var tailDoneAtRecovery: Boolean? = null
            private set

        override val vendorId = VENDOR
        override suspend fun candidates(active: Set<CgmSourceId>, connected: Set<CgmSourceId>) = found

        override suspend fun createSession(descriptor: CgmSourceDescriptor, scope: CoroutineScope) =
            FakeSession(descriptor).also { session ->
                sessions += session
                val ms = tailMs ?: return@also
                scope.launch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(ms)
                            tailsDone += session
                        }
                    }
                }
            }

        override fun forget(descriptor: CgmSourceDescriptor) {
            forgotten += descriptor.id.value
        }

        override suspend fun recoverKey(descriptor: CgmSourceDescriptor): Boolean {
            val old = sessions.first()
            closedAtRecovery = old.closed
            tailDoneAtRecovery = old in tailsDone
            return false
        }
    }

    /** A session is built only when [stated] is given, and states it on creation. */
    private class LifetimeDriver(
        private val found: List<CgmFamilyCandidate>,
        ratedDays: Int? = null,
        private val stored: Int? = null,
        private val stated: Int? = null,
    ) : CgmFamilyDriver {
        override val vendorId = VENDOR
        override val facts = CgmFamilyFacts(ratedCycleDays = ratedDays)
        override suspend fun candidates(active: Set<CgmSourceId>, connected: Set<CgmSourceId>) = found
        override suspend fun storedLifetimeMin(id: CgmSourceId) = stored

        override suspend fun createSession(descriptor: CgmSourceDescriptor, scope: CoroutineScope) =
            stated?.let { minutes -> FakeSession(descriptor).apply { statedLifetimeMin.value = minutes } }
    }

    /** A session is built only when [held] is given, and holds it on creation. */
    private class StartDriver(
        private val found: List<CgmFamilyCandidate>,
        private val stored: Long? = null,
        private val held: Long? = null,
    ) : CgmFamilyDriver {
        override val vendorId = VENDOR
        override suspend fun candidates(active: Set<CgmSourceId>, connected: Set<CgmSourceId>) = found
        override suspend fun storedSensorStartMs(id: CgmSourceId) = stored

        override suspend fun createSession(descriptor: CgmSourceDescriptor, scope: CoroutineScope) =
            held?.let { ms -> FakeSession(descriptor).apply { sensorStartMs.value = ms } }
    }

    private class RecordingRepository(private val stored: List<CgmSourceDescriptor> = emptyList()) :
        FakeCgmRepository() {
        val upserts = mutableListOf<String>()
        val activated = mutableListOf<String>()

        override suspend fun loadSources() = stored

        override suspend fun upsertSource(
            descriptor: CgmSourceDescriptor,
            authoritative: Boolean,
            lastSeenMs: Long,
        ): Int {
            upserts += descriptor.id.value
            return super.upsertSource(descriptor, authoritative, lastSeenMs)
        }

        override suspend fun activate(id: CgmSourceId) {
            activated += id.value
        }
    }

    private companion object {
        const val VENDOR = "testvendor"
        const val STRANGER = "$VENDOR:STRANGER"
        const val OURS = "$VENDOR:OURS"
    }
}
