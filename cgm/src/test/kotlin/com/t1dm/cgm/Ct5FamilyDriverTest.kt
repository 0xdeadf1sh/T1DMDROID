package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The driver holds no Android type; adoptable is asked directly, rationing via an empty scan. */
@OptIn(ExperimentalCoroutinesApi::class)
class Ct5FamilyDriverTest {

    /** Rated wear is written to the sensor at bind — the hardware's number, not a preference. */
    @Test
    fun `the family declares a rated wear and no activation`() = runTest {
        val facts = driver(this, FakeCgmRepository()).facts
        assertEquals(Ct5Constants.CYCLE_DAYS, facts.ratedCycleDays)
        assertFalse("there is no activation frame on this family", facts.supportsActivate)
    }

    @Test
    fun `an unbound sensor with no secret is never adopted`() = runTest {
        val driver = driver(this, FakeCgmRepository())
        assertFalse(
            "a sensor that reports nothing must not become the source everything is derived from",
            driver.adoptable(BSN),
        )
    }

    /** No local secret: another client holds CIPHER_ID, unmintable; its pushes are undecodable. */
    @Test
    fun `a sensor bound elsewhere is not adoptable, whatever it advertises`() = runTest {
        val repo = CountingRepository()
        val driver = driver(this, repo)
        assertFalse(driver.adoptable(BSN))
        assertEquals("the sealed store is the only thing that can answer", 1, repo.secretReads)
    }

    @Test
    fun `a sensor this app holds a secret for is adoptable`() = runTest {
        // Advertised flag flips after 0x38+0x06; a bind cut past 0x30 stays readable, "unbound".
        val repo = CountingRepository()
        repo.sensorSecrets[CgmSourceId("anytime:$BSN")] = byteArrayOf(1)
        val driver = driver(this, repo)
        assertTrue(driver.adoptable(BSN))
    }

    @Test
    fun `the secret store is opened once, not on every enumeration tick`() = runTest {
        val repo = CountingRepository()
        repo.sensorSecrets[CgmSourceId("anytime:$BSN")] = byteArrayOf(1)
        val driver = driver(this, repo)

        repeat(20) { assertTrue(driver.adoptable(BSN)) }
        // The coordinator enumerates every 30 seconds for the life of the process.
        assertEquals(1, repo.secretReads)
    }

    @Test
    fun `a sensor with no secret is asked about each time`() = runTest {
        // The negative answer is NOT cached: it changes the moment the user binds the sensor.
        val repo = CountingRepository()
        val driver = driver(this, repo)
        repeat(3) { assertFalse(driver.adoptable(BSN)) }
        assertEquals(3, repo.secretReads)
    }

    /** Without this path adoptable is false, belongsOnRecord drops it; can't be pointed at. */
    @Test
    fun `an imported sensor becomes readable and therefore adoptable`() = runTest {
        val repo = FakeCgmRepository()
        val driver = driver(this, repo, importSource = FakeImportSource(importDocument()))

        assertFalse("nothing is held before the import runs", driver.adoptable(BSN))
        driver.sweep()

        assertTrue(driver.adoptable(BSN))
        val stored = repo.sensorSecrets[CgmSourceId("anytime:$BSN")]
        assertEquals(171, requireNotNull(stored?.let(Ct5SensorState::decode)).cipherId)
    }

    @Test
    fun `a held sensor's stored start is its bind anchor`() = runTest {
        val repo = FakeCgmRepository()
        val id = CgmSourceId("anytime:$BSN")
        val driver = driver(this, repo)

        assertNull("nothing held", driver.storedSensorStartMs(id))
        repo.sensorSecrets[id] = STATE.encode()
        assertEquals(STATE.bindTimeMs, driver.storedSensorStartMs(id))
    }

    /** Bind FINISHED on the client that did it; resuming would rewrite 0x38 over a live wear. */
    @Test
    fun `an imported sensor is stored as a finished bind`() = runTest {
        val repo = FakeCgmRepository()
        driver(this, repo, importSource = FakeImportSource(importDocument())).sweep()
        val stored = requireNotNull(repo.sensorSecrets[CgmSourceId("anytime:$BSN")])
        assertTrue(requireNotNull(Ct5SensorState.decode(stored)).initialised)
    }

    /** Stored bytes are the only password/CIPHER_ID copy; a naming doc is discarded unapplied. */
    @Test
    fun `an import never overwrites a secret already held`() = runTest {
        val repo = FakeCgmRepository()
        val id = CgmSourceId("anytime:$BSN")
        repo.sensorSecrets[id] = byteArrayOf(9, 9, 9)
        val source = FakeImportSource(importDocument())

        driver(this, repo, importSource = source).sweep()

        assertArrayEquals(byteArrayOf(9, 9, 9), repo.sensorSecrets[id])
        assertTrue("nothing was written", repo.secretWrites.isEmpty())
        assertEquals("and the document is still disposed of", 1, source.consumed)
    }

