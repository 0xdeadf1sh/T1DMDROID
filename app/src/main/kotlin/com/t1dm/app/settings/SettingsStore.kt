package com.t1dm.app.settings

import com.t1dm.alerts.AlarmConfig
import com.t1dm.alerts.AlarmSeverity
import com.t1dm.alerts.VibrationPreset
import com.t1dm.app.DeathFlavor
import com.t1dm.calc.Asymmetry
import com.t1dm.calc.CalcConfig
import com.t1dm.calc.GridSpec
import com.t1dm.calc.Objective
import com.t1dm.calc.RailToggles
import com.t1dm.calc.TargetRange as CalcTargetRange
import com.t1dm.core.design.HapticStrength
import com.t1dm.core.design.normalizeThemeId
import com.t1dm.core.model.AlertThresholds
import com.t1dm.data.T1dmRepository
import com.t1dm.inference.InferenceControllerDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * The complete, kv-backed configuration surface for the Settings hub (Phase 7C —
 * items 14 & 17, "expose EVERY knob"). Every user-tunable knob that the rest of the app boots with a
 * hard-coded default for is persisted here so a restart restores it, and — per —
 * the thresholds are **user-set and deliberately UNBOUNDED**: this store only coerces values to a
 * non-negative sanity floor and never imposes a clinical ceiling.
 *
 * It deliberately lives in `:app` (not `:data`) so it can assemble the module-level policy types
 * [AlarmConfig] (`:alerts`) and [CalcConfig] (`:calc`) from the raw kv primitives. `:data` stays free
 * of those dependencies; it only owns the generic kv read/write + the bulk export/import.
 *
 * Nothing here actuates. A tuned threshold merely shapes when an *advisory* fires or which candidate
 * dose the calculator *recommends* — the manual-administration model remains the terminal safety net.
 */
