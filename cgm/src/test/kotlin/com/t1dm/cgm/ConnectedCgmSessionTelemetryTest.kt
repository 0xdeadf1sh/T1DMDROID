package com.t1dm.cgm

import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** null means cannot report, not reports zero; asserted at the seam, not left to each family. */
class ConnectedCgmSessionTelemetryTest {

    @Test
    fun `a session that overrides nothing reports no telemetry at all`() {
        val session = GlucoseOnlySession()
        assertNull(session.telemetry.value)
        // One shared flow, not one per session: a StateFlow fixed at null has no state to keep.
        assertSame(NO_TELEMETRY, GlucoseOnlySession().telemetry)
        assertSame(NO_TELEMETRY, session.telemetry)
    }

    /** Bind command is a defaulted no-op; this flag is the only thing withholding control. */
    @Test
    fun `a session that overrides nothing offers no bind`() {
        val session = GlucoseOnlySession()
        assertFalse(session.bindable.value)
        assertSame(NOT_BINDABLE, session.bindable)
        assertSame(NOT_BINDABLE, GlucoseOnlySession().bindable)
    }

    /** Asserted on the DEFAULT a new family gets unwritten; forgetting to declare must withhold. */
    @Test
    fun `a family that declares nothing is offered nothing`() {
        val facts = CgmFamilyFacts()
        assertNull(facts.ratedCycleDays)
        assertFalse(facts.supportsActivate)
        assertEquals(facts, NO_FAMILY_FACTS)
        assertTrue("an undeclared family must offer nothing", NO_FAMILY_FACTS == CgmFamilyFacts())
    }

    private class GlucoseOnlySession : ConnectedCgmSession {
        override val descriptor = CgmSourceDescriptor(
            id = CgmSourceId("vendor:1"),
            vendorId = "vendor",
            sensorModelId = "vendor:model",
            advertName = null,
            displayName = "Sensor",
            serialSuffix = "1",
            warmupWindowMin = 45,
            passiveOnly = true,
        )
        override val status: StateFlow<CgmSourceStatus> = MutableStateFlow(CgmSourceStatus.Idle)
        override val rssi: StateFlow<Int?> = MutableStateFlow(null)
        override fun readings(): Flow<CgmReading> = emptyFlow()
        override fun start() = Unit
        override fun close() = Unit
        override fun markSignalLost() = Unit
    }
}
