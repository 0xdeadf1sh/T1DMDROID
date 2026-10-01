package com.t1dm.feature.cgm

import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CgmPanelMappingTest {

    /** Every status in which the sensor is linked and answering. */
    private val RUNNING = setOf(CgmSourceStatus.Live, CgmSourceStatus.Warmup, CgmSourceStatus.Faulted)

    private fun source(
        id: String,
        display: String = id,
        vendor: String = "aidexx",
        ordinal: Int = 0,
        hidden: Boolean = false,
        warmupMin: Int = 60,
    ) = CgmSourceDescriptor(
        id = CgmSourceId(id),
        vendorId = vendor,
        sensorModelId = "$vendor:model",
        advertName = display,
        displayName = display,
        serialSuffix = id.substringAfter(':'),
        warmupWindowMin = warmupMin,
        passiveOnly = false,
        hidden = hidden,
        ordinal = ordinal,
    )

    private fun state(
        sources: List<CgmSourceDescriptor>,
        authoritativeId: String? = null,
        activeIds: Set<String> = emptySet(),
        admittedIds: Set<String> = activeIds,
        live: Map<String, CgmSensorLive> = emptyMap(),
        maxSessions: Int = 4,
        scanning: Boolean = false,
        unidentified: Int = 0,
        unidentifiedRssiDbm: Int? = null,
    ) = cgmPanelState(
        sources = sources,
        authoritativeId = authoritativeId,
        activeIds = activeIds,
        admittedIds = admittedIds,
        live = live,
        maxSessions = maxSessions,
        scanning = scanning,
        unidentified = unidentified,
        unidentifiedRssiDbm = unidentifiedRssiDbm,
    )

    @Test
    fun `each sensor carries its own state, signal and telemetry`() {
        val s = state(
            sources = listOf(source("a:1", ordinal = 0), source("b:2", vendor = "vendor2", ordinal = 1)),
            authoritativeId = "a:1",
            activeIds = setOf("a:1", "b:2"),
            live = mapOf(
                "a:1" to CgmSensorLive(status = CgmSourceStatus.Live, rssiDbm = -55),
                "b:2" to CgmSensorLive(
                    status = CgmSourceStatus.Warmup,
                    rssiDbm = -88,
                    telemetry = CgmSourceTelemetry(sampledAtMs = 1_000L, tempCx100 = 3104),
                ),
            ),
        )
        assertEquals(2, s.sensors.size)
        assertEquals(CgmSourceStatus.Live, s.sensors[0].status)
        assertEquals(-55, s.sensors[0].rssiDbm)
        assertNull("a source that reports nothing must not borrow the other's", s.sensors[0].telemetry)
        assertEquals(CgmSourceStatus.Warmup, s.sensors[1].status)
        assertEquals(-88, s.sensors[1].rssiDbm)
        assertEquals(3104, s.sensors[1].telemetry?.tempCx100)
    }

    @Test
    fun `a session's failure note rides onto the row and absent stays absent`() {
        val failing = state(
            sources = listOf(source("a:1", ordinal = 0)),
            authoritativeId = "a:1",
            activeIds = setOf("a:1"),
            live = mapOf("a:1" to CgmSensorLive(failureNote = "tables: runtime table missing: x")),
        )
        assertEquals("tables: runtime table missing: x", failing.sensors.single().failureNote)

        val quiet = state(
            sources = listOf(source("a:1", ordinal = 0)),
            authoritativeId = "a:1",
            activeIds = setOf("a:1"),
            live = mapOf("a:1" to CgmSensorLive(status = CgmSourceStatus.Live)),
        )
        assertNull("a reading sensor names no failure", quiet.sensors.single().failureNote)
    }

    @Test
    fun `exactly one row is authoritative, and it is also active`() {
        val s = state(
            sources = listOf(source("a:1"), source("b:2")),
            authoritativeId = "b:2",
            activeIds = emptySet(),
        )
        assertEquals(listOf(false, true), s.sensors.map { it.authoritative })
        assertTrue("authority implies the app is reading it", s.sensors[1].active)
        assertFalse(s.sensors[0].active)
    }

    @Test
    fun `a sensor with no session is idle, silent and offered nothing`() {
        val row = state(sources = listOf(source("a:1"))).sensors.single()
        assertEquals(CgmSourceStatus.Idle, row.status)
        assertNull(row.rssiDbm)
        assertNull(row.telemetry)
        assertNull(row.sensorAgeMin)
        assertNull(row.expiryMs)
        assertFalse(row.canBind)
        assertFalse(row.canActivate)
    }

    @Test
    fun `a removed sensor drops off the list`() {
        val s = state(sources = listOf(source("a:1"), source("b:2", hidden = true)))
        assertEquals(listOf("a:1"), s.sensors.map { it.id })
    }

    @Test
    fun `the authoritative sensor is listed even if it is marked removed`() {
        val s = state(sources = listOf(source("a:1", hidden = true)), authoritativeId = "a:1")
        assertEquals(listOf("a:1"), s.sensors.map { it.id })
    }

    @Test
    fun `the list keeps the order the phone met the sensors in`() {
        val s = state(sources = listOf(source("c:3"), source("a:1"), source("b:2")))
        assertEquals(listOf("c:3", "a:1", "b:2"), s.sensors.map { it.id })
    }

    /** Warmup looks safe and is not: re-activating there restarts the sensor's clock. */
    @Test
    fun `activation is refused wherever a session is already counting`() {
        val supports = CgmSensorLive(supportsActivate = true)
        fun canActivate(st: CgmSourceStatus) = state(
            sources = listOf(source("a:1")),
            live = mapOf("a:1" to supports.copy(status = st)),
        ).sensors.single().canActivate

        assertTrue(canActivate(CgmSourceStatus.Idle))
        assertTrue(canActivate(CgmSourceStatus.Scanning))
        assertTrue(canActivate(CgmSourceStatus.SignalLost))
        assertFalse(canActivate(CgmSourceStatus.Live))
        assertFalse(canActivate(CgmSourceStatus.Warmup))
    }

    @Test
    fun `a running search is carried to the panel`() {
        assertFalse(state(sources = emptyList()).scanning)
        assertTrue(state(sources = emptyList(), scanning = true).scanning)
    }

    /** Provisioning is a family fact, so the control draws wherever the family claims it. */
    @Test
    fun `provision is drawn wherever the family claims it`() {
        val no = state(sources = listOf(source("a:1"))).sensors.single()
        assertFalse(no.hasProvision)
        assertFalse(no.canProvision)
        val yes = state(
            sources = listOf(source("a:1")),
            live = mapOf("a:1" to CgmSensorLive(supportsProvision = true)),
        ).sensors.single()
        assertTrue(yes.hasProvision)
        assertTrue(yes.canProvision)
    }

    /** Sensor heard, not offerable, has no row; count is the panel's only account of it. */
    @Test
    fun `sensors heard without a bind flag are carried to the panel with their signal`() {
        val none = state(sources = emptyList())
        assertEquals(0, none.unidentified)
        assertNull(none.unidentifiedRssiDbm)

        val heard = state(sources = emptyList(), unidentified = 2, unidentifiedRssiDbm = -89)
        assertEquals(2, heard.unidentified)
        assertEquals(-89, heard.unidentifiedRssiDbm)
        assertTrue("and none of them is a row", heard.sensors.isEmpty())
    }

    @Test
    fun `reconnect is offered on a sensor being read with no link held`() {
        fun canReconnect(st: CgmSourceStatus, active: Boolean) = state(
            sources = listOf(source("a:1")),
            activeIds = if (active) setOf("a:1") else emptySet(),
            live = mapOf("a:1" to CgmSensorLive(status = st)),
        ).sensors.single().canReconnect

        assertTrue(canReconnect(CgmSourceStatus.SignalLost, active = true))
        assertTrue(canReconnect(CgmSourceStatus.Scanning, active = true))
        assertTrue(canReconnect(CgmSourceStatus.Idle, active = true))
        assertFalse(canReconnect(CgmSourceStatus.Live, active = true))
        assertFalse(canReconnect(CgmSourceStatus.Warmup, active = true))
        assertFalse(canReconnect(CgmSourceStatus.Faulted, active = true))
        assertFalse("not being read, so nothing to rebuild", canReconnect(CgmSourceStatus.SignalLost, active = false))
    }

    /** Reads raw advert store, no session needed; unreachable sensor can still be re-dated. */
    @Test
    fun `repairing a wear is offered in every state, for a family that can do it`() {
        for (st in CgmSourceStatus.entries) {
            val row = state(
                sources = listOf(source("a:1")),
                live = mapOf("a:1" to CgmSensorLive(status = st, supportsHistoryRepair = true)),
            ).sensors.single()
            assertTrue("$st dropped the control", row.hasRepairHistory)
            assertTrue("$st refused it", row.canRepairHistory)
        }
        val none = state(
            sources = listOf(source("a:1")),
            live = mapOf("a:1" to CgmSensorLive(status = CgmSourceStatus.Live)),
        ).sensors.single()
        assertFalse(none.hasRepairHistory)
        assertFalse(none.canRepairHistory)
    }

    /** Drawn only where the app has actually failed to decode this sensor. */
    @Test
    fun `key recovery is offered exactly when the family holds records it cannot decode`() {
        for (st in CgmSourceStatus.entries) {
            val offered = state(
                sources = listOf(source("a:1")),
                live = mapOf("a:1" to CgmSensorLive(status = st, keyRecoverable = true)),
            ).sensors.single()
            assertTrue("$st dropped the control", offered.hasRecoverKey)
            assertTrue("$st refused it", offered.canRecoverKey)

            val none = state(
                sources = listOf(source("a:1")),
                live = mapOf("a:1" to CgmSensorLive(status = st)),
            ).sensors.single()
            assertFalse("$st drew a key search on a sensor that decodes", none.hasRecoverKey)
            assertFalse("$st offered one", none.canRecoverKey)
        }
    }

    @Test
    fun `history is drawn for a family that keeps a store and offered only with a session established`() {
        for (st in CgmSourceStatus.entries) {
            val row = state(
                sources = listOf(source("a:1")),
                live = mapOf("a:1" to CgmSensorLive(status = st, supportsHistory = true)),
            ).sensors.single()
            assertTrue("$st dropped the control", row.hasHistory)
            assertEquals("$st", RUNNING.contains(st), row.canFetchHistory)
        }
        val none = state(
            sources = listOf(source("a:1")),
            live = mapOf("a:1" to CgmSensorLive(status = CgmSourceStatus.Live)),
        ).sensors.single()
        assertFalse(none.hasHistory)
        assertFalse(none.canFetchHistory)
    }

    /** A sensor whose records the app refuses is RUNNING: linked, authenticated and answering. */
    @Test
    fun `a faulted sensor keeps a running sensor's controls and is offered no reconnect`() {
        val row = state(
            sources = listOf(source("a:1")),
            activeIds = setOf("a:1"),
            live = mapOf(
                "a:1" to CgmSensorLive(
                    status = CgmSourceStatus.Faulted,
                    supportsHistory = true,
                ),
            ),
        ).sensors.single()

        assertTrue("its own store is what a fault is diagnosed from", row.canFetchHistory)
        assertFalse("the link is up; there is nothing to rebuild", row.canReconnect)
    }

    @Test
    fun `a family with no activation frame is never offered one, in any state`() {
        for (st in CgmSourceStatus.entries) {
            val row = state(
                sources = listOf(source("a:1")),
                live = mapOf("a:1" to CgmSensorLive(status = st)),
            ).sensors.single()
            assertFalse("$st offered an activation frame", row.hasActivate)
            assertFalse("$st offered activation", row.canActivate)
        }
    }

    /** Greyed rather than gone; hence two flags and not one. */
    @Test
    fun `a family that has the frame offers it in every state, enabled or not`() {
        for (st in CgmSourceStatus.entries) {
            val row = state(
                sources = listOf(source("a:1")),
                live = mapOf(
                    "a:1" to CgmSensorLive(status = st, supportsActivate = true),
                ),
            ).sensors.single()
            assertTrue("$st withdrew the activation frame", row.hasActivate)
        }
    }

    @Test
    fun `bind is offered exactly when the session says the sensor is unclaimed`() {
        val rows = state(
            sources = listOf(source("a:1"), source("b:2")),
            live = mapOf(
                "a:1" to CgmSensorLive(bindable = true),
                "b:2" to CgmSensorLive(bindable = false),
            ),
        ).sensors
        assertTrue(rows[0].canBind)
        assertFalse(rows[1].canBind)
    }

    @Test
    fun `an active sensor the budget cannot carry is marked as waiting`() {
        val s = state(
            sources = listOf(source("a:1"), source("b:2")),
            activeIds = setOf("a:1", "b:2"),
            admittedIds = setOf("a:1"),
            maxSessions = 1,
        )
        assertTrue(s.sensors[0].admitted)
        assertFalse(s.sensors[1].admitted)
        assertEquals(1, s.maxSessions)
    }

    /** An empty admitted set means "not yet published", not "everything held back". */
    @Test
    fun `nothing is reported as waiting while the budget is not oversubscribed`() {
        val s = state(
            sources = listOf(source("a:1"), source("b:2")),
            activeIds = setOf("a:1", "b:2"),
            admittedIds = emptySet(),
            maxSessions = 4,
        )
        assertTrue(s.sensors.all { it.admitted })
    }

    @Test
    fun `past the budget the admitted set is what decides`() {
        val s = state(
            sources = listOf(source("a:1"), source("b:2"), source("c:3")),
            activeIds = setOf("a:1", "b:2", "c:3"),
            admittedIds = setOf("c:3", "a:1"),
            maxSessions = 2,
        )
        assertEquals(listOf(true, false, true), s.sensors.map { it.admitted })
    }

    @Test
    fun `an inactive sensor is not reported as waiting for a link`() {
        val s = state(
            sources = listOf(source("a:1")),
            activeIds = emptySet(),
            admittedIds = emptySet(),
        )
        assertTrue(s.sensors.single().admitted)
    }

    @Test
    fun `expiry runs off each sensor's own reading and its own wear`() {
        val day = 86_400_000L
        val s = state(
            sources = listOf(source("a:1"), source("b:2")),
            live = mapOf(
                "a:1" to CgmSensorLive(sensorAgeMin = 6 * 1440, readingTsMs = 10 * day, lifetimeMin = 15 * 1440),
                "b:2" to CgmSensorLive(sensorAgeMin = 1440, readingTsMs = 10 * day, lifetimeMin = 14 * 1440),
            ),
        )
        assertEquals(10 * day - 6 * day + 15 * day, s.sensors[0].expiryMs)
        assertEquals(10 * day - 1 * day + 14 * day, s.sensors[1].expiryMs)
        assertEquals(6 * 1440, s.sensors[0].sensorAgeMin)
    }

    @Test
    fun `an unknown sensor age withholds the countdown rather than assuming a start`() {
        val s = state(
            sources = listOf(source("a:1"), source("b:2")),
            live = mapOf(
                "a:1" to CgmSensorLive(sensorAgeMin = null, readingTsMs = 1_000L, lifetimeMin = 1440),
                "b:2" to CgmSensorLive(sensorAgeMin = 30, readingTsMs = null, lifetimeMin = 1440),
            ),
        )
        assertNull(s.sensors[0].expiryMs)
        assertNull(s.sensors[1].expiryMs)
    }

    @Test
    fun `an unknown wear withholds the countdown rather than assuming one`() {
        val s = state(
            sources = listOf(source("a:1")),
            live = mapOf("a:1" to CgmSensorLive(sensorAgeMin = 30, readingTsMs = 1_000L, lifetimeMin = null)),
        )
        assertNull(s.sensors.single().expiryMs)
    }


    @Test
    fun `a card names the sensor and states the number the rest of the app uses`() {
        val row = state(sources = listOf(source("a:1", display = "AiDEX X 22222C74D9", ordinal = 2)))
            .sensors.single()
        assertEquals("AiDEX X 22222C74D9", row.name)
        assertEquals("CGM #2", row.ordinalLabel)
    }

    @Test
    fun `a rated cycle is a fact the family states, and absent where it states none`() {
        val s = state(
            sources = listOf(source("a:1"), source("b:2", vendor = "vendor2")),
            live = mapOf(
                "a:1" to CgmSensorLive(ratedCycleDays = null),
                "b:2" to CgmSensorLive(ratedCycleDays = 16),
            ),
        )
        assertNull(s.sensors[0].ratedCycleDays)
        assertEquals(16, s.sensors[1].ratedCycleDays)
    }

    @Test
    fun `each row carries its own warm-up window`() {
        val s = state(
            sources = listOf(source("a:1", warmupMin = 60), source("b:2", warmupMin = 45)),
            authoritativeId = "b:2",
        )
        assertEquals(60, s.sensors[0].warmupWindowMin)
        assertEquals(45, s.sensors.first { it.authoritative }.warmupWindowMin)
    }

    @Test
    fun `an empty set yields no rows and no invented globals`() {
        val s = state(sources = emptyList())
        assertTrue(s.sensors.isEmpty())
        assertNull(s.lastError)
    }
}
