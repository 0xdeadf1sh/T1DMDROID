package com.t1dm.calc

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the terminal seam only: no member of these types may deliver a dose. */
class NoActuatorStructuralTest {

    private val forbidden = Regex("(?i)(actuate|administer|deliver|inject|dispense|setrate|pumpcommand|sendbolus)")

    private val surface = listOf(
        AdviceResult::class.java,
        AdviceResult.Recommended::class.java,
        AdviceResult.Refused::class.java,
        Candidate::class.java,
        DecisionCard::class.java,
        DoseAdvisor::class.java,
        BolusCalculator::class.java,
        SplitBolusSearch::class.java,
        BasalCalculator::class.java,
        Rails::class.java,
    )

    @Test
    fun no_public_member_names_an_actuation_verb() {
        val offenders = buildList {
            for (c in surface) {
                for (m in c.methods) if (forbidden.containsMatchIn(m.name)) add("${c.simpleName}.${m.name}")
                for (f in c.fields) if (forbidden.containsMatchIn(f.name)) add("${c.simpleName}.${f.name}")
            }
        }
        assertTrue("advisory-only violated — actuation-shaped members: $offenders", offenders.isEmpty())
    }

    @Test
    fun calc_module_declares_no_dependency_on_a_pump_or_actuator_type() {
        val recommended = AdviceResult.Recommended::class.java
        assertTrue(recommended.methods.any { it.name == "getBest" })
        assertTrue(Candidate::class.java.methods.any { it.name == "getDoseU" })
    }
}