class SettingsStore(
    private val repository: T1dmRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // ── typed kv helpers ──────────────────────────────────────────────────────────────────────

    private fun intFlow(key: String, default: Int): Flow<Int> =
        repository.observeKv(key).map { it?.toIntOrNull() ?: default }

    private fun doubleFlow(key: String, default: Double): Flow<Double> =
        repository.observeKv(key).map { it?.toDoubleOrNull() ?: default }

    private fun boolFlow(key: String, default: Boolean): Flow<Boolean> =
        repository.observeKv(key).map { it?.let { s -> s == "1" || s == "true" } ?: default }

    private suspend fun getInt(key: String, default: Int): Int =
        repository.getKv(key)?.toIntOrNull() ?: default

    private suspend fun getDouble(key: String, default: Double): Double =
        repository.getKv(key)?.toDoubleOrNull() ?: default

    private suspend fun getBool(key: String, default: Boolean): Boolean =
        repository.getKv(key)?.let { it == "1" || it == "true" } ?: default

    private suspend fun put(key: String, value: String) = repository.putKv(key, value, clock())

    // ── Deterministic alarm thresholds + loss-of-signal (§3.6-A) ────────────────
    // UNBOUNDED user values; only floored at 0. Ordering (urgentLow < low <= high < urgentHigh) is
    // NOT enforced — the user's override is honoured — but the Settings screen surfaces a warning
    // when the values are out of order so a mistake is visible.

    val alarmUrgentLow: Flow<Int> = intFlow(K_ALARM_URGENT_LOW, DEF.thresholds.urgentLowMgdl)
    val alarmLow: Flow<Int> = intFlow(K_ALARM_LOW, DEF.thresholds.lowMgdl)
    val alarmHigh: Flow<Int> = intFlow(K_ALARM_HIGH, DEF.thresholds.highMgdl)
    val alarmUrgentHigh: Flow<Int> = intFlow(K_ALARM_URGENT_HIGH, DEF.thresholds.urgentHighMgdl)

    suspend fun setAlarmThresholds(urgentLow: Int, low: Int, high: Int, urgentHigh: Int) {
        put(K_ALARM_URGENT_LOW, urgentLow.coerceAtLeast(0).toString())
        put(K_ALARM_LOW, low.coerceAtLeast(0).toString())
        put(K_ALARM_HIGH, high.coerceAtLeast(0).toString())
        put(K_ALARM_URGENT_HIGH, urgentHigh.coerceAtLeast(0).toString())
    }

    val lossMin: Flow<Int> = intFlow(K_LOSS_MIN, DEF.lossMin)
    val lossEscalatedMin: Flow<Int> = intFlow(K_LOSS_ESCALATED_MIN, DEF.lossEscalatedMin)
    val repeatCadenceMin: Flow<Int> = intFlow(K_REPEAT_CADENCE, DEF.repeatCadenceMin)
    val minActuationMin: Flow<Int> = intFlow(K_MIN_ACTUATION, DEF.minActuationIntervalMin)

    /** How long a "Snooze" on a firing alarm silences presentation before it auto-expires (§3.6 C1 —
     *  a snooze is always TIME-BOUNDED; permanent silence stays DEATH's job). Floored at 1 minute. */
    val snoozeMin: Flow<Int> = intFlow(K_SNOOZE_MIN, DEFAULT_SNOOZE_MIN)

    suspend fun setLossWindows(lossMin: Int, lossEscalatedMin: Int) {
        put(K_LOSS_MIN, lossMin.coerceAtLeast(1).toString())
        put(K_LOSS_ESCALATED_MIN, lossEscalatedMin.coerceAtLeast(1).toString())
    }

    suspend fun setRepeatCadence(min: Int) = put(K_REPEAT_CADENCE, min.coerceAtLeast(1).toString())
    suspend fun setMinActuationMin(min: Int) = put(K_MIN_ACTUATION, min.coerceAtLeast(0).toString())
    suspend fun currentSnoozeMin(): Int = decodeSnoozeMin(repository.getKv(K_SNOOZE_MIN))
    suspend fun setSnoozeMin(min: Int) = put(K_SNOOZE_MIN, encodeSnoozeMin(min))

    // ── Weak-signal alarm — the connection RSSI (dBm) below which the sensor link is "weak", sustained
    // for a window. A signal-QUALITY alert, distinct from the age-based loss-of-signal alarm; folded into
    // currentAlarmConfig() so it rides the same snapshot the engine is built from. dBm is UNBOUNDED.
    val weakSignalEnabled: Flow<Boolean> = boolFlow(K_WEAK_SIGNAL_ON, DEF.weakSignalEnabled)
    val weakSignalDbm: Flow<Int> = intFlow(K_WEAK_SIGNAL_DBM, DEF.weakSignalDbm)
    val weakSignalSustainMin: Flow<Int> = intFlow(K_WEAK_SIGNAL_SUSTAIN, DEF.weakSignalSustainMin)

    suspend fun setWeakSignal(enabled: Boolean, dbm: Int, sustainMin: Int) {
        put(K_WEAK_SIGNAL_ON, if (enabled) "1" else "0")
        put(K_WEAK_SIGNAL_DBM, dbm.toString())
        put(K_WEAK_SIGNAL_SUSTAIN, sustainMin.coerceAtLeast(0).toString())
    }

    /** Assemble the deterministic-alarm policy from the persisted knobs (defaults where unset). */
    suspend fun currentAlarmConfig(): AlarmConfig = AlarmConfig(
        thresholds = AlertThresholds(
            urgentLowMgdl = getInt(K_ALARM_URGENT_LOW, DEF.thresholds.urgentLowMgdl),
            lowMgdl = getInt(K_ALARM_LOW, DEF.thresholds.lowMgdl),
            highMgdl = getInt(K_ALARM_HIGH, DEF.thresholds.highMgdl),
            urgentHighMgdl = getInt(K_ALARM_URGENT_HIGH, DEF.thresholds.urgentHighMgdl),
        ),
        lossMin = getInt(K_LOSS_MIN, DEF.lossMin),
        lossEscalatedMin = getInt(K_LOSS_ESCALATED_MIN, DEF.lossEscalatedMin),
        repeatCadenceMin = getInt(K_REPEAT_CADENCE, DEF.repeatCadenceMin),
        minActuationIntervalMin = getInt(K_MIN_ACTUATION, DEF.minActuationIntervalMin),
        overTempEnabled = getBool(K_OVERTEMP_ENABLED, DEFAULT_OVERTEMP_ENABLED),
        overTempAlertC = getDouble(K_OVERTEMP_ALERT_C, DEFAULT_OVERTEMP_ALERT_C),
        overTempClearC = getDouble(K_OVERTEMP_CLEAR_C, DEFAULT_OVERTEMP_CLEAR_C),
        overTempSeverity = if (getBool(K_OVERTEMP_CRITICAL, DEFAULT_OVERTEMP_CRITICAL))
            AlarmSeverity.CRITICAL else AlarmSeverity.WARNING,
        weakSignalEnabled = getBool(K_WEAK_SIGNAL_ON, DEF.weakSignalEnabled),
        weakSignalDbm = getInt(K_WEAK_SIGNAL_DBM, DEF.weakSignalDbm),
        weakSignalSustainMin = getInt(K_WEAK_SIGNAL_SUSTAIN, DEF.weakSignalSustainMin),
    )

    // ── Alert actuators (per-band vibration + DND bypass; sounds handled with Uri in AppContainer) ──

    val warningVibration: Flow<String> = repository.observeKv(K_VIB_WARN).map { it ?: VibrationPreset.DOUBLE.name }
    val criticalVibration: Flow<String> = repository.observeKv(K_VIB_CRIT).map { it ?: VibrationPreset.INSISTENT.name }
    val bypassDnd: Flow<Boolean> = boolFlow(K_BYPASS_DND, true)
    val criticalSoundOn: Flow<Boolean> = boolFlow(K_CRIT_SOUND_ON, true)
    val warningSoundOn: Flow<Boolean> = boolFlow(K_WARN_SOUND_ON, false)

    suspend fun currentWarningVibration(): VibrationPreset = vibOrDefault(repository.getKv(K_VIB_WARN), VibrationPreset.DOUBLE)
    suspend fun currentCriticalVibration(): VibrationPreset = vibOrDefault(repository.getKv(K_VIB_CRIT), VibrationPreset.INSISTENT)
    suspend fun currentBypassDnd(): Boolean = getBool(K_BYPASS_DND, true)
    suspend fun currentCriticalSoundOn(): Boolean = getBool(K_CRIT_SOUND_ON, true)
    suspend fun currentWarningSoundOn(): Boolean = getBool(K_WARN_SOUND_ON, false)

    private fun vibOrDefault(raw: String?, fallback: VibrationPreset): VibrationPreset =
        raw?.let { runCatching { VibrationPreset.valueOf(it) }.getOrNull() } ?: fallback

    suspend fun setWarningVibration(preset: VibrationPreset) = put(K_VIB_WARN, preset.name)
    suspend fun setCriticalVibration(preset: VibrationPreset) = put(K_VIB_CRIT, preset.name)
    suspend fun setBypassDnd(on: Boolean) = put(K_BYPASS_DND, if (on) "1" else "0")
    suspend fun setCriticalSoundOn(on: Boolean) = put(K_CRIT_SOUND_ON, if (on) "1" else "0")
    suspend fun setWarningSoundOn(on: Boolean) = put(K_WARN_SOUND_ON, if (on) "1" else "0")

    // ── Low-power / battery-saver (Q9 — default 20 %, configurable) ─────────────────

    val lowPowerEnabled: Flow<Boolean> = boolFlow(K_POWER_ENABLED, true)
    val lowPowerPercent: Flow<Int> = intFlow(K_POWER_PCT, DEFAULT_LOW_POWER_PCT)
    val lowPowerUseOsSaver: Flow<Boolean> = boolFlow(K_POWER_OS_SAVER, true)

    suspend fun setLowPowerEnabled(on: Boolean) = put(K_POWER_ENABLED, if (on) "1" else "0")
    suspend fun setLowPowerPercent(pct: Int) = put(K_POWER_PCT, pct.coerceIn(0, 100).toString())
    suspend fun setLowPowerUseOsSaver(on: Boolean) = put(K_POWER_OS_SAVER, if (on) "1" else "0")

    suspend fun currentLowPowerEnabled(): Boolean = getBool(K_POWER_ENABLED, true)
    suspend fun currentLowPowerPercent(): Int = getInt(K_POWER_PCT, DEFAULT_LOW_POWER_PCT)
    suspend fun currentLowPowerUseOsSaver(): Boolean = getBool(K_POWER_OS_SAVER, true)

    // ── Forecast cadence (F2 — adaptive per-reading vs a fixed wall-clock period) ────────────────
    // ADAPTIVE re-forecasts on every CGM reading; TIMED fires on a phone-clock grid of N minutes.
    // Read as a live snapshot by the single-consumer forecast driver in CgmScanService.

    val forecastMode: Flow<String> =
        repository.observeKv(K_FORECAST_MODE).map { it ?: FORECAST_MODE_ADAPTIVE }
    val forecastPeriodMin: Flow<Int> = intFlow(K_FORECAST_PERIOD_MIN, DEFAULT_FORECAST_PERIOD_MIN)

    suspend fun currentForecastMode(): String = repository.getKv(K_FORECAST_MODE) ?: FORECAST_MODE_ADAPTIVE
    suspend fun currentForecastPeriodMin(): Int = getInt(K_FORECAST_PERIOD_MIN, DEFAULT_FORECAST_PERIOD_MIN)
    suspend fun setForecastMode(mode: String) = put(K_FORECAST_MODE, mode)
    suspend fun setForecastPeriodMin(min: Int) =
        put(K_FORECAST_PERIOD_MIN, min.coerceIn(FORECAST_PERIOD_MIN_MIN, FORECAST_PERIOD_MIN_MAX).toString())

    // ── Log-driven re-forecast debounce — how long a logged meal/dose waits before the model re-runs
    // on the curve channels it just moved. A SIBLING of the cadence above, not a mode of it: the two
    // tick sources fire on their own schedule regardless, and this only decides how promptly a WRITE
    // gets its own cycle. The window exists to coalesce — a meal and then its bolus arrive seconds
    // apart and are worth exactly one forward, not two — so it is short by default and clamped well
    // under the grid so a re-run can never be deferred past the tick it was meant to pre-empt.
    val logReforecastDebounceS: Flow<Int> =
        repository.observeKv(K_INF_LOG_DEBOUNCE_S).map { decodeLogReforecastDebounceS(it) }
    suspend fun currentLogReforecastDebounceS(): Int =
        decodeLogReforecastDebounceS(repository.getKv(K_INF_LOG_DEBOUNCE_S))
    suspend fun setLogReforecastDebounceS(seconds: Int) =
        put(K_INF_LOG_DEBOUNCE_S, encodeLogReforecastDebounceS(seconds))

    // ── Thermal gate (D1/D3/D6 — pause inference on the BATTERY-sensor °C; a truer die temp is
    // unreadable here). Gate ENABLED by default; the threshold/warn-margin are user-set (floored at 0).
    // The gate reads the battery °C irrespective of DEATH (D4: it stays active in DEATH mode).

    val thermalGateEnabled: Flow<Boolean> = boolFlow(K_INF_THERMAL_ON, DEFAULT_THERMAL_ON)
    val inferenceMaxTempC: Flow<Double> = doubleFlow(K_INF_MAX_TEMP_C, DEFAULT_MAX_TEMP_C)
    val thermalWarnMarginC: Flow<Double> = doubleFlow(K_INF_WARN_MARGIN_C, DEFAULT_WARN_MARGIN_C)

    suspend fun currentThermalGateEnabled(): Boolean = getBool(K_INF_THERMAL_ON, DEFAULT_THERMAL_ON)
    suspend fun currentInferenceMaxTempC(): Double = getDouble(K_INF_MAX_TEMP_C, DEFAULT_MAX_TEMP_C)
    suspend fun currentThermalWarnMarginC(): Double = getDouble(K_INF_WARN_MARGIN_C, DEFAULT_WARN_MARGIN_C)

    suspend fun setThermalGateEnabled(on: Boolean) = put(K_INF_THERMAL_ON, if (on) "1" else "0")
    suspend fun setInferenceMaxTempC(c: Double) = put(K_INF_MAX_TEMP_C, c.coerceAtLeast(0.0).toString())
    suspend fun setThermalWarnMarginC(c: Double) = put(K_INF_WARN_MARGIN_C, c.coerceAtLeast(0.0).toString())

    // ── Running-set cap (issues 1/2 — how many discovered models run each cycle). Every running model
    // forecasts and pushes to the server tagged by its model_id; only the SELECTED model feeds the
    // dashboard and dosing. User-configurable; coerced to [MIN, MAX], default DEFAULT_MAX_MODELS.

    val inferenceMaxModels: Flow<Int> = repository.observeKv(K_INF_MAX_MODELS)
        .map { it?.toIntOrNull()?.coerceIn(INF_MAX_MODELS_MIN, INF_MAX_MODELS_MAX) ?: DEFAULT_MAX_MODELS }

    suspend fun currentInferenceMaxModels(): Int =
        repository.getKv(K_INF_MAX_MODELS)?.toIntOrNull()?.coerceIn(INF_MAX_MODELS_MIN, INF_MAX_MODELS_MAX) ?: DEFAULT_MAX_MODELS

    suspend fun setInferenceMaxModels(n: Int) =
        put(K_INF_MAX_MODELS, n.coerceIn(INF_MAX_MODELS_MIN, INF_MAX_MODELS_MAX).toString())

    // ── BG input filter (INFERENCE.md §7.1) — the causal Savitzky-Golay window the BG channel is
    // pre-filtered at before normalization. It governs the MODEL INPUT, not the drawing: it shifts
    // the `last_bg` anchor the §3.6-D freshness gate, the rails and the countdown all rest on, so it
    // is an `inference.` key (hand-listed in CONFIG_EXACT_KEYS) rather than a cosmetic `graph.` one.
    // Snapped to an offered detent on EVERY read: the window must be odd, and the Rust model-input
    // guard rejects anything else outright rather than substituting a default.

    val savgolWindow: Flow<Int> = repository.observeKv(K_INF_SAVGOL_WINDOW)
        .map { InferenceControllerDefaults.nearestSmoothingStop(it?.toIntOrNull() ?: DEFAULT_SAVGOL_WINDOW) }

    suspend fun currentSavgolWindow(): Int = InferenceControllerDefaults.nearestSmoothingStop(
        repository.getKv(K_INF_SAVGOL_WINDOW)?.toIntOrNull() ?: DEFAULT_SAVGOL_WINDOW,
    )

    suspend fun setSavgolWindow(window: Int) =
        put(K_INF_SAVGOL_WINDOW, InferenceControllerDefaults.nearestSmoothingStop(window).toString())

    // ── Death-clock offsets (F5 — the morbid IOB-exhaustion projection). DISPLAY-ONLY forward offsets
    // (hours) from each prior landmark; no §3.6 gate reads these. Tunable per D2, floored at 0.

    val dkaAfterIobZeroH: Flow<Double> = doubleFlow(K_DEATH_DKA_H, DEFAULT_DKA_AFTER_IOB_ZERO_H)
    val comaAfterDkaH: Flow<Double> = doubleFlow(K_DEATH_COMA_H, DEFAULT_COMA_AFTER_DKA_H)
    val deathAfterComaH: Flow<Double> = doubleFlow(K_DEATH_DEATH_H, DEFAULT_DEATH_AFTER_COMA_H)

    suspend fun setDkaAfterIobZeroH(h: Double) = put(K_DEATH_DKA_H, h.coerceAtLeast(0.0).toString())
    suspend fun setComaAfterDkaH(h: Double) = put(K_DEATH_COMA_H, h.coerceAtLeast(0.0).toString())
    suspend fun setDeathAfterComaH(h: Double) = put(K_DEATH_DEATH_H, h.coerceAtLeast(0.0).toString())

    // ── Over-temperature alarm (F7 — the battery-°C alert, distinct from the inference gate). Fires at
    // alertC, clears at clearC (hysteresis); D4-exempt from DEATH's global suppression. Folded into
    // currentAlarmConfig() above so it rides the same snapshot the AlarmEngine is built from.

    val overTempEnabled: Flow<Boolean> = boolFlow(K_OVERTEMP_ENABLED, DEFAULT_OVERTEMP_ENABLED)
    val overTempAlertC: Flow<Double> = doubleFlow(K_OVERTEMP_ALERT_C, DEFAULT_OVERTEMP_ALERT_C)
    val overTempClearC: Flow<Double> = doubleFlow(K_OVERTEMP_CLEAR_C, DEFAULT_OVERTEMP_CLEAR_C)
    val overTempCritical: Flow<Boolean> = boolFlow(K_OVERTEMP_CRITICAL, DEFAULT_OVERTEMP_CRITICAL)

    suspend fun setOverTempConfig(enabled: Boolean, alertC: Double, clearC: Double, critical: Boolean) {
        put(K_OVERTEMP_ENABLED, if (enabled) "1" else "0")
        put(K_OVERTEMP_ALERT_C, alertC.coerceAtLeast(0.0).toString())
        put(K_OVERTEMP_CLEAR_C, clearC.coerceAtLeast(0.0).toString())
        put(K_OVERTEMP_CRITICAL, if (critical) "1" else "0")
    }

    // ── Calculator policy (§3.6 — every threshold user-set, UNBOUNDED) ─────────────────────────

    private val calcDef = CalcConfig()

    val calcTargetLow: Flow<Double> = doubleFlow(K_CALC_TARGET_LOW, calcDef.target.lowMgdl)
    val calcTargetHigh: Flow<Double> = doubleFlow(K_CALC_TARGET_HIGH, calcDef.target.highMgdl)
    val calcTargetMid: Flow<Double> = doubleFlow(K_CALC_TARGET_MID, calcDef.target.targetMgdl)
    val calcObjective: Flow<String> = repository.observeKv(K_CALC_OBJECTIVE).map { it ?: OBJ_KOVATCHEV }
    val calcHypoWeight: Flow<Double> = doubleFlow(K_CALC_HYPO_W, calcDef.asymmetry.hypoWeight)
    val calcHyperWeight: Flow<Double> = doubleFlow(K_CALC_HYPER_W, calcDef.asymmetry.hyperWeight)
    val calcPredictedLow: Flow<Double> = doubleFlow(K_CALC_PRED_LOW, calcDef.predictedLowThresholdMgdl)
    val calcIobCeiling: Flow<Double> = doubleFlow(K_CALC_IOB_CEIL, calcDef.iobCeilingU)
    val calcFreshnessMin: Flow<Int> = intFlow(K_CALC_FRESHNESS_MIN, (calcDef.freshnessMaxAgeMs / 60_000L).toInt())
    val calcGridMaxU: Flow<Double> = doubleFlow(K_CALC_GRID_MAX, calcDef.grid.maxU)
    val calcGridStepU: Flow<Double> = doubleFlow(K_CALC_GRID_STEP, calcDef.grid.stepU)

    val railFreshness: Flow<Boolean> = boolFlow(K_RAIL_FRESH, calcDef.rails.freshnessGate)
    val railPredictedLow: Flow<Boolean> = boolFlow(K_RAIL_PREDLOW, calcDef.rails.predictedLowVeto)
    val railIobCeiling: Flow<Boolean> = boolFlow(K_RAIL_IOB, calcDef.rails.iobCeiling)
    val railConfirm: Flow<Boolean> = boolFlow(K_RAIL_CONFIRM, calcDef.rails.mandatoryConfirmation)
    val railHypoTreatment: Flow<Boolean> = boolFlow(K_RAIL_HYPO, calcDef.rails.hypoTreatment)

    suspend fun setCalcTarget(low: Double, high: Double, mid: Double) {
        put(K_CALC_TARGET_LOW, low.coerceAtLeast(0.0).toString())
        put(K_CALC_TARGET_HIGH, high.coerceAtLeast(0.0).toString())
        put(K_CALC_TARGET_MID, mid.coerceAtLeast(0.0).toString())
    }

    suspend fun setCalcObjective(key: String) = put(K_CALC_OBJECTIVE, key)
    suspend fun setCalcAsymmetry(hypo: Double, hyper: Double) {
        put(K_CALC_HYPO_W, hypo.coerceAtLeast(0.0).toString())
        put(K_CALC_HYPER_W, hyper.coerceAtLeast(0.0).toString())
    }

    suspend fun setCalcPredictedLow(v: Double) = put(K_CALC_PRED_LOW, v.coerceAtLeast(0.0).toString())
    suspend fun setCalcIobCeiling(v: Double) = put(K_CALC_IOB_CEIL, v.coerceAtLeast(0.0).toString())
    suspend fun setCalcFreshnessMin(v: Int) = put(K_CALC_FRESHNESS_MIN, v.coerceAtLeast(1).toString())
    suspend fun setCalcGrid(maxU: Double, stepU: Double) {
        put(K_CALC_GRID_MAX, maxU.coerceAtLeast(0.0).toString())
        put(K_CALC_GRID_STEP, stepU.coerceAtLeast(0.1).toString())
    }

    suspend fun setRail(rail: String, on: Boolean) {
        val key = when (rail) {
            RAIL_FRESHNESS -> K_RAIL_FRESH
            RAIL_PREDICTED_LOW -> K_RAIL_PREDLOW
            RAIL_IOB -> K_RAIL_IOB
            RAIL_CONFIRM -> K_RAIL_CONFIRM
            RAIL_HYPO -> K_RAIL_HYPO
            else -> return
        }
        put(key, if (on) "1" else "0")
    }

    /** Assemble the calculator policy from the persisted knobs (defaults where unset). */
    suspend fun currentCalcConfig(): CalcConfig {
        val objective = when (repository.getKv(K_CALC_OBJECTIVE)) {
            OBJ_MIN_TOR -> Objective.MinTimeOutOfRange
            OBJ_HIT_TARGET -> Objective.HitTargetAtTime(60 * 60_000L)
            else -> Objective.MinKovatchevRisk
        }
        val assembled = calcDef.copy(
            target = CalcTargetRange(
                lowMgdl = getDouble(K_CALC_TARGET_LOW, calcDef.target.lowMgdl),
                highMgdl = getDouble(K_CALC_TARGET_HIGH, calcDef.target.highMgdl),
                targetMgdl = getDouble(K_CALC_TARGET_MID, calcDef.target.targetMgdl),
            ),
            objective = objective,
            asymmetry = Asymmetry(
                hypoWeight = getDouble(K_CALC_HYPO_W, calcDef.asymmetry.hypoWeight),
                hyperWeight = getDouble(K_CALC_HYPER_W, calcDef.asymmetry.hyperWeight),
            ),
            rails = RailToggles(
                freshnessGate = getBool(K_RAIL_FRESH, calcDef.rails.freshnessGate),
                predictedLowVeto = getBool(K_RAIL_PREDLOW, calcDef.rails.predictedLowVeto),
                iobCeiling = getBool(K_RAIL_IOB, calcDef.rails.iobCeiling),
                mandatoryConfirmation = getBool(K_RAIL_CONFIRM, calcDef.rails.mandatoryConfirmation),
                hypoTreatment = getBool(K_RAIL_HYPO, calcDef.rails.hypoTreatment),
            ),
            grid = GridSpec(
                minU = calcDef.grid.minU,
                maxU = getDouble(K_CALC_GRID_MAX, calcDef.grid.maxU),
                stepU = getDouble(K_CALC_GRID_STEP, calcDef.grid.stepU).coerceAtLeast(0.1),
            ),
            freshnessMaxAgeMs = getInt(K_CALC_FRESHNESS_MIN, (calcDef.freshnessMaxAgeMs / 60_000L).toInt()) * 60_000L,
            predictedLowThresholdMgdl = getDouble(K_CALC_PRED_LOW, calcDef.predictedLowThresholdMgdl),
            iobCeilingU = getDouble(K_CALC_IOB_CEIL, calcDef.iobCeilingU),
        )
        if (!currentDeathMode()) return assembled
        // §3.6 override for DEATH mode: every optional user-safety rail off + its threshold neutralised,
        // so the advisor still emits a number off a stale/degenerate forecast. The structural
        // backend-agreement + baseline-degeneracy refusals in DoseAdvisor are untouched (not config-driven).
        return assembled.copy(
            rails = RailToggles(
                freshnessGate = false,
                predictedLowVeto = false,
                iobCeiling = false,
                mandatoryConfirmation = false,
                hypoTreatment = false,
            ),
            freshnessMaxAgeMs = Long.MAX_VALUE,
            predictedLowThresholdMgdl = 0.0,
            iobCeilingU = Double.MAX_VALUE,
        )
    }

    // ── DEATH mode (the total-silence override) — persisted, deliberately NOT in the exportable set
    // (it must never travel with a shared config). Reading it flips the calculator to the §3.6 override
    // below and silences the active alarm surfaces (the FGS gate reads it via AppContainer). ─────────

    // Flavor-gated at the origin: in the public flavor DeathFlavor.SUPPORTED is false, so the override
    // is pinned off (the key is never read or written) and every downstream reader — the §3.6 config
    // rail/degeneracy-gate bypass, the FGS alarm-silence gate, the theme — sees a hard false.
    val deathMode: Flow<Boolean> =
        if (DeathFlavor.SUPPORTED) boolFlow(K_DEATH, false) else flowOf(false)
    suspend fun currentDeathMode(): Boolean = DeathFlavor.SUPPORTED && getBool(K_DEATH, false)
    suspend fun setDeathMode(on: Boolean) {
        if (DeathFlavor.SUPPORTED) put(K_DEATH, if (on) "1" else "0")
    }

    // ── UI (a global "disable all animations" toggle) ────────────────────────

    val animationsEnabled: Flow<Boolean> = boolFlow(K_UI_ANIMATIONS, true)
    /** The one-shot mirror of [animationsEnabled], for the headless surfaces (the glucose widget's
     *  live pull, the FGS's freshness-blink gate) that must read this ONCE without subscribing. A
     *  `Flow.first()` there collects a live Room query, whose first emission drives the
     *  InvalidationTracker's subscribe/refresh path — on a cold, widget-only process that can park where
     *  a bounded `getKv` SELECT cannot, holding the tile on its loading spinner. */
    suspend fun currentAnimationsEnabled(): Boolean = getBool(K_UI_ANIMATIONS, true)
    suspend fun setAnimationsEnabled(on: Boolean) = put(K_UI_ANIMATIONS, if (on) "1" else "0")

    // ── UI haptics intensity (core/design Haptics.kt) ─────────────────────────────────────────
    // A SIBLING of the animation flag, not a dependent: a static UI and a mute one are different
    // wants. Deliberately NOT the same knob as the `alerts.vib.*` presets above — those are §3.6-A
    // alarm actuation and may never be attenuated by a UI comfort preference. Exposed as an opaque
    // name string for the Settings chip row (the screen never sees a :core:design type).
    val hapticsLevel: Flow<String> = repository.observeKv(K_UI_HAPTICS).map { it ?: DEFAULT_HAPTICS }
    suspend fun currentHapticsLevel(): HapticStrength = HapticStrength.forKey(repository.getKv(K_UI_HAPTICS))
    suspend fun setHapticsLevel(level: HapticStrength) = put(K_UI_HAPTICS, level.name)

    // ── Themed background image opacity (0–100 %; 0 = off). The per-theme Canvas backdrop is drawn at
    // this alpha behind the whole app. Exportable (ui.* prefix) so it travels with a shared config.
    val backgroundAlphaPct: Flow<Int> = intFlow(K_UI_BG_ALPHA, DEFAULT_BG_ALPHA_PCT)
    suspend fun currentBackgroundAlphaPct(): Int = getInt(K_UI_BG_ALPHA, DEFAULT_BG_ALPHA_PCT)
    suspend fun setBackgroundAlphaPct(pct: Int) = put(K_UI_BG_ALPHA, pct.coerceIn(0, 100).toString())

    // ── Device-temperature unit (U9 — no fan; show a labelled device temperature, C/F/K) ─────────
    val temperatureUnit: Flow<String> = repository.observeKv(K_UI_TEMP_UNIT).map { it ?: DEFAULT_TEMP_UNIT }
    suspend fun setTemperatureUnit(key: String) = put(K_UI_TEMP_UNIT, key)

    // ── Theme + font (item 25 — three themes + JSON import + a bundled font; persisted, exportable) ──
    // The custom-theme JSON is stored verbatim so a re-selection of "custom" reconstructs the palette.
    // Both read seams normalise: `ui.theme` is exportable, and importJson writes any allowlisted key
    // through unvalidated, so an old backup can re-inject the id of a since-retired palette. Every
    // id→palette resolver already falls back to Tron, but the Settings chip row selects on string
    // equality — without this it would show NOTHING selected while the app rendered the default.
    val themeId: Flow<String> =
        repository.observeKv(K_UI_THEME).map { normalizeThemeId(it ?: DEFAULT_THEME) }
    val fontId: Flow<String> = repository.observeKv(K_UI_FONT).map { it ?: DEFAULT_FONT }
    val customThemeJson: Flow<String?> = repository.observeKv(K_UI_CUSTOM_THEME)

    suspend fun currentThemeId(): String = normalizeThemeId(repository.getKv(K_UI_THEME) ?: DEFAULT_THEME)
    suspend fun currentFontId(): String = repository.getKv(K_UI_FONT) ?: DEFAULT_FONT
    suspend fun currentCustomThemeJson(): String? = repository.getKv(K_UI_CUSTOM_THEME)

    /**
     * Rewrite a persisted `ui.theme` that no longer names a palette this build carries, returning the id
     * it was coerced to — or null when the row already resolved and nothing was written. Run once at
     * startup (see [com.t1dm.app.RetiredThemeMigration]) so the stale id does not survive to be exported
     * again; a mid-session config import is covered by the normalisation on the read seams above.
     */
    suspend fun coerceRetiredThemeId(): String? {
        val raw = repository.getKv(K_UI_THEME) ?: return null
        val id = normalizeThemeId(raw)
        if (id == raw) return null
        put(K_UI_THEME, id)
        return id
    }

    suspend fun setThemeId(id: String) = put(K_UI_THEME, id)
    suspend fun setFontId(id: String) = put(K_UI_FONT, id)
    /** Persist a validated custom-theme JSON blob (the caller validates via `parseThemeJson` first). */
    suspend fun setCustomThemeJson(json: String) = put(K_UI_CUSTOM_THEME, json)

    // ── Custom Bézier curve templates (item 19 — the Settings custom-curve designers) ───────────
    // Stored as the compact `BezierCurve.encode` string under the exportable graph.* prefix.
    val carbBezier: Flow<String?> = repository.observeKv(K_CURVE_CARB_BEZIER)
    val insulinBezier: Flow<String?> = repository.observeKv(K_CURVE_INSULIN_BEZIER)
    suspend fun setCarbBezier(encoded: String) = put(K_CURVE_CARB_BEZIER, encoded)
    suspend fun setInsulinBezier(encoded: String) = put(K_CURVE_INSULIN_BEZIER, encoded)

    // ── The public build's first-run medical disclaimer. Deliberately OUTSIDE the exportable config
    // set (no `ui.` prefix, not an exact key): an acknowledgement is a statement about the person
    // holding the phone, not a setting, and importing someone else's backup must not answer it for
    // them. `wipeAllData` clears it, so a full reset genuinely returns to first-run.
    val disclaimerAcknowledged: Flow<Boolean> = boolFlow(K_DISCLAIMER_ACK, false)
    suspend fun acknowledgeDisclaimer() = put(K_DISCLAIMER_ACK, "1")

    // ── CGM sensor lifetime (I11) — a USER-ENTERED absolute expiry instant (epoch-ms). Because the
    // passive-advertisement sensor exposes no true age, the user enters remaining life and we store the
    // resulting expiry; the BG panel + CGM settings count it down. Device/sensor-specific, so it is
    // deliberately NOT part of the exportable config set. Empty/absent ⇒ null ⇒ no countdown shown.
    val sensorExpiryMs: Flow<Long?> = repository.observeKv(K_CGM_SENSOR_EXPIRY).map { it?.toLongOrNull() }
    suspend fun setSensorExpiryMs(ms: Long) = put(K_CGM_SENSOR_EXPIRY, ms.toString())
    suspend fun clearSensorExpiry() = put(K_CGM_SENSOR_EXPIRY, "")

    // ── Aggressive background scanning (the keep-screen-on "AOD" that keeps the locked BLE scan
    // alive). HyperOS/powerkeeper SUSPENDS a backgrounded scan the
    // instant the screen turns off — the only app-side defeat is to hold the display genuinely ON
    // (is_screen_on=1) behind a black/dim surface. This is a HEAVY, opt-in mode (the SoC never
    // deep-idles), so it defaults OFF. Device-specific (the `cgm.` prefix keeps it out of the
    // exportable config set). `show_glucose` renders a dim glucose read-out instead of pure black;
    // `only_charging` restricts the mode to the charger to bound the battery cost.
    val aggressiveScanEnabled: Flow<Boolean> = boolFlow(K_AGG_SCAN, false)
    val aggressiveShowGlucose: Flow<Boolean> = boolFlow(K_AGG_SHOW_BG, true)
    val aggressiveOnlyCharging: Flow<Boolean> = boolFlow(K_AGG_ONLY_CHARGING, false)

    suspend fun setAggressiveScanEnabled(on: Boolean) = put(K_AGG_SCAN, if (on) "1" else "0")
    suspend fun setAggressiveShowGlucose(on: Boolean) = put(K_AGG_SHOW_BG, if (on) "1" else "0")
    suspend fun setAggressiveOnlyCharging(on: Boolean) = put(K_AGG_ONLY_CHARGING, if (on) "1" else "0")

    // ── Last-logged insulin, per kind — STICKINESS, not a setting ───────────────────────────────
    //
    // The insulin panel picks its preset out of the shared clinical catalogue, and the writer commits
    // what the panel picked. These two keys only remember which one that was, so the panel reopens on
    // the insulin actually in use and the callers with no pick of their own (the accepted advisory
    // bolus, a debug quick action) inherit it. There is deliberately no Settings row: a settings
    // screen that also chose the insulin is exactly the arrangement that let the panel and the row
    // disagree, and nothing outside `AppContainer.logBolus`/`logBasal` writes them.
    //
    // Stored as the preset's stable label, unvalidated: the catalogue is the authority on which
    // labels exist and resolution falls through a stale one (see `resolveInsulinPreset`). The keys
    // sit OUTSIDE the exportable set (no `insulin.` prefix in CONFIG_PREFIXES, and not exact keys) —
    // this is per-device usage state like `cgm.sensor_expiry_ms`, not configuration, and importing
    // someone else's last dose would silently change which curve the next unpicked dose commits.
    suspend fun lastRapidPreset(): String? = repository.getKv(K_LAST_RAPID_PRESET)
    suspend fun lastBasalPreset(): String? = repository.getKv(K_LAST_BASAL_PRESET)
    suspend fun setLastRapidPreset(label: String) = put(K_LAST_RAPID_PRESET, label)
    suspend fun setLastBasalPreset(label: String) = put(K_LAST_BASAL_PRESET, label)

    // ── Push hold — how long a freshly logged meal/dose sits in the outbox before its FIRST send
    // attempt, and therefore how long the Logs panel can still withdraw it. Only the MEAL/DOSE event
    // pushes take it (never INGEST/PREDICTIONS/STATS/ALERT/PHOTO, and never the basal template);
    // it is stamped once, at enqueue, into `nextAttemptMs`. 0 = push at once, no withdrawal window.
    //
    // The hold cannot lose a record: MEAL/DOSE are exempt from age-eviction, so a held row waits
    // indefinitely rather than expiring, and the FORECAST never reads the queue at all — predictions
    // are reconstructed from the local `logged_*` rows through ChannelBuilder, so a held push changes
    // nothing about what the model sees.
    val pushHoldMin: Flow<Int> = repository.observeKv(K_PUSH_HOLD_MIN).map { decodePushHoldMin(it) }
    suspend fun currentPushHoldMin(): Int = decodePushHoldMin(repository.getKv(K_PUSH_HOLD_MIN))
    suspend fun setPushHoldMin(min: Int) = put(K_PUSH_HOLD_MIN, encodePushHoldMin(min))

    // ── Settings-search history — the three most recent COMMITTED queries, newest first ─────────
    // One kv row holding the whole list, the compound-value idiom the Bézier curves and the custom
    // theme already use. The key sits deliberately OUTSIDE the exportable set (no `search.` prefix
    // in CONFIG_PREFIXES, and not an exact key): a `ui.` key would ship the user's typed queries
    // inside every configuration backup they hand to someone, the same reasoning that keeps
    // `cgm.sensor_expiry_ms` and `death.enabled` out. `wipeAllData` clears it for free.
    val recentSearches: Flow<List<String>> =
        repository.observeKv(K_SEARCH_RECENT).map { decodeRecentSearches(it) }

    suspend fun pushRecentSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val kept = decodeRecentSearches(repository.getKv(K_SEARCH_RECENT))
            .filterNot { it.equals(q, ignoreCase = true) }
        put(K_SEARCH_RECENT, encodeRecentSearches(listOf(q) + kept))
    }

    suspend fun clearRecentSearches() = put(K_SEARCH_RECENT, encodeRecentSearches(emptyList()))

    // ── Config export / import (item 17 — versioned, via SAF) ──────────────────────────────────
    // Exports ONLY the config keys (the allowlisted prefixes) — never runtime state such as the
    // watch nonce ceilings (exporting/importing those across devices would risk (key,nonce) reuse)
    // or the liveness heartbeat. Secrets never live in kv (the rw token is Keystore-wrapped).

    suspend fun exportJson(): String {
        val kv = repository.allKv().filterKeys(::isConfigKey)
        val body = JSONObject()
        for ((k, v) in kv.toSortedMap()) body.put(k, v)
        return JSONObject()
            .put("format", CONFIG_FORMAT)
            .put("version", CONFIG_VERSION)
            .put("exportedAtMs", clock())
            .put("kv", body)
            .toString(2)
    }

    /**
     * Import a config JSON produced by [exportJson]. Fail-closed: a malformed document or a wrong
     * format tag throws with a plain-language message; only allowlisted config keys are written (a
     * tampered file cannot inject runtime state). Returns the number of keys applied.
     */
    suspend fun importJson(text: String): Int {
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("Not a valid config file (could not parse JSON).") }
        if (root.optString("format") != CONFIG_FORMAT) {
            throw IllegalArgumentException("Not a T1DM config file (missing or wrong format tag).")
        }
        val kv = root.optJSONObject("kv")
            ?: throw IllegalArgumentException("Config file has no settings block to import.")
        val pairs = mutableMapOf<String, String>()
        for (key in kv.keys()) {
            if (!isConfigKey(key)) continue
            val raw = kv.getString(key)
            val coerce = CONFIG_COERCE[key]
            val value = if (coerce == null) raw else coerce(raw)
            if (value != null) pairs[key] = value
        }
        if (pairs.isEmpty()) throw IllegalArgumentException("Config file contained no recognised settings.")
        repository.putKvBatch(pairs, clock())
        return pairs.size
    }

    companion object {
        private val DEF = AlarmConfig.DEFAULT

        /** The export/import allowlist predicate. A pure function of the constants below, lifted to
         *  the companion so the placement of a new key is host-testable without Room. */
        internal fun isConfigKey(key: String): Boolean =
            CONFIG_PREFIXES.any { key.startsWith(it) } || key in CONFIG_EXACT_KEYS

        /**
         * Per-key validation applied to an imported value before it is written. A key absent from
         * the table is copied verbatim — most config is free-form, and the alarm THRESHOLDS are
         * user-set without an upper limit by design.
         *
         * A key present here carries a contract a rail depends on, and import is the one writer that
         * can otherwise persist a value no stepper could produce: the file may be hand-edited, or
         * written by a build whose bounds differed. Returning null rejects the key, leaving the
         * setting at its default rather than importing a value the rail cannot honour.
         */
        internal val CONFIG_COERCE: Map<String, (String) -> String?> = mapOf(
            K_SNOOZE_MIN to { raw -> raw.toIntOrNull()?.let(::encodeSnoozeMin) },
            K_PUSH_HOLD_MIN to { raw -> raw.toIntOrNull()?.let(::encodePushHoldMin) },
        )

        const val DEFAULT_LOW_POWER_PCT = 20

        // Objective enum keys (persisted).
        const val OBJ_KOVATCHEV = "kovatchev"
        const val OBJ_MIN_TOR = "min_tor"
        const val OBJ_HIT_TARGET = "hit_target"

        // Rail identifiers (for setRail).
        const val RAIL_FRESHNESS = "freshness"
        const val RAIL_PREDICTED_LOW = "predicted_low"
        const val RAIL_IOB = "iob"
        const val RAIL_CONFIRM = "confirm"
        const val RAIL_HYPO = "hypo"

        private const val CONFIG_FORMAT = "t1dm.config"
        private const val CONFIG_VERSION = 1

        /** kv-key prefixes that constitute exportable configuration. */
        private val CONFIG_PREFIXES = listOf("alarm.", "alerts.", "power.", "calc.", "ui.", "graph.", "stats.")
        // NOTE: no blanket `inference.` prefix — that would sweep runtime telemetry into the export.
        // The four `alarm.overtemp_*` keys ride the existing `alarm.` prefix already. `death.enabled`
        // stays deliberately non-exportable; only the display-only death-clock OFFSETS are exported.
        private val CONFIG_EXACT_KEYS = setOf(
            "inference.warmup_hours",
            K_FORECAST_MODE,
            K_FORECAST_PERIOD_MIN,
            K_INF_LOG_DEBOUNCE_S,
            K_INF_THERMAL_ON,
            K_INF_MAX_TEMP_C,
            K_INF_WARN_MARGIN_C,
            K_INF_MAX_MODELS,
            K_INF_SAVGOL_WINDOW,
            K_DEATH_DKA_H,
            K_DEATH_COMA_H,
            K_DEATH_DEATH_H,
            K_PUSH_HOLD_MIN,
        )

        // ── Forecast cadence (PUBLIC — CgmScanService reads the mode) ─────────────────────────────
        const val K_FORECAST_MODE = "inference.forecast_mode"
        const val K_FORECAST_PERIOD_MIN = "inference.forecast_period_min"
        const val FORECAST_MODE_ADAPTIVE = "adaptive"
        const val FORECAST_MODE_TIMED = "timed"
        const val DEFAULT_FORECAST_PERIOD_MIN = 5
        const val FORECAST_PERIOD_MIN_MIN = 1
        const val FORECAST_PERIOD_MIN_MAX = 60

        // ── Log-driven re-forecast debounce (PUBLIC — see [logReforecastDebounceS]) ───────────────
        const val K_INF_LOG_DEBOUNCE_S = "inference.log_reforecast_debounce_s"
        const val DEFAULT_LOG_REFORECAST_DEBOUNCE_S = 4

        /** The stepper's maximum, and the ceiling no writer may exceed. Deliberately far below the
         *  5-min grid: past it a "prompt" re-run would land after the tick it exists to anticipate,
         *  which is the setting silently doing nothing rather than doing less. */
        const val MAX_LOG_REFORECAST_DEBOUNCE_S = 60

        /** The debounce persistence contract, extracted so the round-trip is host-testable without
         *  Room, as the snooze and push-hold pairs are. Both directions clamp to
         *  `0..`[MAX_LOG_REFORECAST_DEBOUNCE_S] — 0 being a legitimate "re-run at once" — and a
         *  garbage or unset read falls back to the default, so a value written by a build with a
         *  wider ceiling (or hand-edited into a config backup) cannot outlive this one. */
        internal fun encodeLogReforecastDebounceS(seconds: Int): String =
            seconds.coerceIn(0, MAX_LOG_REFORECAST_DEBOUNCE_S).toString()
        internal fun decodeLogReforecastDebounceS(raw: String?): Int =
            raw?.toIntOrNull()?.coerceIn(0, MAX_LOG_REFORECAST_DEBOUNCE_S) ?: DEFAULT_LOG_REFORECAST_DEBOUNCE_S

        // ── Thermal gate (PUBLIC) ─────────────────────────────────────────────────────────────────
        const val K_INF_THERMAL_ON = "inference.thermal_gate_enabled"
        const val K_INF_MAX_TEMP_C = "inference.max_temp_c"
        const val K_INF_WARN_MARGIN_C = "inference.thermal_warn_margin_c"
        const val DEFAULT_THERMAL_ON = true
        const val DEFAULT_MAX_TEMP_C = 45.0
        const val DEFAULT_WARN_MARGIN_C = 3.0

        // ── Running-set cap (PUBLIC) ──────────────────────────────────────────────────────────────
        const val K_INF_MAX_MODELS = "inference.max_models"
        const val DEFAULT_MAX_MODELS = 5
        const val INF_MAX_MODELS_MIN = 1
        const val INF_MAX_MODELS_MAX = 8

        // ── BG input filter (PUBLIC) ──────────────────────────────────────────────────────────────
        const val K_INF_SAVGOL_WINDOW = "inference.savgol_window"
        const val DEFAULT_SAVGOL_WINDOW = InferenceControllerDefaults.SAVGOL_WINDOW
        val SAVGOL_STOPS: List<Int> = InferenceControllerDefaults.SAVGOL_STOPS

        // ── Death-clock offsets (PUBLIC) ──────────────────────────────────────────────────────────
        const val K_DEATH_DKA_H = "death.dka_after_iob_zero_h"
        const val K_DEATH_COMA_H = "death.coma_after_dka_h"
        const val K_DEATH_DEATH_H = "death.death_after_coma_h"
        const val DEFAULT_DKA_AFTER_IOB_ZERO_H = 2.0
        const val DEFAULT_COMA_AFTER_DKA_H = 29.0
        const val DEFAULT_DEATH_AFTER_COMA_H = 59.0

        // ── Weak-signal alarm ─────────────────────────────────────────────────────────────────────
        private const val K_WEAK_SIGNAL_ON = "alarm.weak_signal_enabled"
        private const val K_WEAK_SIGNAL_DBM = "alarm.weak_signal_dbm"
        private const val K_WEAK_SIGNAL_SUSTAIN = "alarm.weak_signal_sustain_min"

        // ── Over-temperature alarm (PUBLIC) ───────────────────────────────────────────────────────
        const val K_OVERTEMP_ENABLED = "alarm.overtemp_enabled"
        const val K_OVERTEMP_ALERT_C = "alarm.overtemp_alert_c"
        const val K_OVERTEMP_CLEAR_C = "alarm.overtemp_clear_c"
        const val K_OVERTEMP_CRITICAL = "alarm.overtemp_critical"
        const val DEFAULT_OVERTEMP_ENABLED = true
        const val DEFAULT_OVERTEMP_ALERT_C = 44.0
        const val DEFAULT_OVERTEMP_CLEAR_C = 41.0
        const val DEFAULT_OVERTEMP_CRITICAL = false

        // ── kv keys ────────────────────────────────────────────────────────────────────────────
        private const val K_ALARM_URGENT_LOW = "alarm.urgent_low_mgdl"
        private const val K_ALARM_LOW = "alarm.low_mgdl"
        private const val K_ALARM_HIGH = "alarm.high_mgdl"
        private const val K_ALARM_URGENT_HIGH = "alarm.urgent_high_mgdl"
        private const val K_LOSS_MIN = "alarm.loss_min"
        private const val K_LOSS_ESCALATED_MIN = "alarm.loss_escalated_min"
        private const val K_REPEAT_CADENCE = "alarm.repeat_cadence_min"
        private const val K_MIN_ACTUATION = "alarm.min_actuation_min"
        private const val K_SNOOZE_MIN = "alarm.snooze_min"
        const val DEFAULT_SNOOZE_MIN = 15

        /** The ceiling §3.6 C1 rests on, and the stepper's own maximum. A snooze is time-bounded, so
         *  no writer — not the UI, not [importJson] — may persist a silence longer than this. */
        const val MAX_SNOOZE_MIN = 60

        /** The snooze-duration persistence contract, extracted so the round-trip is host-testable
         *  without Room: both directions clamp to 1..[MAX_SNOOZE_MIN] (a snooze is always
         *  TIME-BOUNDED, §3.6 C1); reads fall back to [DEFAULT_SNOOZE_MIN] when unset/garbage.
         *  The read clamps too, so a value persisted by an older build cannot outlive this one. */
        internal fun encodeSnoozeMin(min: Int): String = min.coerceIn(1, MAX_SNOOZE_MIN).toString()
        internal fun decodeSnoozeMin(raw: String?): Int =
            raw?.toIntOrNull()?.coerceIn(1, MAX_SNOOZE_MIN) ?: DEFAULT_SNOOZE_MIN

        // ── Push hold (see [pushHoldMin]) ─────────────────────────────────────────────────────────
        const val K_PUSH_HOLD_MIN = "sync.push_hold_min"
        const val DEFAULT_PUSH_HOLD_MIN = 15

        /** The stepper's maximum, and the ceiling no writer may exceed. A hold is a withdrawal window,
         *  not a sync policy: past a couple of hours the phone is simply not mirroring its own record,
         *  and every reader of the server copy — the console, a second session — is that far behind. */
        const val MAX_PUSH_HOLD_MIN = 120

        /** The push-hold persistence contract, extracted so the round-trip is host-testable without
         *  Room, as the snooze pair is. Both directions clamp to `0..`[MAX_PUSH_HOLD_MIN] — 0 being a
         *  legitimate "send immediately" — and a garbage or unset read falls back to the default, so a
         *  value written by a build with a wider ceiling cannot outlive this one. */
        internal fun encodePushHoldMin(min: Int): String = min.coerceIn(0, MAX_PUSH_HOLD_MIN).toString()
        internal fun decodePushHoldMin(raw: String?): Int =
            raw?.toIntOrNull()?.coerceIn(0, MAX_PUSH_HOLD_MIN) ?: DEFAULT_PUSH_HOLD_MIN

        /** Settings-search history: a kv row outside the export allowlist (see [recentSearches]). */
        private const val K_SEARCH_RECENT = "search.recent_settings"
        const val RECENT_SEARCH_LIMIT = 3

        /**
         * The persistence contract for [recentSearches], extracted for the same reason the snooze
         * pair is: the round-trip and the fail-soft read are then host-testable without Room.
         *
         * A newline-separated list rather than the `JSONArray` the Bézier/theme rows would suggest —
         * `org.json` is an Android class, stubbed on the host JVM, so a codec built on it cannot be
         * unit-tested at all (see the note in ConfigBackupTest). The search field is single-line, so
         * the separator cannot occur in a query; encoding folds one in anyway, since `importJson`
         * writes allowlisted keys through unvalidated and a smuggled newline must not become a second
         * entry. Reads are total for the same reason: garbage degrades to "no history", never a throw
         * on a settings page.
         */
        internal fun encodeRecentSearches(queries: List<String>): String =
            queries
                .map { it.replace('\n', ' ').replace('\r', ' ').trim() }
                .filter { it.isNotEmpty() }
                .take(RECENT_SEARCH_LIMIT)
                .joinToString("\n")

        internal fun decodeRecentSearches(raw: String?): List<String> =
            raw?.split('\n').orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(RECENT_SEARCH_LIMIT)

        private const val K_VIB_WARN = "alerts.vib.warning"
        private const val K_VIB_CRIT = "alerts.vib.critical"
        private const val K_BYPASS_DND = "alerts.bypass_dnd_bool"
        private const val K_CRIT_SOUND_ON = "alerts.sound.critical_on"
        private const val K_WARN_SOUND_ON = "alerts.sound.warning_on"

        private const val K_POWER_ENABLED = "power.low_enabled"
        private const val K_POWER_PCT = "power.low_pct"
        private const val K_POWER_OS_SAVER = "power.use_os_saver"

        private const val K_CALC_TARGET_LOW = "calc.target_low"
        private const val K_CALC_TARGET_HIGH = "calc.target_high"
        private const val K_CALC_TARGET_MID = "calc.target_mid"
        private const val K_CALC_OBJECTIVE = "calc.objective"
        private const val K_CALC_HYPO_W = "calc.hypo_weight"
        private const val K_CALC_HYPER_W = "calc.hyper_weight"
        private const val K_CALC_PRED_LOW = "calc.predicted_low"
        private const val K_CALC_IOB_CEIL = "calc.iob_ceiling"
        private const val K_CALC_FRESHNESS_MIN = "calc.freshness_min"
        private const val K_CALC_GRID_MAX = "calc.grid_max_u"
        private const val K_CALC_GRID_STEP = "calc.grid_step_u"
        private const val K_RAIL_FRESH = "calc.rail_freshness"
        private const val K_RAIL_PREDLOW = "calc.rail_predicted_low"
        private const val K_RAIL_IOB = "calc.rail_iob"
        private const val K_RAIL_CONFIRM = "calc.rail_confirm"
        private const val K_RAIL_HYPO = "calc.rail_hypo"

        private const val K_DEATH = "death.enabled"

        private const val K_UI_ANIMATIONS = "ui.animations"
        private const val K_UI_HAPTICS = "ui.haptics"
        val DEFAULT_HAPTICS: String = HapticStrength.DEFAULT.name
        private const val K_UI_BG_ALPHA = "ui.background_alpha"
        const val DEFAULT_BG_ALPHA_PCT = 15
        private const val K_UI_THEME = "ui.theme"
        private const val K_UI_FONT = "ui.font"
        private const val K_UI_CUSTOM_THEME = "ui.custom_theme_json"
        private const val K_UI_TEMP_UNIT = "ui.temp_unit"
        const val DEFAULT_TEMP_UNIT = "C"
        private const val K_CURVE_CARB_BEZIER = "graph.curve_carb_bezier"
        private const val K_CURVE_INSULIN_BEZIER = "graph.curve_insulin_bezier"
        private const val K_DISCLAIMER_ACK = "disclaimer.acknowledged"
        private const val K_CGM_SENSOR_EXPIRY = "cgm.sensor_expiry_ms"
        private const val K_AGG_SCAN = "cgm.aggressive_scan"
        private const val K_AGG_SHOW_BG = "cgm.aggressive_show_glucose"
        private const val K_AGG_ONLY_CHARGING = "cgm.aggressive_only_charging"
        /** Usage state, not configuration — see the note on [lastRapidPreset] for why the prefix is
         *  outside [CONFIG_PREFIXES]. No default label lives here: the catalogue's own first entry of
         *  the family is the fallback, so this build cannot name an insulin the catalogue dropped. */
        internal const val K_LAST_RAPID_PRESET = "insulin.last_rapid_preset"
        internal const val K_LAST_BASAL_PRESET = "insulin.last_basal_preset"
        const val DEFAULT_THEME = "tron"
        const val DEFAULT_FONT = "system"
    }
}
