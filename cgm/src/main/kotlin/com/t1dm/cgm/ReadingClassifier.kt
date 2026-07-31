package com.t1dm.cgm

import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag

/**
 * The validity + WARMUP gate (§3.1). Operates on an already CRC-validated
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
    warmupWindowMin: Int = CgmConstants.WARMUP_WINDOW_MIN,
    private val validBgRange: IntRange = CgmConstants.VALID_BG_RANGE,
    private val rejectNonNormalStatus: Boolean = false,
) {
    /**
     * The per-source warm-up window in minutes, and on this branch the WHOLE of the warm-up evidence:
     * a passive advertisement carries no warm-up bit, so `minFromStart < this` is the entire verdict
     * and the CONFIGURED duration is what governs. [CgmConstants.WARMUP_WINDOW_MIN] is only the seed a
     * newly discovered source starts at; the user's own value rides
     * [CgmSourceDescriptor.warmupWindowMin] and is edited from the CGM panel.
     *
     * The comparison is EXCLUSIVE, which is what keeps it aligned with the BG panel's countdown:
     * warm-up ends at `sensorStart + warmupWindowMin`, so the reading stamped at exactly that minute is
     * already `NORMAL` and the chip reaches zero on the same reading. `0` therefore disables warm-up
     * outright — no `minFromStart` is below zero — with no boundary case of its own.
     *
     * Retunable in place rather than rebuilt: the [CgmPipeline] holding this classifier is STATEFUL (a
     * dedup ring and the grid stamper's interpolation anchor), so constructing a fresh one to carry a
     * new window would drop that state and re-admit a minute already recorded. `@Volatile` because the
     * write comes off the UI's coroutine and the read happens on the scan thread. Clamped on every
     * write, so no caller can install a window the panel could not have produced.
     */
    @Volatile
    var warmupWindowMin: Int = warmupWindowMin.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE)
        set(value) { field = value.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE) }

    fun classify(d: DecodedAdvert): ReadingFlag = when {
        !d.valid -> ReadingFlag.INVALID
        d.glucoseMgdl !in validBgRange -> ReadingFlag.INVALID
        rejectNonNormalStatus && d.status != 0 -> ReadingFlag.INVALID
        d.minFromStart < warmupWindowMin -> ReadingFlag.WARMUP
        else -> ReadingFlag.NORMAL
    }
}
