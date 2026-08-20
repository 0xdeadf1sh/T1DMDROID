package com.t1dm.inference

import com.t1dm.core.model.LoraGuardVerdict
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The attach rule, as a table. It is the whole of what stands between a fitted adapter and the
 *  forecaster a dose recommendation is read off. */
class LoraAttachGateTest {

    private val fitted = 1_000_000L

    @Test
    fun a_passing_adapter_attaches() {
        assertNull(loraAttachRefusal(LoraGuardVerdict.PASS, null, null, fitted))
    }

    /** Silence is not a pass — the state a stored row backfills to, and the state an imported or
     *  restored adapter arrives in. */
    @Test
    fun an_unmeasured_adapter_is_refused_like_a_blocked_one() {
        assertNotNull(loraAttachRefusal(LoraGuardVerdict.ABSENT, null, null, fitted))
        assertNotNull(loraAttachRefusal(LoraGuardVerdict.BLOCKED, null, null, fitted))
        assertNotNull(loraAttachRefusal(LoraGuardVerdict.INCONCLUSIVE, null, null, fitted))
    }

    /** A refusal carries the guard's own numbers when it has them. */
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

    /**
     * …but not a history edit, and the ordering is the point: an edit that lands AFTER an override
     * is information the override could not have taken into account.
     */
    @Test
    fun an_override_does_not_clear_a_rewritten_history() {
        val r = loraAttachRefusal(LoraGuardVerdict.PASS, fitted + 1, fitted + 500, fitted)
        assertNotNull(r)
        assertTrue(r!!, r.contains("re-fit"))
    }

    /** An edit BEFORE the fit is what the adapter was fitted on, and is not a refusal. */
    @Test
    fun a_history_edit_older_than_the_fit_is_not_a_refusal() {
        assertNull(loraAttachRefusal(LoraGuardVerdict.PASS, null, fitted - 1, fitted))
    }
}