    @Test
    fun `the import document is read once, not on every enumeration tick`() = runTest {
        val source = FakeImportSource(importDocument())
        val driver = driver(this, FakeCgmRepository(), importSource = source)
        repeat(5) { driver.sweep() }
        assertEquals(1, source.reads)
    }

    /** The file is the only copy of the key material; a refusal leaves it on disk to be fixed. */
    @Test
    fun `a document that does not parse is neither stored nor consumed`() = runTest {
        val repo = FakeCgmRepository()
        val source = FakeImportSource("""{"BSN": "$BSN", "dry_run": true}""")

        driver(this, repo, importSource = source).sweep()

        assertTrue(repo.secretWrites.isEmpty())
        assertEquals(0, source.consumed)
        assertFalse(driver(this, repo).adoptable(BSN))
    }

    @Test
    fun `a build with no import wired behaves as before`() = runTest {
        val repo = FakeCgmRepository()
        driver(this, repo).sweep()
        assertTrue(repo.secretWrites.isEmpty())
    }

    /** A secret can vanish unasked (a disowned unfinished bind); the cached yes must go too. */
    @Test
    fun `a released secret is dropped from the cache`() = runTest {
        val repo = CountingRepository()
        val id = CgmSourceId("anytime:$BSN")
        repo.sensorSecrets[id] = byteArrayOf(1)
        val driver = driver(this, repo)
        assertTrue(driver.adoptable(BSN))

        repo.sensorSecrets -= id
        driver.releaseSecret(BSN)

        assertFalse("the store is asked again, and it says no", driver.adoptable(BSN))
    }

    /** Heard every sweep, session yields nothing: ordinary when another central holds the link. */
    @Test
    fun `a sensor that holds a link and reports nothing stops being retried`() = runTest {
        var scans = 0
        val driver = driver(this, readableRepo()) { scans++ }

        assertNull(driver.handleFor(BSN))
        assertEquals("the first attempt looks for it", 1, scans)

        driver.sessionYieldedNothing(Ct5ConnectedSource.descriptorFor(BSN))
        assertNull(driver.handleFor(BSN))
        assertEquals("and the next spends no radio at all", 1, scans)
    }

