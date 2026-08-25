package com.t1dm.inference

import com.t1dm.core.model.LoraGuardVerdict
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraAttachGateTest {

    private val fitted = 1_000_000L

    @Test
    fun a_passing_adapter_attaches() {
        assertNull(loraAttachRefusal(LoraGuardVerdict.PASS, null, null, fitted))
    }

    /** ABSENT is what a backfilled row and an imported adapter arrive as. */
    @Test
    fun an_unmeasured_adapter_is_refused_like_a_blocked_one() {
        assertNotNull(loraAttachRefusal(LoraGuardVerdict.ABSENT, null, null, fitted))
        assertNotNull(loraAttachRefusal(LoraGuardVerdict.BLOCKED, null, null, fitted))
        assertNotNull(loraAttachRefusal(LoraGuardVerdict.INCONCLUSIVE, null, null, fitted))
    }

    @Test
    fun a_blocked_adapter_reports_why() {
        val why = "keeps 5% of the model's dose response (-0.50 vs -10.50 mg/dL/U), floor 25%"
        assertTrue(loraAttachRefusal(LoraGuardVerdict.BLOCKED, null, null, fitted, why) == why)
    }

    @Test
    fun a_deliberate_override_clears_a_guard_refusal() {
        assertNull(loraAttachRefusal(LoraGuardVerdict.BLOCKED, fitted + 1, null, fitted))
        assertNull(loraAttachRefusal(LoraGuardVerdict.ABSENT, fitted + 1, null, fitted))
    }

    /** An edit after an override is information the override could not have weighed. */
    @Test
    fun an_override_does_not_clear_a_rewritten_history() {
        val r = loraAttachRefusal(LoraGuardVerdict.PASS, fitted + 1, fitted + 500, fitted)
        assertNotNull(r)
        assertTrue(r!!, r.contains("re-fit"))
    }

    /** An edit before the fit is already in the adapter. */
    @Test
    fun a_history_edit_older_than_the_fit_is_not_a_refusal() {
        assertNull(loraAttachRefusal(LoraGuardVerdict.PASS, null, fitted - 1, fitted))
    }
}
