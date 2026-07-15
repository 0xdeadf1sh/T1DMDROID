package com.t1dm.app.settings

import com.t1dm.alerts.AlarmConfig
import com.t1dm.alerts.AlarmSeverity
import com.t1dm.alerts.VibrationPreset
import com.t1dm.calc.Asymmetry
import com.t1dm.calc.CalcConfig
import com.t1dm.calc.GridSpec
import com.t1dm.calc.Objective
import com.t1dm.calc.RailToggles
import com.t1dm.calc.TargetRange as CalcTargetRange
import com.t1dm.core.model.AlertThresholds
import com.t1dm.data.T1dmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * The complete, kv-backed configuration surface for the Settings hub (Phase 7C —
 * items 14 & 17, "expose EVERY knob"). Every user-tunable knob that the rest of the app boots with a
 * hard-coded default for is persisted here so a restart restores it, and — per safety-posture.md —
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

    // ── Deterministic alarm thresholds + loss-of-signal (safety-posture §3.6-A) ────────────────
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

    // ── Low-power / battery-saver (progress.md Q9 — default 20 %, configurable) ─────────────────

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

    val deathMode: Flow<Boolean> = boolFlow(K_DEATH, false)
    suspend fun currentDeathMode(): Boolean = getBool(K_DEATH, false)
    suspend fun setDeathMode(on: Boolean) = put(K_DEATH, if (on) "1" else "0")

    // ── UI (ux-decisions.md — a global "disable all animations" toggle) ────────────────────────

    val animationsEnabled: Flow<Boolean> = boolFlow(K_UI_ANIMATIONS, true)
    suspend fun setAnimationsEnabled(on: Boolean) = put(K_UI_ANIMATIONS, if (on) "1" else "0")

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
    val themeId: Flow<String> = repository.observeKv(K_UI_THEME).map { it ?: DEFAULT_THEME }
    val fontId: Flow<String> = repository.observeKv(K_UI_FONT).map { it ?: DEFAULT_FONT }
    val customThemeJson: Flow<String?> = repository.observeKv(K_UI_CUSTOM_THEME)

    suspend fun currentThemeId(): String = repository.getKv(K_UI_THEME) ?: DEFAULT_THEME
    suspend fun currentFontId(): String = repository.getKv(K_UI_FONT) ?: DEFAULT_FONT
    suspend fun currentCustomThemeJson(): String? = repository.getKv(K_UI_CUSTOM_THEME)

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

    // ── Clinical insulin PRESET selection (issue 19 — the OPT-IN, off-distribution rapid/basal
    // curves). Persisted by the preset's stable label; the default is the in-distribution simulator
    // shape, so a fresh install (and every unchanged install) keeps forecasts in-distribution. The
    // apply-at-log path (AppContainer.logBolus/logBasal) resolves the selected label to its spec.
    // ── CGM sensor lifetime (I11) — a USER-ENTERED absolute expiry instant (epoch-ms). Because the
    // passive-advertisement sensor exposes no true age, the user enters remaining life and we store the
    // resulting expiry; the BG panel + CGM settings count it down. Device/sensor-specific, so it is
    // deliberately NOT part of the exportable config set. Empty/absent ⇒ null ⇒ no countdown shown.
    val sensorExpiryMs: Flow<Long?> = repository.observeKv(K_CGM_SENSOR_EXPIRY).map { it?.toLongOrNull() }
    suspend fun setSensorExpiryMs(ms: Long) = put(K_CGM_SENSOR_EXPIRY, ms.toString())
    suspend fun clearSensorExpiry() = put(K_CGM_SENSOR_EXPIRY, "")

    // ── Aggressive background scanning (the keep-screen-on "AOD" that keeps the locked BLE scan
    // alive; cgm-ingestion / build-gotchas). HyperOS/powerkeeper SUSPENDS a backgrounded scan the
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

    val selectedRapidPreset: Flow<String> =
        repository.observeKv(K_CURVE_RAPID_PRESET).map { it ?: DEFAULT_RAPID_PRESET_LABEL }
    val selectedBasalPreset: Flow<String> =
        repository.observeKv(K_CURVE_BASAL_PRESET).map { it ?: DEFAULT_BASAL_PRESET_LABEL }
    suspend fun currentRapidPreset(): String = repository.getKv(K_CURVE_RAPID_PRESET) ?: DEFAULT_RAPID_PRESET_LABEL
    suspend fun currentBasalPreset(): String = repository.getKv(K_CURVE_BASAL_PRESET) ?: DEFAULT_BASAL_PRESET_LABEL
    suspend fun setRapidPreset(label: String) = put(K_CURVE_RAPID_PRESET, label)
    suspend fun setBasalPreset(label: String) = put(K_CURVE_BASAL_PRESET, label)

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
            if (isConfigKey(key)) pairs[key] = kv.getString(key)
        }
        if (pairs.isEmpty()) throw IllegalArgumentException("Config file contained no recognised settings.")
        repository.putKvBatch(pairs, clock())
        return pairs.size
    }

    private fun isConfigKey(key: String): Boolean =
        CONFIG_PREFIXES.any { key.startsWith(it) } || key in CONFIG_EXACT_KEYS

    companion object {
        private val DEF = AlarmConfig.DEFAULT

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
            K_INF_THERMAL_ON,
            K_INF_MAX_TEMP_C,
            K_INF_WARN_MARGIN_C,
            K_INF_MAX_MODELS,
            K_DEATH_DKA_H,
            K_DEATH_COMA_H,
            K_DEATH_DEATH_H,
        )

        // ── Forecast cadence (PUBLIC — CgmScanService reads the mode) ─────────────────────────────
        const val K_FORECAST_MODE = "inference.forecast_mode"
        const val K_FORECAST_PERIOD_MIN = "inference.forecast_period_min"
        const val FORECAST_MODE_ADAPTIVE = "adaptive"
        const val FORECAST_MODE_TIMED = "timed"
        const val DEFAULT_FORECAST_PERIOD_MIN = 5
        const val FORECAST_PERIOD_MIN_MIN = 1
        const val FORECAST_PERIOD_MIN_MAX = 60

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

        // ── Death-clock offsets (PUBLIC) ──────────────────────────────────────────────────────────
        const val K_DEATH_DKA_H = "death.dka_after_iob_zero_h"
        const val K_DEATH_COMA_H = "death.coma_after_dka_h"
        const val K_DEATH_DEATH_H = "death.death_after_coma_h"
        const val DEFAULT_DKA_AFTER_IOB_ZERO_H = 2.0
        const val DEFAULT_COMA_AFTER_DKA_H = 29.0
        const val DEFAULT_DEATH_AFTER_COMA_H = 59.0

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

        /** The snooze-duration persistence contract, extracted so the round-trip is host-testable
         *  without Room: writes floor the value at 1 min (a snooze is always TIME-BOUNDED, §3.6 C1);
         *  reads fall back to [DEFAULT_SNOOZE_MIN] when unset/garbage. */
        internal fun encodeSnoozeMin(min: Int): String = min.coerceAtLeast(1).toString()
        internal fun decodeSnoozeMin(raw: String?): Int = raw?.toIntOrNull() ?: DEFAULT_SNOOZE_MIN

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
        private const val K_UI_BG_ALPHA = "ui.background_alpha"
        const val DEFAULT_BG_ALPHA_PCT = 15
        private const val K_UI_THEME = "ui.theme"
        private const val K_UI_FONT = "ui.font"
        private const val K_UI_CUSTOM_THEME = "ui.custom_theme_json"
        private const val K_UI_TEMP_UNIT = "ui.temp_unit"
        const val DEFAULT_TEMP_UNIT = "C"
        private const val K_CURVE_CARB_BEZIER = "graph.curve_carb_bezier"
        private const val K_CURVE_INSULIN_BEZIER = "graph.curve_insulin_bezier"
        private const val K_CGM_SENSOR_EXPIRY = "cgm.sensor_expiry_ms"
        private const val K_AGG_SCAN = "cgm.aggressive_scan"
        private const val K_AGG_SHOW_BG = "cgm.aggressive_show_glucose"
        private const val K_AGG_ONLY_CHARGING = "cgm.aggressive_only_charging"
        private const val K_CURVE_RAPID_PRESET = "graph.curve_rapid_preset"
        private const val K_CURVE_BASAL_PRESET = "graph.curve_basal_preset"
        /** The clinical defaults — NovoRapid rapid / Lantus basal (must equal the labels in `insulin_preset_catalog`). */
        const val DEFAULT_RAPID_PRESET_LABEL = "Aspart · NovoRapid/Novolog"
        const val DEFAULT_BASAL_PRESET_LABEL = "Glargine U100 · Lantus"
        const val DEFAULT_THEME = "tron"
        const val DEFAULT_FONT = "system"
    }
}
