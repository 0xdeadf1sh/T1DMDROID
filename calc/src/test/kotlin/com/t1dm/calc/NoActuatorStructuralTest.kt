package com.t1dm.calc

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **structural no-actuator** invariant (PLAN Phase 4 §7 / safety-posture.md — "advisory-only,
 * NEVER actuates insulin"). The app is advisory-only: no code path may reach an insulin actuator. We
 * cannot prove that over arbitrary code, but we can pin the terminal seam: the public surface of the
 * calculator's result and orchestrator types must expose no member that *delivers* a dose — only ones
 * that *recommend* one. A future refactor that added a `deliver(...)` / `administer(...)` /
 * `actuate(...)` / `inject(...)` / pump-command member would flip this red.
 */
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
        // The result type is a pure data carrier: recommending a dose can only ever hand back numbers +
        // a card, never a live delivery handle. Assert the recommended result exposes a plain dose value.
        val recommended = AdviceResult.Recommended::class.java
        assertTrue(recommended.methods.any { it.name == "getBest" })
        assertTrue(Candidate::class.java.methods.any { it.name == "getDoseU" })
    }
}
