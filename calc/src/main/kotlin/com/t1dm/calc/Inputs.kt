package com.t1dm.calc

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.Precision

/**
 * The decision-relevant facts about the CGM anchor the whole recommendation rests on (§3.6-D/-F).
 * The forecast anchors on `last_bg`, so a stale or fabricated current-BG is the single largest
 * dosing hazard; these fields are both the freshness-gate inputs and the card fields.
 *
 * [lastMeasuredTsMs] null ⇒ there is no MEASURED reading at all (fail closed). [interpolatedFraction]
 * is the fraction of the recent anchor context that is INTERPOLATED or WARMUP — a fabricated
 * interpolated line must never silently drive a dose. [currentBgMgdl] is the last real value, used
 * by the hypo-treatment path.
 */
data class AnchorInfo(
    val lastMeasuredTsMs: Long?,
    val anchorTsMs: Long,
    val currentBgMgdl: Double?,
    val interpolatedFraction: Double,
    val warmup: Boolean,
) {
    fun ageMs(nowMs: Long): Long? = lastMeasuredTsMs?.let { nowMs - it }
}

/**
 * IOB/COB computed from **logged doses only** (§3.6-F), with the timestamp of the last logged dose so
 * the mandatory-confirmation rail can flag a long log gap. [iobU] null ⇒ IOB is unknown (the store
 * failed); an unknown IOB with a nonzero recommendation fails the IOB rail closed.
 */
data class IobSnapshot(
    val iobU: Double?,
    val cobG: Double,
    val lastLoggedDoseTsMs: Long?,
) {
    fun minSinceLastDose(nowMs: Long): Long? = lastLoggedDoseTsMs?.let { (nowMs - it) / 60_000L }
}

/** The backend/precision provenance of the selected model + the fp16↔fp32 agreement verdict (§3.6-E). */
data class BackendInfo(
    val backend: BackendId,
    val precision: Precision,
    /** null = not measured; true/false = last agreement probe within/outside the hypo-relevant tolerance. */
    val agreementOk: Boolean?,
    /**
     * The backend actually RENDERING the displayed forecast, when the switcher has it on a
     * non-authoritative path while dosing runs on the fp32 CPU authority ([backend]). Null ⇒ the
     * displayed forecast came from the authority too (nothing to note). PURELY INFORMATIONAL: it never
     * touches [trustworthy] or any rail — it only drives a small non-blocking note so the user knows the
     * dose was computed on the CPU authority even though a GPU/NPU rendered what they see (§3.6-E: the
     * switcher governs the DISPLAYED forecast only).
     */
    val displayedBackend: BackendId? = null,
) {
    /**
     * §3.6-E: only the AUTHORITATIVE fp32 XNNPACK CPU backend may drive a dose unconditionally.
     * ANY other backend — a GPU/NPU path, even at fp32 (e.g. the Vulkan delegate) — is NEVER
     * silently promoted: it is trustworthy for dosing ONLY once it has PASSED the fp32-agreement
     * probe ([agreementOk] == true). A selected non-authoritative backend whose agreement is
     * unmeasured (null) or failed (false) fails the dosing path CLOSED, while the forecast may
     * still render. (Precision alone is not sufficient — a fp32 GPU result can still diverge from
     * the CPU authority, so it must be measured, not assumed.)
     */
    val trustworthy: Boolean get() = backend == BackendId.EXECUTORCH_XNNPACK_FP32 || agreementOk == true
}

/** Fail-closed source of the CGM anchor facts; null ⇒ no signal at all (the freshness gate blocks). */
fun interface AnchorInfoSource {
    suspend fun current(nowMs: Long): AnchorInfo?
}

/** Fail-closed source of the logged-dose IOB/COB snapshot; null ⇒ store failure. */
fun interface IobSource {
    suspend fun snapshot(nowMs: Long): IobSnapshot?
}
