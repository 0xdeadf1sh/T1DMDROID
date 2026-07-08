package com.t1dm.cgm

import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag

/**
 * The validity + WARMUP gate (PLAN.private.md §3.1). Operates on an already CRC-validated
 * [DecodedAdvert]:
 *
 *  - `INVALID`  — fails the value gate: bad valid-bit or out-of-range BG. Dropped by the pipeline;
 *    never persisted as a value.
 *  - `WARMUP`   — passes the value gate but `minFromStart < warmupWindowMin`; suppressed from
 *    inference and alarm evaluation, shown distinctly on the graph. The value is still carried.
 *  - `NORMAL`   — a fully eligible reading.
 *
 * The order matters: the value gate runs first, so a warm-up-window reading that also fails the
 * value gate is `INVALID`, not `WARMUP`.
 *
 * **On `status`.** The task brief named `status == 0` as a hard validity condition, but the CGM.md
 * §3.1 golden advertisement — a real, CRC-valid 92 mg/dL reading — carries `status = 1`, while the
 * §3.2 live sample carries `status = 0`. Gating hard on `status == 0` would therefore reject the
 * canonical golden reading, contradicting both the acceptance criterion ("golden → MEASURED
 * bg=92") and the authoritative gate recorded in memory (`valid-bit ∧ 18≤bg≤800 ∧ warmup`, no
 * status term). So the empirical default does **not** hard-reject on `status`; [rejectNonNormalStatus]
 * is offered for the connected-session `LastPast`/`CurrentGlucose` payloads (which the field's
 * "0 = normal" semantics were documented against) should a later phase want it.
 */
class ReadingClassifier(
    private val warmupWindowMin: Int = CgmConstants.WARMUP_WINDOW_MIN,
    private val validBgRange: IntRange = CgmConstants.VALID_BG_RANGE,
    private val rejectNonNormalStatus: Boolean = false,
) {
    fun classify(d: DecodedAdvert): ReadingFlag = when {
        !d.valid -> ReadingFlag.INVALID
        d.glucoseMgdl !in validBgRange -> ReadingFlag.INVALID
        rejectNonNormalStatus && d.status != 0 -> ReadingFlag.INVALID
        d.minFromStart < warmupWindowMin -> ReadingFlag.WARMUP
        else -> ReadingFlag.NORMAL
    }
}