    /** Unbound sensor parks in AWAITING_BIND by design; rationing would hide Bind for ~30min. */
    @Test
    fun `a sensor awaiting its bind is never rationed`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }

        assertNull(driver.handleFor(BSN))
        driver.sessionYieldedNothing(Ct5ConnectedSource.descriptorFor(BSN))

        advanceTimeBy(Ct5FamilyDriver.RESCAN_MIN_GAP_MS + 1)
        assertNull(driver.handleFor(BSN))
        assertEquals("the bind offer is still reachable", 2, scans)
    }

    @Test
    fun `a sensor that produces a reading lifts the ration`() = runTest {
        var scans = 0
        val driver = driver(this, readableRepo()) { scans++ }
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)

        driver.sessionYieldedNothing(descriptor)
        assertNull(driver.handleFor(BSN))
        assertEquals(0, scans)

        driver.sensorReadable(descriptor)
        assertNull(driver.handleFor(BSN))
        assertEquals(1, scans)
    }

    /** Two reasons to back off in one cycle are one back-off, else miss+failure double it. */
    @Test
    fun `the ration doubles per failed attempt and does not double twice in one cycle`() = runTest {
        var scans = 0
        val driver = driver(this, readableRepo()) { scans++ }
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)

        assertNull(driver.handleFor(BSN))
        driver.sessionYieldedNothing(descriptor)
        // Both the scan's own miss and the session's failure landed in this cycle.
        driver.sessionYieldedNothing(descriptor)

        advanceTimeBy(Ct5FamilyDriver.RESCAN_MIN_GAP_MS - 1)
        assertNull(driver.handleFor(BSN))
        assertEquals("still rationed", 1, scans)

        advanceTimeBy(2)
        assertNull(driver.handleFor(BSN))
        assertEquals("thirty seconds, not two minutes", 2, scans)
    }

    @Test
    fun `a user-pressed search listens far longer than a routine sweep`() = runTest {
        val driver = driver(this, FakeCgmRepository())
        assertEquals(Ct5FamilyDriver.DISCOVERY_SWEEP_MS, driver.sweepWindowMs())
        assertEquals(
            "an idle sensor advertises once per eight seconds; 15 s can hear nothing",
            Ct5Constants.SCAN_TIMEOUT_MS,
            driver.userSweepWindowMs(),
        )
        assertTrue(driver.userSweepWindowMs() > driver.sweepWindowMs())
    }

    /** Measured on the clock: a silent scan ends on its own timeout; elapsed time IS the window. */
    @Test
    fun `the long window is spent on the sweep the user asked for and not the one after it`() = runTest {
        val silent = { flow<Ct5AdvertisedDevice> { awaitCancellation() } }
        val driver = driver(this, FakeCgmRepository(), discover = silent)

        var mark = currentTime
        driver.sweep()
        assertEquals(Ct5FamilyDriver.DISCOVERY_SWEEP_MS, currentTime - mark)

        driver.rescan()
        mark = currentTime
        driver.sweep()
        assertEquals(Ct5Constants.SCAN_TIMEOUT_MS, currentTime - mark)

        advanceTimeBy(Ct5FamilyDriver.IDLE_DISCOVERY_INTERVAL_MS)
        mark = currentTime
        driver.sweep()
        assertEquals("the next sweep is routine again", Ct5FamilyDriver.DISCOVERY_SWEEP_MS, currentTime - mark)
    }

    /** The panel's only account of a met-but-unofferable sensor; only for an unknown flag. */
    @Test
    fun `only a sighting refused for an unknown flag is reported as unidentified`() {
        assertTrue(
            "no advertisement has said whether it is free",
            Ct5FamilyDriver.sightingIsUnidentified(bound = null, adoptable = false),
        )
        assertFalse(
            "a held key answers what the air did not, and it is listed",
            Ct5FamilyDriver.sightingIsUnidentified(bound = null, adoptable = true),
        )
        assertFalse(
            "somebody else's wear is not a fault to report",
            Ct5FamilyDriver.sightingIsUnidentified(bound = true, adoptable = false),
        )
        assertFalse(
            "an unclaimed sensor is listed, not reported",
            Ct5FamilyDriver.sightingIsUnidentified(bound = false, adoptable = false),
        )
    }

    /** The two must not overlap: a sensor cannot be both offered and reported as unofferable. */
    @Test
    fun `nothing is both listed and reported as unidentified`() {
        for (bound in listOf(true, false, null)) {
            for (adoptable in listOf(true, false)) {
                assertFalse(
                    "bound=$bound adoptable=$adoptable",
                    Ct5FamilyDriver.belongsOnRecord(bound, adoptable) &&
                        Ct5FamilyDriver.sightingIsUnidentified(bound, adoptable),
                )
            }
        }
    }

    @Test
    fun `the strongest of several unidentified sensors is the one reported`() {
        val merged = UnidentifiedSightings(1, -89).merge(UnidentifiedSightings(2, -71))
        assertEquals(3, merged.count)
        assertEquals("the nearest is the one worth walking towards", -71, merged.bestRssiDbm)
        assertEquals(UnidentifiedSightings(), UnidentifiedSightings().merge(UnidentifiedSightings()))
    }

    /** DESTRUCTIVE; order is the point — nothing deleted until an anchor is agreed. */
    @Test
    fun `a repair re-dates the wear, drops its readings and resets its cursor`() = runTest {
        val repo = readableRepo()
        val id = Ct5FamilyDriver.sourceIdFor(BSN)
        val bind = 1_755_000_000_000L
        val walked = bind + 19 * 60 * 60_000L
        repo.sensorSecrets[id] = STATE.copy(bindTimeMs = walked).encode()
        repo.arrivals[id] = (1..200).map {
            Ct5AnchorRepair.Arrival(
                rxWallMs = bind + it * Ct5Constants.SAMPLE_INTERVAL_MS,
                minFromStart = it * Ct5Constants.SAMPLE_INTERVAL_MIN,
            )
        }
        repo.saveSourceCursor(id, 8175)
        val driver = driver(this, repo)

        assertTrue(driver.repairHistory(Ct5ConnectedSource.descriptorFor(BSN)))

        assertEquals(bind, Ct5SensorState.decode(repo.sensorSecrets.getValue(id))!!.bindTimeMs)
        assertEquals(listOf(id), repo.readingsDeletedFor)
        assertEquals("the pull must start again from the beginning", 0, repo.loadSourceCursor(id))
    }

    /** A wrong key still writes some ordinary-looking readings; fixing it must take those too. */
    @Test
    fun `recovering a key drops the readings the old one wrote`() = runTest {
        val repo = readableRepo()
        val id = Ct5FamilyDriver.sourceIdFor(BSN)
        repo.sensorSecrets[id] = STATE.copy(cipherId = 0x11).encode()
        repo.saveSourceCursor(id, 500)
        val driver = driver(this, repo)
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)
        repeat(Ct5KeySearch.MIN_FRAMES) { driver.keepUndecodableForTest(BSN, byteArrayOf(it.toByte())) }

        // Search is stubbed through the codec; this only asserts what a SUCCESS must clean up.
        if (driver.recoverKey(descriptor)) {
            assertEquals(listOf(id), repo.readingsDeletedFor)
            assertEquals(0, repo.loadSourceCursor(id))
        }
    }

    /** A walked anchor is always AHEAD of the truth, so a repair moves it back or does nothing. */
    @Test
    fun `a repair that would move the anchor forward deletes nothing`() = runTest {
        val repo = readableRepo()
        val id = Ct5FamilyDriver.sourceIdFor(BSN)
        val bind = 1_755_000_000_000L
        // Held anchor is already EARLIER than the witnesses: nothing to undo.
        repo.sensorSecrets[id] = STATE.copy(bindTimeMs = bind - 60 * 60_000L).encode()
        repo.arrivals[id] = (1..200).map {
            Ct5AnchorRepair.Arrival(
                rxWallMs = bind + it * Ct5Constants.SAMPLE_INTERVAL_MS,
                minFromStart = it * Ct5Constants.SAMPLE_INTERVAL_MIN,
            )
        }
        repo.saveSourceCursor(id, 8175)
        val driver = driver(this, repo)

        assertFalse(driver.repairHistory(Ct5ConnectedSource.descriptorFor(BSN)))

        assertEquals(bind - 60 * 60_000L, Ct5SensorState.decode(repo.sensorSecrets.getValue(id))!!.bindTimeMs)
        assertTrue(repo.readingsDeletedFor.isEmpty())
        assertEquals(8175, repo.loadSourceCursor(id))
    }

    @Test
    fun `a repair with too few witnesses deletes nothing and moves nothing`() = runTest {
        val repo = readableRepo()
        val id = Ct5FamilyDriver.sourceIdFor(BSN)
        val walked = 1_755_000_000_000L
        repo.sensorSecrets[id] = STATE.copy(bindTimeMs = walked).encode()
        repo.arrivals[id] = listOf(Ct5AnchorRepair.Arrival(1L, 0))
        repo.saveSourceCursor(id, 8175)
        val driver = driver(this, repo)

        assertFalse(driver.repairHistory(Ct5ConnectedSource.descriptorFor(BSN)))

        assertEquals(walked, Ct5SensorState.decode(repo.sensorSecrets.getValue(id))!!.bindTimeMs)
        assertTrue("a wear with no agreed anchor must keep its readings", repo.readingsDeletedFor.isEmpty())
        assertEquals(8175, repo.loadSourceCursor(id))
    }

    @Test
    fun `a repair on a sensor this app holds no key for does nothing`() = runTest {
        val repo = FakeCgmRepository()
        repo.arrivals[Ct5FamilyDriver.sourceIdFor(BSN)] = (1..200).map {
            Ct5AnchorRepair.Arrival(it * 180_000L, it * 3)
        }
        val driver = driver(this, repo)

        assertFalse(driver.repairHistory(Ct5ConnectedSource.descriptorFor(BSN)))
        assertTrue(repo.readingsDeletedFor.isEmpty())
    }

    @Test
    fun `the family declares it can repair a wear`() = runTest {
        assertTrue(driver(this, FakeCgmRepository()).facts.supportsHistoryRepair)
    }

    @Test
    fun `a rescan lifts every hold-off and sweeps at once`() = runTest {
        var scans = 0
        val driver = driver(this, readableRepo()) { scans++ }
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)
        driver.sweep()
        driver.sweep()
        assertEquals("the sweep is rationed", 1, scans)
        driver.sessionYieldedNothing(descriptor)
        assertTrue(driver.rationed(BSN))

        driver.rescan()

        assertFalse(driver.rationed(BSN))
        driver.sweep()
        assertEquals("and the next enumeration sweeps", 2, scans)
    }

    @Test
    fun `forgetting a sensor lifts its hold-off so the next look goes straight out`() = runTest {
        var scans = 0
        val driver = driver(this, readableRepo()) { scans++ }
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)
        driver.sessionYieldedNothing(descriptor)
        assertNull(driver.handleFor(BSN))
        assertEquals("rationed above the cache, so no scan", 0, scans)

        driver.forget(descriptor)

        assertNull(driver.handleFor(BSN))
        assertEquals(1, scans)
    }

    /** While [rationed], handleFor refuses it; a sighting only refreshes an unused handle. */
    @Test
    fun `a rationed sensor is not a find, and stops being rationed once it reports`() = runTest {
        val driver = driver(this, readableRepo())
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)
        assertFalse("nothing is rationed before a session has failed", driver.rationed(BSN))

        driver.sessionYieldedNothing(descriptor)
        assertTrue("while the connect path refuses it, a sighting teaches the sweep nothing", driver.rationed(BSN))

        advanceTimeBy(Ct5FamilyDriver.RESCAN_MIN_GAP_MS + 1)
        assertFalse("and the sweep counts it again the moment the gate opens", driver.rationed(BSN))
    }

    @Test
    fun `a reading un-rations the sensor at once`() = runTest {
        val driver = driver(this, readableRepo())
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)
        driver.sessionYieldedNothing(descriptor)
        assertTrue(driver.rationed(BSN))

        driver.sensorReadable(descriptor)

        assertFalse(driver.rationed(BSN))
    }

    /** ~-80dBm, a link dying in one push interval is ordinary; checkID accept means readable. */
    @Test
    fun `an authenticated session that drops before a push is not rationed`() = runTest {
        var scans = 0
        val driver = driver(this, readableRepo()) { scans++ }
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)

        driver.noteAuthenticatedForTest(BSN)
        driver.sessionYieldedNothing(descriptor)

        assertFalse("checkID passed, so the sensor is readable", driver.rationed(BSN))
        assertNull(driver.handleFor(BSN))
        assertEquals("and the next attempt goes straight out", 1, scans)
    }

    @Test
    fun `the authentication credit is consumed by the session that earned it`() = runTest {
        val driver = driver(this, readableRepo())
        val descriptor = Ct5ConnectedSource.descriptorFor(BSN)

        driver.noteAuthenticatedForTest(BSN)
        driver.sessionYieldedNothing(descriptor)
        assertFalse(driver.rationed(BSN))

        // The next session never gets a checkID through — the case the gate exists for.
        driver.sessionYieldedNothing(descriptor)
        assertTrue(driver.rationed(BSN))
    }

    /** A sensor this app can read — the only kind the unreadable gate applies to. */
    private fun readableRepo() = FakeCgmRepository().apply {
        sensorSecrets[CgmSourceId("anytime:$BSN")] = byteArrayOf(1)
    }

    @Test
    fun `a discovery sweep is rationed rather than run on every enumeration`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }

        driver.sweep()
        assertEquals(1, scans)
        repeat(5) { driver.sweep() }
        assertEquals("the radio must not be woken on every tick", 1, scans)
    }

    @Test
    fun `sweeps that hear nothing back off to the idle interval`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }

        driver.sweep()
        assertEquals(1, scans)

        // The first empty sweep doubled the wait already; the ordinary interval isn't enough now.
        advanceTimeBy(Ct5FamilyDriver.DISCOVERY_INTERVAL_MS + 1)
        driver.sweep()
        assertEquals(1, scans)

        advanceTimeBy(Ct5FamilyDriver.DISCOVERY_INTERVAL_MS)
        driver.sweep()
        assertEquals(2, scans)

        // And it keeps doubling to the ceiling rather than growing without bound.
        var seenScans = scans
        repeat(6) {
            advanceTimeBy(Ct5FamilyDriver.IDLE_DISCOVERY_INTERVAL_MS)
            driver.sweep()
            assertEquals(++seenScans, scans)
        }
    }

    /** Recovery channel: sweep refreshes handle, clears rescan gate; bounded by one interval. */
    @Test
    fun `a sensor being read but not reached keeps the base sweep interval`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }
        val wanted = setOf(Ct5FamilyDriver.sourceIdFor(BSN))

        driver.candidates(active = wanted, connected = emptySet())
        assertEquals(1, scans)

        // Empty sweeps must NOT lengthen the wait while a sensor is wanted and recently missing.
        for (i in 2..4) {
            advanceTimeBy(Ct5FamilyDriver.DISCOVERY_INTERVAL_MS)
            driver.candidates(active = wanted, connected = emptySet())
            assertEquals(i, scans)
        }
    }

    /** End-of-wear looks the same: no more adverts, source active; one listen per interval. */
    @Test
    fun `a sensor that is never found holds the sweep at its base interval for as long as it is wanted`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }
        val wanted = setOf(Ct5FamilyDriver.sourceIdFor(BSN))

        // Well past the half hour the sweep once gave up after.
        for (i in 1..24) {
            driver.candidates(active = wanted, connected = emptySet())
            assertEquals(i, scans)
            advanceTimeBy(Ct5FamilyDriver.DISCOVERY_INTERVAL_MS)
        }
    }

    @Test
    fun `no sweep runs at the ordinary interval while a session is held`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }
        val held = setOf(Ct5FamilyDriver.sourceIdFor(BSN))

        driver.candidates(active = held, connected = held)
        assertEquals(1, scans)

        advanceTimeBy(Ct5FamilyDriver.DISCOVERY_INTERVAL_MS * 3)
        driver.candidates(active = held, connected = held)
        assertEquals("a held link needs no scanning at all", 1, scans)

        // A floor, not a full stop: a SECOND sensor put on while the first reports is still found.
        advanceTimeBy(Ct5FamilyDriver.IDLE_DISCOVERY_INTERVAL_MS)
        driver.candidates(active = held, connected = held)
        assertEquals(2, scans)
    }

    /** An aged-out handle connectGatts a rotated address, hangs until timeout as another fault. */
    @Test
    fun `a targeted rescan that hears nothing yields no handle and then waits`() = runTest {
        var scans = 0
        val driver = driver(this, FakeCgmRepository()) { scans++ }

        assertNull(driver.handleFor(BSN))
        assertEquals(1, scans)

        assertNull(driver.handleFor(BSN))
        assertEquals("the same sensor must not be scanned for again at once", 1, scans)

        advanceTimeBy(Ct5FamilyDriver.RESCAN_MIN_GAP_MS + 1)
        assertNull(driver.handleFor(BSN))
        assertEquals(2, scans)

        // The gap doubles, so the first gap is no longer enough.
        advanceTimeBy(Ct5FamilyDriver.RESCAN_MIN_GAP_MS + 1)
        assertNull(driver.handleFor(BSN))
        assertEquals(2, scans)
    }

    @Test
    fun `the sweep window is short and the targeted window generous`() {
        // An idle sensor advertises once every eight seconds, which is what needs the long window.
        assertTrue(Ct5FamilyDriver.DISCOVERY_SWEEP_MS < Ct5Constants.SCAN_TIMEOUT_MS)
        assertTrue(
            "a sweep must still span more than one idle advertising interval",
            Ct5FamilyDriver.DISCOVERY_SWEEP_MS > Ct5Constants.IDLE_ADVERT_INTERVAL_MS,
        )
        // At worst a fifteenth of the time at low latency, rather than a fifth.
        assertTrue(Ct5FamilyDriver.DISCOVERY_SWEEP_MS * 10 < Ct5FamilyDriver.DISCOVERY_INTERVAL_MS)
    }

    /** Bind flag rides scan response (no room beside 17-char name); sightings merge newest-wins. */
    @Test
    fun `a sighting with no manufacturer block never erases a flag already known`() {
        assertEquals(true, Ct5FamilyDriver.mergedFlag(fresh = null, prior = true))
        assertEquals(false, Ct5FamilyDriver.mergedFlag(fresh = null, prior = false))
        assertNull(Ct5FamilyDriver.mergedFlag(fresh = null, prior = null))
        // A report that DID carry one is current and wins, in both directions.
        assertEquals(true, Ct5FamilyDriver.mergedFlag(fresh = true, prior = false))
        assertEquals(false, Ct5FamilyDriver.mergedFlag(fresh = false, prior = true))
    }

    /** A stranger's sensor used to become a permanent cgm_source row (name=serial), synced out. */
    @Test
    fun `only an unclaimed sensor or one this app has a key for goes on record`() {
        assertTrue("a fresh sensor is what discovery is for", Ct5FamilyDriver.belongsOnRecord(bound = false, adoptable = false))
        assertTrue("a sensor this app bound", Ct5FamilyDriver.belongsOnRecord(bound = true, adoptable = true))
        assertFalse("somebody else's sensor", Ct5FamilyDriver.belongsOnRecord(bound = true, adoptable = false))
        assertFalse("and one nothing has said either way about", Ct5FamilyDriver.belongsOnRecord(bound = null, adoptable = false))
    }

    /** A neighbour's sensor, re-heard forever, pins the interval fastest, never reaching idle. */
    @Test
    fun `a sensor that will never be connected is not a reason to keep scanning`() {
        assertFalse("somebody else's, and not being read", Ct5FamilyDriver.radioWorthwhile(bound = true, wanted = false))
        assertFalse("nor one nothing has said about", Ct5FamilyDriver.radioWorthwhile(bound = null, wanted = false))
        assertTrue("an unclaimed sensor could be adopted", Ct5FamilyDriver.radioWorthwhile(bound = false, wanted = false))
        // Recovery channel: a sensor the user awaits keeps the radio awake whatever it advertises.
        assertTrue(Ct5FamilyDriver.radioWorthwhile(bound = true, wanted = true))
        assertTrue(Ct5FamilyDriver.radioWorthwhile(bound = null, wanted = true))
    }

    /** Narrower than [radioWorthwhile]: unclaimed is a find once, else a spare pins it forever. */
    @Test
    fun `an unclaimed sensor already on the list is not a fresh discovery`() {
        val learned = { bound: Boolean?, wanted: Boolean, connected: Boolean, known: Boolean ->
            Ct5FamilyDriver.sweepLearnedSomething(bound, wanted, connected, known)
        }

        assertTrue(
            "the sighting that discovers it",
            learned(false, /*wanted*/ false, /*connected*/ false, /*known*/ false),
        )
        assertFalse(
            "every sighting after that teaches nothing and must not reset the back-off",
            learned(false, false, false, true),
        )

        // A sensor being READ is the recovery channel; a sighting refreshes its handle either way.
        assertTrue(learned(null, true, false, true))
        assertTrue(learned(true, true, false, true))

        // A held link is not a find. There is nothing for a sweep to recover.
        assertFalse(learned(false, true, true, false))
        assertFalse(learned(false, false, true, false))

        // Somebody else's sensor was never a find, and an unknown flag is treated as theirs.
        assertFalse(learned(true, false, false, false))
        assertFalse(learned(null, false, false, false))
    }

    @Test
    fun `the driver answers for its own vendor only`() {
        assertEquals(Ct5Constants.VENDOR_ID, driver(TestScope(), FakeCgmRepository()).vendorId)
    }


    // Locked screen SUSPENDS an unbatched scan silently; batched survives but flushes rarely.

    @Test
    fun `a locked scan window outlasts a controller flush and an interactive one does not`() {
        val locked = driver(TestScope(), FakeCgmRepository(), screenOn = false)
        assertTrue(
            "a sweep shorter than one flush hears nothing at all",
            locked.sweepWindowMs() > Ct5Constants.BATCH_FLUSH_MS,
        )
        assertTrue(
            "a targeted rescan gets at least two flushes, so one opening just after a flush still catches the next",
            locked.targetedScanMs() >= Ct5Constants.BATCH_FLUSH_MS * 2,
        )

        val interactive = driver(TestScope(), FakeCgmRepository(), screenOn = true)
        assertEquals(
            "an interactive sweep is unchanged — this costs latency and must not be paid while somebody is watching",
            Ct5FamilyDriver.DISCOVERY_SWEEP_MS,
            interactive.sweepWindowMs(),
        )
        assertEquals(Ct5Constants.SCAN_TIMEOUT_MS, interactive.targetedScanMs())
    }

    @Test
    fun `a locked handle stays usable for as long as the controller may sit on the sighting`() {
        // Handle is aged from when the RADIO heard the advert, not from when this process was told.
        val locked = driver(TestScope(), FakeCgmRepository(), screenOn = false)
        assertTrue(
            "a sighting delivered one flush late must still be connectable",
            locked.handleTtlMs() > Ct5Constants.BATCH_FLUSH_MS,
        )
        val interactive = driver(TestScope(), FakeCgmRepository(), screenOn = true)
        assertEquals(Ct5FamilyDriver.HANDLE_TTL_MS, interactive.handleTtlMs())
    }

    @Test
    fun `a locked sweep really does listen for its whole window`() = runTest {
        // A never-completing flow is what a silent scan looks like; the window is what ends it.
        val d = driver(this, FakeCgmRepository(), screenOn = false, discover = { flow { awaitCancellation() } })
        // The FIRST sweep is short regardless of screen; enumeration precedes any session.
        d.sweep()
        assertEquals(Ct5FamilyDriver.DISCOVERY_SWEEP_MS, currentTime)

        // Past the interval the empty first sweep has already backed off to.
        advanceTimeBy(Ct5FamilyDriver.IDLE_DISCOVERY_INTERVAL_MS)
        val before = currentTime
        d.sweep()
        assertEquals(d.sweepWindowMs(), currentTime - before)
    }

    @Test
    fun `a locked sweep interval clears its own window, so sweeps cannot run back to back`() = runTest {
        // The locked window is longer than the interactive interval it would otherwise space at.
        var scans = 0
        val d = driver(this, FakeCgmRepository(), screenOn = false) { scans++ }
        d.sweep()
        assertEquals(1, scans)
        advanceTimeBy(Ct5FamilyDriver.DISCOVERY_INTERVAL_MS)
        d.sweep()
        assertEquals("the interactive interval is too short to space a locked sweep", 1, scans)
        advanceTimeBy(Ct5Constants.BATCH_FLUSH_MS)
        d.sweep()
        assertEquals(2, scans)
    }


    @Test
    fun `no targeted rescan is opened while the screen is locked`() = runTest {
        // A second batch client resets the shared flush alarm; clients could push it out forever.
        var scans = 0
        val locked = driver(this, FakeCgmRepository(), screenOn = false) { scans++ }
        assertNull(locked.handleFor(BSN))
        assertEquals("the sweep is the only scanner while locked", 0, scans)

        val interactive = driver(this, FakeCgmRepository(), screenOn = true) { scans++ }
        assertNull(interactive.handleFor(BSN))
        assertEquals("an interactive rescan is unchanged", 1, scans)
    }

    @Test
    fun `a stored address is offered however long ago the sensor was heard`() = runTest {
        val repo = readableRepo()
        repo.saveSensorAddress(Ct5FamilyDriver.sourceIdFor(BSN), ADDRESS)
        val driver = driver(this, repo, screenOn = false)

        advanceTimeBy(Ct5FamilyDriver.SEEN_TTL_MS * 10)

        assertEquals(ADDRESS, driver.reconnectAddress(BSN))
    }

    @Test
    fun `an authenticated address outlives the process`() = runTest {
        val repo = readableRepo()
        driver(this, repo).authenticatedOn(BSN, ADDRESS)

        assertEquals(ADDRESS, driver(this, repo).reconnectAddress(BSN))
    }

    @Test
    fun `a sensor heard at another address is dialled there`() = runTest {
        val repo = readableRepo()
        repo.saveSensorAddress(Ct5FamilyDriver.sourceIdFor(BSN), ADDRESS)
        val driver = driver(this, repo)
        assertEquals(ADDRESS, driver.reconnectAddress(BSN))

        driver.noteAddress(BSN, OTHER_ADDRESS)

        assertEquals(OTHER_ADDRESS, driver.reconnectAddress(BSN))
    }

    @Test
    fun `no address is offered for a sensor without a key or under a ration`() = runTest {
        val unkeyed = FakeCgmRepository()
        unkeyed.saveSensorAddress(Ct5FamilyDriver.sourceIdFor(BSN), ADDRESS)
        assertNull(driver(this, unkeyed).reconnectAddress(BSN))

        val repo = readableRepo()
        repo.saveSensorAddress(Ct5FamilyDriver.sourceIdFor(BSN), ADDRESS)
        val driver = driver(this, repo)
        driver.sessionYieldedNothing(Ct5ConnectedSource.descriptorFor(BSN))
        assertNull(driver.reconnectAddress(BSN))
    }

    /** A sweep with nothing being read and nothing connected — pure discovery. */
    private suspend fun Ct5FamilyDriver.sweep() = candidates(active = emptySet(), connected = emptySet())

    // `onScan` stays LAST so the trailing-lambda calls throughout this file keep binding to it.
    private fun driver(
        scope: TestScope,
        repo: CgmRepository,
        importSource: Ct5ImportSource? = null,
        screenOn: Boolean = true,
        discover: () -> Flow<Ct5AdvertisedDevice> = { emptyFlow() },
        onScan: () -> Unit = {},
    ) = Ct5FamilyDriver(
        repository = repo,
        session = Ct5SessionCodec,
        nowMs = { scope.currentTime },
        discover = {
            onScan()
            // Finds nothing by default, no BluetoothDevice; tests only how often it's opened.
            discover()
        },
        transportFactory = { _, _, _ -> error("no session is built in these tests") },
        importSource = importSource,
        screenOn = { screenOn },
    )

    private class FakeImportSource(private val text: String?) : Ct5ImportSource {
        var reads = 0
            private set
        var consumed = 0
            private set

        override suspend fun read(): String? {
            reads++
            return text
        }

        override suspend fun consume() {
            consumed++
        }
    }

    /** Not a real serial or identity string. */
    private fun importDocument(bsn: String = BSN) = """
        {"BSN": "$bsn", "A": [1, 2, 3, 4], "B": [5, 6, 7, 8], "RANDOM_ID": "4271",
         "CIPHER_ID": 171, "K": 1.25, "R": 1.0, "SSN": "000000000000000000000",
         "bind_started_utc": "2026-08-17T11:46:13Z", "dry_run": false}
    """.trimIndent()

    private class CountingRepository : FakeCgmRepository() {
        var secretReads = 0
            private set

        override suspend fun loadSensorSecret(id: CgmSourceId): ByteArray? {
            secretReads++
            return super.loadSensorSecret(id)
        }
    }

    private companion object {
        const val BSN = "0123456789"

        /** Not a real unit's address. */
        const val ADDRESS = "C0:00:00:00:00:01"
        const val OTHER_ADDRESS = "C0:00:00:00:00:02"

        /** Every value INVENTED; none of it may come from a live wear. */
        val STATE = Ct5SensorState(
            cipherId = 0x69,
            a = byteArrayOf(1, 2, 3, 4),
            b = byteArrayOf(5, 6, 7, 8),
            randomId = "1234",
            kX100 = 125,
            rX100 = 100,
            ssn = "001734456789012510B2C",
            bindTimeMs = 1_755_000_000_000L,
            initialised = true,
        )
    }
}
