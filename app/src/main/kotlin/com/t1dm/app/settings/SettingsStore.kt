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
import com.t1dm.data.curve.ExerciseDisposal
import com.t1dm.inference.InferenceControllerDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class BackupRunOk(val atMs: Long, val bytes: Long, val rows: Int)

class BackupRunError(val atMs: Long, val message: String)

/** kv-backed config surface; thresholds UNBOUNDED, floored non-negative, never capped. */
class SettingsStore(
    private val repository: T1dmRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
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

    // §3.6-A: ordering (urgentLow<low<=high<urgentHigh) is NOT enforced; screen warns only.

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

    /** Minutes of presentation silence; always TIME-BOUNDED (§3.6 C1), floored at 1. */
    val snoozeMin: Flow<Int> = intFlow(K_SNOOZE_MIN, DEFAULT_SNOOZE_MIN)

    suspend fun setLossWindows(lossMin: Int, lossEscalatedMin: Int) {
        put(K_LOSS_MIN, lossMin.coerceAtLeast(1).toString())
        put(K_LOSS_ESCALATED_MIN, lossEscalatedMin.coerceAtLeast(1).toString())
    }

    suspend fun setRepeatCadence(min: Int) = put(K_REPEAT_CADENCE, min.coerceAtLeast(1).toString())
    suspend fun setMinActuationMin(min: Int) = put(K_MIN_ACTUATION, min.coerceAtLeast(0).toString())
    suspend fun currentSnoozeMin(): Int = decodeSnoozeMin(repository.getKv(K_SNOOZE_MIN))
    suspend fun setSnoozeMin(min: Int) = put(K_SNOOZE_MIN, encodeSnoozeMin(min))

    // Connection RSSI (dBm, unbounded) below which the link is weak; distinct from loss-of-signal.
    val weakSignalEnabled: Flow<Boolean> = boolFlow(K_WEAK_SIGNAL_ON, DEF.weakSignalEnabled)
    val weakSignalDbm: Flow<Int> = intFlow(K_WEAK_SIGNAL_DBM, DEF.weakSignalDbm)
    val weakSignalSustainMin: Flow<Int> = intFlow(K_WEAK_SIGNAL_SUSTAIN, DEF.weakSignalSustainMin)

    suspend fun setWeakSignal(enabled: Boolean, dbm: Int, sustainMin: Int) {
        put(K_WEAK_SIGNAL_ON, if (enabled) "1" else "0")
        put(K_WEAK_SIGNAL_DBM, dbm.toString())
        put(K_WEAK_SIGNAL_SUSTAIN, sustainMin.coerceAtLeast(0).toString())
    }

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

    // Sound URIs live in AppContainer, not here.

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

    val lowPowerEnabled: Flow<Boolean> = boolFlow(K_POWER_ENABLED, true)
    val lowPowerPercent: Flow<Int> = intFlow(K_POWER_PCT, DEFAULT_LOW_POWER_PCT)
    val lowPowerUseOsSaver: Flow<Boolean> = boolFlow(K_POWER_OS_SAVER, true)

    suspend fun setLowPowerEnabled(on: Boolean) = put(K_POWER_ENABLED, if (on) "1" else "0")
    suspend fun setLowPowerPercent(pct: Int) = put(K_POWER_PCT, pct.coerceIn(0, 100).toString())
    suspend fun setLowPowerUseOsSaver(on: Boolean) = put(K_POWER_OS_SAVER, if (on) "1" else "0")

    suspend fun currentLowPowerEnabled(): Boolean = getBool(K_POWER_ENABLED, true)
    suspend fun currentLowPowerPercent(): Int = getInt(K_POWER_PCT, DEFAULT_LOW_POWER_PCT)
    suspend fun currentLowPowerUseOsSaver(): Boolean = getBool(K_POWER_OS_SAVER, true)

    // ADAPTIVE re-forecasts every reading; TIMED fires on an N-minute phone-clock grid.

    val forecastMode: Flow<String> =
        repository.observeKv(K_FORECAST_MODE).map { it ?: FORECAST_MODE_ADAPTIVE }
    val forecastPeriodMin: Flow<Int> = intFlow(K_FORECAST_PERIOD_MIN, DEFAULT_FORECAST_PERIOD_MIN)

    suspend fun currentForecastMode(): String = repository.getKv(K_FORECAST_MODE) ?: FORECAST_MODE_ADAPTIVE
    suspend fun currentForecastPeriodMin(): Int = getInt(K_FORECAST_PERIOD_MIN, DEFAULT_FORECAST_PERIOD_MIN)
    suspend fun setForecastMode(mode: String) = put(K_FORECAST_MODE, mode)
    suspend fun setForecastPeriodMin(min: Int) =
        put(K_FORECAST_PERIOD_MIN, min.coerceIn(FORECAST_PERIOD_MIN_MIN, FORECAST_PERIOD_MIN_MAX).toString())

    // Debounce before a log re-runs the model; coalesces meal+bolus, clamped under the grid.
    val logReforecastDebounceS: Flow<Int> =
        repository.observeKv(K_INF_LOG_DEBOUNCE_S).map { decodeLogReforecastDebounceS(it) }
    suspend fun currentLogReforecastDebounceS(): Int =
        decodeLogReforecastDebounceS(repository.getKv(K_INF_LOG_DEBOUNCE_S))
    suspend fun setLogReforecastDebounceS(seconds: Int) =
        put(K_INF_LOG_DEBOUNCE_S, encodeLogReforecastDebounceS(seconds))

    // Pauses inference on battery-sensor °C; enabled by default, active even in DEATH (D4).

    val thermalGateEnabled: Flow<Boolean> = boolFlow(K_INF_THERMAL_ON, DEFAULT_THERMAL_ON)
    val inferenceMaxTempC: Flow<Double> = doubleFlow(K_INF_MAX_TEMP_C, DEFAULT_MAX_TEMP_C)
    val thermalWarnMarginC: Flow<Double> = doubleFlow(K_INF_WARN_MARGIN_C, DEFAULT_WARN_MARGIN_C)

    suspend fun currentThermalGateEnabled(): Boolean = getBool(K_INF_THERMAL_ON, DEFAULT_THERMAL_ON)
    suspend fun currentInferenceMaxTempC(): Double = getDouble(K_INF_MAX_TEMP_C, DEFAULT_MAX_TEMP_C)
    suspend fun currentThermalWarnMarginC(): Double = getDouble(K_INF_WARN_MARGIN_C, DEFAULT_WARN_MARGIN_C)

    suspend fun setThermalGateEnabled(on: Boolean) = put(K_INF_THERMAL_ON, if (on) "1" else "0")
    suspend fun setInferenceMaxTempC(c: Double) = put(K_INF_MAX_TEMP_C, c.coerceAtLeast(0.0).toString())
    suspend fun setThermalWarnMarginC(c: Double) = put(K_INF_WARN_MARGIN_C, c.coerceAtLeast(0.0).toString())

    // How many discovered models run each cycle; only the SELECTED one feeds dosing.

    val inferenceMaxModels: Flow<Int> = repository.observeKv(K_INF_MAX_MODELS)
        .map { it?.toIntOrNull()?.coerceIn(INF_MAX_MODELS_MIN, INF_MAX_MODELS_MAX) ?: DEFAULT_MAX_MODELS }

    suspend fun currentInferenceMaxModels(): Int =
        repository.getKv(K_INF_MAX_MODELS)?.toIntOrNull()?.coerceIn(INF_MAX_MODELS_MIN, INF_MAX_MODELS_MAX) ?: DEFAULT_MAX_MODELS

    suspend fun setInferenceMaxModels(n: Int) =
        put(K_INF_MAX_MODELS, n.coerceIn(INF_MAX_MODELS_MIN, INF_MAX_MODELS_MAX).toString())

    // Savitzky-Golay window for the BG channel (INFERENCE.md §7.1); must be odd, snapped to detent.

    val savgolWindow: Flow<Int> = repository.observeKv(K_INF_SAVGOL_WINDOW)
        .map { InferenceControllerDefaults.nearestSmoothingStop(it?.toIntOrNull() ?: DEFAULT_SAVGOL_WINDOW) }

    suspend fun currentSavgolWindow(): Int = InferenceControllerDefaults.nearestSmoothingStop(
        repository.getKv(K_INF_SAVGOL_WINDOW)?.toIntOrNull() ?: DEFAULT_SAVGOL_WINDOW,
    )

    suspend fun setSavgolWindow(window: Int) =
        put(K_INF_SAVGOL_WINDOW, InferenceControllerDefaults.nearestSmoothingStop(window).toString())

    // DISPLAY-ONLY forward offsets in hours from each prior landmark; no §3.6 gate reads these.

    val dkaAfterIobZeroH: Flow<Double> = doubleFlow(K_DEATH_DKA_H, DEFAULT_DKA_AFTER_IOB_ZERO_H)
    val comaAfterDkaH: Flow<Double> = doubleFlow(K_DEATH_COMA_H, DEFAULT_COMA_AFTER_DKA_H)
    val deathAfterComaH: Flow<Double> = doubleFlow(K_DEATH_DEATH_H, DEFAULT_DEATH_AFTER_COMA_H)

    suspend fun setDkaAfterIobZeroH(h: Double) = put(K_DEATH_DKA_H, h.coerceAtLeast(0.0).toString())
    suspend fun setComaAfterDkaH(h: Double) = put(K_DEATH_COMA_H, h.coerceAtLeast(0.0).toString())
    suspend fun setDeathAfterComaH(h: Double) = put(K_DEATH_DEATH_H, h.coerceAtLeast(0.0).toString())

    // Battery-°C alert, distinct from the inference gate; hysteresis alertC/clearC, exempt D4.

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

    private val calcDef = CalcConfig()

    val calcTargetLow: Flow<Double> = doubleFlow(K_CALC_TARGET_LOW, calcDef.target.lowMgdl)
    val calcTargetHigh: Flow<Double> = doubleFlow(K_CALC_TARGET_HIGH, calcDef.target.highMgdl)
    val calcTargetMid: Flow<Double> = doubleFlow(K_CALC_TARGET_MID, calcDef.target.targetMgdl)
    val calcObjective: Flow<String> = repository.observeKv(K_CALC_OBJECTIVE).map { it ?: OBJ_KOVATCHEV }
    val calcHypoWeight: Flow<Double> = doubleFlow(K_CALC_HYPO_W, calcDef.asymmetry.hypoWeight)
    val calcHyperWeight: Flow<Double> = doubleFlow(K_CALC_HYPER_W, calcDef.asymmetry.hyperWeight)
    val calcPredictedLow: Flow<Double> = doubleFlow(K_CALC_PRED_LOW, calcDef.predictedLowThresholdMgdl)
    val calcIobCeiling: Flow<Double> = doubleFlow(K_CALC_IOB_CEIL, calcDef.iobCeilingU)
    val calcGridMaxU: Flow<Double> = doubleFlow(K_CALC_GRID_MAX, calcDef.grid.maxU)
    val calcGridStepU: Flow<Double> = doubleFlow(K_CALC_GRID_STEP, calcDef.grid.stepU)

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
    suspend fun setCalcGrid(maxU: Double, stepU: Double) {
        put(K_CALC_GRID_MAX, maxU.coerceAtLeast(0.0).toString())
        put(K_CALC_GRID_STEP, stepU.coerceAtLeast(0.1).toString())
    }

    suspend fun setRail(rail: String, on: Boolean) {
        val key = when (rail) {
            RAIL_PREDICTED_LOW -> K_RAIL_PREDLOW
            RAIL_IOB -> K_RAIL_IOB
            RAIL_CONFIRM -> K_RAIL_CONFIRM
            RAIL_HYPO -> K_RAIL_HYPO
            else -> return
        }
        put(key, if (on) "1" else "0")
    }

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
            predictedLowThresholdMgdl = getDouble(K_CALC_PRED_LOW, calcDef.predictedLowThresholdMgdl),
            iobCeilingU = getDouble(K_CALC_IOB_CEIL, calcDef.iobCeilingU),
        )
        if (!currentDeathMode()) return assembled
        // §3.6 DEATH override: every optional rail off, thresholds neutralised; DoseAdvisor is not.
        return assembled.copy(
            // ALL_OFF not a list: a rail added to RailToggles, not repeated here, stays standing.
            rails = RailToggles.ALL_OFF,
            predictedLowThresholdMgdl = 0.0,
            iobCeilingU = Double.MAX_VALUE,
        )
    }

    // Total-silence override, not exportable; public flavor DeathFlavor.SUPPORTED=false forces off.
    val deathMode: Flow<Boolean> =
        if (DeathFlavor.SUPPORTED) boolFlow(K_DEATH, false) else flowOf(false)
    suspend fun currentDeathMode(): Boolean = DeathFlavor.SUPPORTED && getBool(K_DEATH, false)
    suspend fun setDeathMode(on: Boolean) {
        if (DeathFlavor.SUPPORTED) put(K_DEATH, if (on) "1" else "0")
    }

    val animationsEnabled: Flow<Boolean> = boolFlow(K_UI_ANIMATIONS, true)
    /** One-shot read for headless surfaces; Flow.first() can park a cold process, getKv can't. */
    suspend fun currentAnimationsEnabled(): Boolean = getBool(K_UI_ANIMATIONS, true)
    suspend fun setAnimationsEnabled(on: Boolean) = put(K_UI_ANIMATIONS, if (on) "1" else "0")

    // Volume up = Meals, down = Insulin; stands down during any alarm, gated in MainActivity.
    val volumeNavEnabled: Flow<Boolean> = boolFlow(K_UI_VOLUME_NAV, true)
    suspend fun setVolumeNavEnabled(on: Boolean) = put(K_UI_VOLUME_NAV, if (on) "1" else "0")

    // Not alerts.vib.* (§3.6-A alarm actuation), never attenuated by a UI comfort preference.
    val hapticsLevel: Flow<String> = repository.observeKv(K_UI_HAPTICS).map { it ?: DEFAULT_HAPTICS }
    suspend fun currentHapticsLevel(): HapticStrength = HapticStrength.forKey(repository.getKv(K_UI_HAPTICS))
    suspend fun setHapticsLevel(level: HapticStrength) = put(K_UI_HAPTICS, level.name)

    // Per-theme backdrop opacity, 0–100 %; 0 = off.
    val backgroundAlphaPct: Flow<Int> = intFlow(K_UI_BG_ALPHA, DEFAULT_BG_ALPHA_PCT)
    suspend fun currentBackgroundAlphaPct(): Int = getInt(K_UI_BG_ALPHA, DEFAULT_BG_ALPHA_PCT)
    suspend fun setBackgroundAlphaPct(pct: Int) = put(K_UI_BG_ALPHA, pct.coerceIn(0, 100).toString())

    val temperatureUnit: Flow<String> = repository.observeKv(K_UI_TEMP_UNIT).map { it ?: DEFAULT_TEMP_UNIT }
    suspend fun setTemperatureUnit(key: String) = put(K_UI_TEMP_UNIT, key)

    // Custom-theme JSON stored verbatim; both read seams normalise a re-injected retired id.
    val themeId: Flow<String> =
        repository.observeKv(K_UI_THEME).map { normalizeThemeId(it ?: DEFAULT_THEME) }
    val fontId: Flow<String> = repository.observeKv(K_UI_FONT).map { it ?: DEFAULT_FONT }
    val customThemeJson: Flow<String?> = repository.observeKv(K_UI_CUSTOM_THEME)

    suspend fun currentThemeId(): String = normalizeThemeId(repository.getKv(K_UI_THEME) ?: DEFAULT_THEME)
    suspend fun currentFontId(): String = repository.getKv(K_UI_FONT) ?: DEFAULT_FONT
    suspend fun currentCustomThemeJson(): String? = repository.getKv(K_UI_CUSTOM_THEME)

    /** Rewrites a retired ui.theme id; null when already resolved. Run once at startup. */
    suspend fun coerceRetiredThemeId(): String? {
        val raw = repository.getKv(K_UI_THEME) ?: return null
        val id = normalizeThemeId(raw)
        if (id == raw) return null
        put(K_UI_THEME, id)
        return id
    }

    suspend fun setThemeId(id: String) = put(K_UI_THEME, id)
    suspend fun setFontId(id: String) = put(K_UI_FONT, id)
    /** The caller validates via `parseThemeJson` first. */
    suspend fun setCustomThemeJson(json: String) = put(K_UI_CUSTOM_THEME, json)

    // The compact `BezierCurve.encode` string.
    val carbBezier: Flow<String?> = repository.observeKv(K_CURVE_CARB_BEZIER)
    val insulinBezier: Flow<String?> = repository.observeKv(K_CURVE_INSULIN_BEZIER)
    suspend fun setCarbBezier(encoded: String) = put(K_CURVE_CARB_BEZIER, encoded)
    suspend fun setInsulinBezier(encoded: String) = put(K_CURVE_INSULIN_BEZIER, encoded)

    // Sensor total lifetime in days; time-left derives from elapsed age; not exportable.
    val sensorLifeDays: Flow<Int> = repository.observeKv(K_CGM_SENSOR_LIFE_DAYS)
        .map { it?.toIntOrNull()?.coerceIn(SENSOR_LIFE_DAYS_MIN, SENSOR_LIFE_DAYS_MAX) ?: DEFAULT_SENSOR_LIFE_DAYS }
    suspend fun currentSensorLifeDays(): Int =
        repository.getKv(K_CGM_SENSOR_LIFE_DAYS)?.toIntOrNull()?.coerceIn(SENSOR_LIFE_DAYS_MIN, SENSOR_LIFE_DAYS_MAX) ?: DEFAULT_SENSOR_LIFE_DAYS
    suspend fun setSensorLifeDays(days: Int) =
        put(K_CGM_SENSOR_LIFE_DAYS, days.coerceIn(SENSOR_LIFE_DAYS_MIN, SENSOR_LIFE_DAYS_MAX).toString())

    // DEFAULT OFF: advertised name may embed the sensor serial; not exportable; drawing only.
    val showSensorNames: Flow<Boolean> = boolFlow(K_CGM_SHOW_SENSOR_NAMES, DEFAULT_SHOW_SENSOR_NAMES)
    suspend fun setShowSensorNames(on: Boolean) = put(K_CGM_SHOW_SENSOR_NAMES, if (on) "1" else "0")

    // Stickiness not a setting: last-committed preset label, unvalidated, not exportable.
    suspend fun lastRapidPreset(): String? = repository.getKv(K_LAST_RAPID_PRESET)
    suspend fun lastBasalPreset(): String? = repository.getKv(K_LAST_BASAL_PRESET)
    suspend fun setLastRapidPreset(label: String) = put(K_LAST_RAPID_PRESET, label)
    suspend fun setLastBasalPreset(label: String) = put(K_LAST_BASAL_PRESET, label)

    // Body mass: kcal figure needs it, not exportable, floored not capped; blank means unset.
    val bodyMassKg: Flow<Double?> = repository.observeKv(K_EXERCISE_BODY_MASS_KG).map(::decodeBodyMassKg)

    suspend fun currentBodyMassKg(): Double? = decodeBodyMassKg(repository.getKv(K_EXERCISE_BODY_MASS_KG))

    suspend fun setBodyMassKg(kg: Double?) = put(K_EXERCISE_BODY_MASS_KG, encodeBodyMassKg(kg))

    // Per-patient carb-equiv-per-min (§5 disposal gamma); exportable, applies forward only.
    val carbEquivPerMin: Flow<Double> =
        repository.observeKv(K_EXERCISE_CARB_EQUIV).map(::decodeCarbEquivPerMin)

    suspend fun currentCarbEquivPerMin(): Double =
        decodeCarbEquivPerMin(repository.getKv(K_EXERCISE_CARB_EQUIV))

    suspend fun setCarbEquivPerMin(gPerMin: Double) =
        put(K_EXERCISE_CARB_EQUIV, encodeCarbEquivPerMin(gPerMin))

    // Outbox hold before first send, so Logs can withdraw it; MEAL/DOSE exempt from eviction.
    val pushHoldMin: Flow<Int> = repository.observeKv(K_PUSH_HOLD_MIN).map { decodePushHoldMin(it) }
    suspend fun currentPushHoldMin(): Int = decodePushHoldMin(repository.getKv(K_PUSH_HOLD_MIN))
    suspend fun setPushHoldMin(min: Int) = put(K_PUSH_HOLD_MIN, encodePushHoldMin(min))

    // Three most recent COMMITTED queries, newest first, one kv row; not exportable.
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

    // Not exportable: SAF grant is per-install, and last-run state must not travel with backup.

    val backupCadenceHours: Flow<Int> = repository.observeKv(K_BACKUP_CADENCE_H).map(::decodeBackupCadence)
    val backupKeep: Flow<Int> = repository.observeKv(K_BACKUP_KEEP).map(::decodeBackupKeep)

    /** Null when no folder is granted; blank reads as null, so revoking is a write. */
    val backupFolderUri: Flow<String?> = repository.observeKv(K_BACKUP_TREE).map { it?.ifBlank { null } }

    /** Captured once at grant time; resolving from URI would query ContentResolver on recompose. */
    val backupFolderLabel: Flow<String?> = repository.observeKv(K_BACKUP_LABEL).map { it?.ifBlank { null } }

    val backupLastOk: Flow<BackupRunOk?> = repository.observeKv(K_BACKUP_LAST_OK).map(::decodeBackupOk)
    val backupLastError: Flow<BackupRunError?> = repository.observeKv(K_BACKUP_LAST_ERR).map(::decodeBackupError)

    suspend fun currentBackupCadenceHours(): Int = decodeBackupCadence(repository.getKv(K_BACKUP_CADENCE_H))
    suspend fun currentBackupKeep(): Int = decodeBackupKeep(repository.getKv(K_BACKUP_KEEP))
    suspend fun currentBackupFolder(): String? = repository.getKv(K_BACKUP_TREE)?.ifBlank { null }

    suspend fun currentBackupFolderLabel(): String? = repository.getKv(K_BACKUP_LABEL)?.ifBlank { null }

    suspend fun setBackupCadenceHours(hours: Int) = put(K_BACKUP_CADENCE_H, encodeBackupCadence(hours))
    suspend fun setBackupKeep(keep: Int) = put(K_BACKUP_KEEP, encodeBackupKeep(keep))

    suspend fun setBackupFolder(treeUri: String, label: String) {
        put(K_BACKUP_TREE, treeUri)
        put(K_BACKUP_LABEL, label)
    }

    /** Success does NOT clear last failure, nor failure the last success; both are kept. */
    suspend fun recordBackupOk(atMs: Long, bytes: Long, rows: Int) =
        put(K_BACKUP_LAST_OK, encodeBackupOk(atMs, bytes, rows))

    suspend fun recordBackupError(atMs: Long, message: String) =
        put(K_BACKUP_LAST_ERR, encodeBackupError(atMs, message))

    suspend fun clearBackupError() = put(K_BACKUP_LAST_ERR, "")

    // Exports only allowlisted config keys, never runtime state (e.g. watch nonce ceilings).

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

    /** Fail-closed: malformed or wrong-format throws; only allowlisted keys written. */
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


    val aggressiveScanEnabled: Flow<Boolean> = boolFlow(K_AGG_SCAN, false)

    val aggressiveShowGlucose: Flow<Boolean> = boolFlow(K_AGG_SHOW_BG, true)

    val aggressiveOnlyCharging: Flow<Boolean> = boolFlow(K_AGG_ONLY_CHARGING, false)

    suspend fun setAggressiveScanEnabled(on: Boolean) = put(K_AGG_SCAN, if (on) "1" else "0")

    suspend fun setAggressiveShowGlucose(on: Boolean) = put(K_AGG_SHOW_BG, if (on) "1" else "0")

    suspend fun setAggressiveOnlyCharging(on: Boolean) = put(K_AGG_ONLY_CHARGING, if (on) "1" else "0")

    val sensorExpiryMs: Flow<Long?> = repository.observeKv(K_CGM_SENSOR_EXPIRY).map { it?.toLongOrNull() }

    suspend fun setSensorExpiryMs(ms: Long) = put(K_CGM_SENSOR_EXPIRY, ms.toString())

    suspend fun clearSensorExpiry() = put(K_CGM_SENSOR_EXPIRY, "")

    val disclaimerAcknowledged: Flow<Boolean> = boolFlow(K_DISCLAIMER_ACK, false)

    suspend fun acknowledgeDisclaimer() = put(K_DISCLAIMER_ACK, "1")

    companion object {
        private const val K_DISCLAIMER_ACK = "disclaimer.acknowledged"
        private const val K_CGM_SENSOR_EXPIRY = "cgm.sensor_expiry_ms"
        private const val K_AGG_SCAN = "cgm.aggressive_scan"
        private const val K_AGG_SHOW_BG = "cgm.aggressive_show_glucose"
        private const val K_AGG_ONLY_CHARGING = "cgm.aggressive_only_charging"

        private val DEF = AlarmConfig.DEFAULT

        /** Lifted to the companion so the placement of a new key is host-testable without Room. */
        internal fun isConfigKey(key: String): Boolean =
            CONFIG_PREFIXES.any { key.startsWith(it) } || key in CONFIG_EXACT_KEYS

        /** Per-key import validation; absent key copied verbatim, null rejects, keeps default. */
        internal val CONFIG_COERCE: Map<String, (String) -> String?> = mapOf(
            K_SNOOZE_MIN to { raw -> raw.toIntOrNull()?.let(::encodeSnoozeMin) },
            K_PUSH_HOLD_MIN to { raw -> raw.toIntOrNull()?.let(::encodePushHoldMin) },
            K_EXERCISE_CARB_EQUIV to { raw -> raw.toDoubleOrNull()?.let(::encodeCarbEquivPerMin) },
        )

        const val DEFAULT_LOW_POWER_PCT = 20

        const val OBJ_KOVATCHEV = "kovatchev"
        const val OBJ_MIN_TOR = "min_tor"
        const val OBJ_HIT_TARGET = "hit_target"

        const val RAIL_PREDICTED_LOW = "predicted_low"
        const val RAIL_IOB = "iob"
        const val RAIL_CONFIRM = "confirm"
        const val RAIL_HYPO = "hypo"

        private const val CONFIG_FORMAT = "t1dm.config"
        private const val CONFIG_VERSION = 1

        private val CONFIG_PREFIXES = listOf("alarm.", "alerts.", "power.", "calc.", "ui.", "graph.", "stats.")
        // No blanket inference. prefix (sweeps telemetry); death.enabled stays non-exportable.
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
            // By exact key, never an `exercise.` prefix: `exercise.body_mass_kg` sits beside it.
            K_EXERCISE_CARB_EQUIV,
        )

        const val K_FORECAST_MODE = "inference.forecast_mode"
        const val K_FORECAST_PERIOD_MIN = "inference.forecast_period_min"
        const val FORECAST_MODE_ADAPTIVE = "adaptive"
        const val FORECAST_MODE_TIMED = "timed"
        const val DEFAULT_FORECAST_PERIOD_MIN = 5
        const val FORECAST_PERIOD_MIN_MIN = 1
        const val FORECAST_PERIOD_MIN_MAX = 60

        const val K_INF_LOG_DEBOUNCE_S = "inference.log_reforecast_debounce_s"
        const val DEFAULT_LOG_REFORECAST_DEBOUNCE_S = 4

        /** Ceiling no writer may exceed; past it a re-run lands after the tick it anticipates. */
        const val MAX_LOG_REFORECAST_DEBOUNCE_S = 60

        /** Clamps to 0..MAX_LOG_REFORECAST_DEBOUNCE_S; garbage/unset falls back to the default. */
        internal fun encodeLogReforecastDebounceS(seconds: Int): String =
            seconds.coerceIn(0, MAX_LOG_REFORECAST_DEBOUNCE_S).toString()
        internal fun decodeLogReforecastDebounceS(raw: String?): Int =
            raw?.toIntOrNull()?.coerceIn(0, MAX_LOG_REFORECAST_DEBOUNCE_S) ?: DEFAULT_LOG_REFORECAST_DEBOUNCE_S

        const val K_INF_THERMAL_ON = "inference.thermal_gate_enabled"
        const val K_INF_MAX_TEMP_C = "inference.max_temp_c"
        const val K_INF_WARN_MARGIN_C = "inference.thermal_warn_margin_c"
        const val DEFAULT_THERMAL_ON = true
        const val DEFAULT_MAX_TEMP_C = 45.0
        const val DEFAULT_WARN_MARGIN_C = 3.0

        const val K_INF_MAX_MODELS = "inference.max_models"
        const val DEFAULT_MAX_MODELS = 5
        const val INF_MAX_MODELS_MIN = 1
        const val INF_MAX_MODELS_MAX = 8

        const val K_INF_SAVGOL_WINDOW = "inference.savgol_window"
        const val DEFAULT_SAVGOL_WINDOW = InferenceControllerDefaults.SAVGOL_WINDOW
        val SAVGOL_STOPS: List<Int> = InferenceControllerDefaults.SAVGOL_STOPS

        const val K_DEATH_DKA_H = "death.dka_after_iob_zero_h"
        const val K_DEATH_COMA_H = "death.coma_after_dka_h"
        const val K_DEATH_DEATH_H = "death.death_after_coma_h"
        const val DEFAULT_DKA_AFTER_IOB_ZERO_H = 2.0
        const val DEFAULT_COMA_AFTER_DKA_H = 29.0
        const val DEFAULT_DEATH_AFTER_COMA_H = 59.0

        private const val K_WEAK_SIGNAL_ON = "alarm.weak_signal_enabled"
        private const val K_WEAK_SIGNAL_DBM = "alarm.weak_signal_dbm"
        private const val K_WEAK_SIGNAL_SUSTAIN = "alarm.weak_signal_sustain_min"

        const val K_OVERTEMP_ENABLED = "alarm.overtemp_enabled"
        const val K_OVERTEMP_ALERT_C = "alarm.overtemp_alert_c"
        const val K_OVERTEMP_CLEAR_C = "alarm.overtemp_clear_c"
        const val K_OVERTEMP_CRITICAL = "alarm.overtemp_critical"
        const val DEFAULT_OVERTEMP_ENABLED = true
        const val DEFAULT_OVERTEMP_ALERT_C = 44.0
        const val DEFAULT_OVERTEMP_CLEAR_C = 41.0
        const val DEFAULT_OVERTEMP_CRITICAL = false

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

        /** Ceiling §3.6 C1 rests on; no writer, importJson included, may persist longer. */
        const val MAX_SNOOZE_MIN = 60

        /** Clamps to 1..MAX_SNOOZE_MIN (§3.6 C1); unset/garbage falls to DEFAULT_SNOOZE_MIN. */
        internal fun encodeSnoozeMin(min: Int): String = min.coerceIn(1, MAX_SNOOZE_MIN).toString()
        internal fun decodeSnoozeMin(raw: String?): Int =
            raw?.toIntOrNull()?.coerceIn(1, MAX_SNOOZE_MIN) ?: DEFAULT_SNOOZE_MIN

        const val K_PUSH_HOLD_MIN = "sync.push_hold_min"
        const val DEFAULT_PUSH_HOLD_MIN = 15

        /** Ceiling no writer may exceed; a hold is a withdrawal window, not a sync policy. */
        const val MAX_PUSH_HOLD_MIN = 120

        /** Clamps to 0..MAX_PUSH_HOLD_MIN; 0 is send-immediately; garbage/unset falls back. */
        internal fun encodePushHoldMin(min: Int): String = min.coerceIn(0, MAX_PUSH_HOLD_MIN).toString()
        internal fun decodePushHoldMin(raw: String?): Int =
            raw?.toIntOrNull()?.coerceIn(0, MAX_PUSH_HOLD_MIN) ?: DEFAULT_PUSH_HOLD_MIN

        const val K_BACKUP_CADENCE_H = "backup.cadence_h"
        const val K_BACKUP_KEEP = "backup.keep"
        const val K_BACKUP_TREE = "backup.tree_uri"
        const val K_BACKUP_LABEL = "backup.tree_label"
        const val K_BACKUP_LAST_OK = "backup.last_ok"
        const val K_BACKUP_LAST_ERR = "backup.last_err"

        /** 0 is OFF and the default; backup writes outside app storage, needs a granted folder. */
        const val BACKUP_CADENCE_OFF = 0
        val BACKUP_CADENCE_STOPS: List<Int> = listOf(BACKUP_CADENCE_OFF, 6, 24, 24 * 7)
        const val DEFAULT_BACKUP_CADENCE_H = BACKUP_CADENCE_OFF

        val BACKUP_KEEP_STOPS: List<Int> = listOf(3, 7, 14, 30)
        const val DEFAULT_BACKUP_KEEP = 7

        /** Snaps to a declared stop, not clamped; unset or unrecognised falls back to default. */
        internal fun encodeBackupCadence(hours: Int): String = nearestStop(hours, BACKUP_CADENCE_STOPS).toString()
        internal fun decodeBackupCadence(raw: String?): Int =
            raw?.toIntOrNull()?.takeIf { it in BACKUP_CADENCE_STOPS } ?: DEFAULT_BACKUP_CADENCE_H

        internal fun encodeBackupKeep(keep: Int): String = nearestStop(keep, BACKUP_KEEP_STOPS).toString()
        internal fun decodeBackupKeep(raw: String?): Int =
            raw?.toIntOrNull()?.takeIf { it in BACKUP_KEEP_STOPS } ?: DEFAULT_BACKUP_KEEP

        private fun nearestStop(value: Int, stops: List<Int>): Int =
            stops.minByOrNull { kotlin.math.abs(it - value) } ?: stops.first()

        /** Pipe-separated not JSON (org.json is host-stubbed); pipes/newlines folded to a space. */
        internal fun encodeBackupOk(atMs: Long, bytes: Long, rows: Int): String = "$atMs|$bytes|$rows"

        internal fun decodeBackupOk(raw: String?): BackupRunOk? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 3) return null
            val at = parts[0].toLongOrNull() ?: return null
            val bytes = parts[1].toLongOrNull() ?: return null
            val rows = parts[2].toIntOrNull() ?: return null
            return BackupRunOk(at, bytes, rows)
        }

        internal fun encodeBackupError(atMs: Long, message: String): String {
            val flat = message.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim()
            return "$atMs|${flat.take(MAX_BACKUP_ERROR_CHARS)}"
        }

        internal fun decodeBackupError(raw: String?): BackupRunError? {
            val at = raw?.substringBefore('|')?.toLongOrNull() ?: return null
            val message = raw.substringAfter('|', "")
            if (message.isBlank()) return null
            return BackupRunError(at, message)
        }

        /** Enough to name the cause; short enough that a stack trace cannot fill the row. */
        private const val MAX_BACKUP_ERROR_CHARS = 160

        private const val K_SEARCH_RECENT = "search.recent_settings"
        const val RECENT_SEARCH_LIMIT = 3

        /** Newline-separated not JSONArray (org.json is host-stubbed); a newline folds anyway. */
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
        private const val K_CALC_GRID_MAX = "calc.grid_max_u"
        private const val K_CALC_GRID_STEP = "calc.grid_step_u"
        private const val K_RAIL_PREDLOW = "calc.rail_predicted_low"
        private const val K_RAIL_IOB = "calc.rail_iob"
        private const val K_RAIL_CONFIRM = "calc.rail_confirm"
        private const val K_RAIL_HYPO = "calc.rail_hypo"

        private const val K_DEATH = "death.enabled"

        private const val K_UI_ANIMATIONS = "ui.animations"
        private const val K_UI_VOLUME_NAV = "ui.volume_nav"
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
        /** Panel-owned per-device state outside [CONFIG_PREFIXES] — see [bodyMassKg]. */
        internal const val K_EXERCISE_BODY_MASS_KG = "exercise.body_mass_kg"

        /** Sanity floor only, no ceiling — same rule that leaves alarm thresholds unbounded. */
        const val MIN_BODY_MASS_KG = 20.0

        internal fun decodeBodyMassKg(raw: String?): Double? =
            raw?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= MIN_BODY_MASS_KG }

        internal fun encodeBodyMassKg(kg: Double?): String =
            kg?.takeIf { it.isFinite() && it >= MIN_BODY_MASS_KG }?.toString().orEmpty()

        /** Grams of carb equivalent disposed per minute of exercise; see carbEquivPerMin. */
        const val K_EXERCISE_CARB_EQUIV = "exercise.carb_equiv_per_min"

        /** Default/rails are ExerciseDisposal's, not a copy (SPEC §5); never falls back to zero. */
        internal fun encodeCarbEquivPerMin(gPerMin: Double): String =
            (if (gPerMin.isFinite()) clampCarbEquiv(gPerMin) else ExerciseDisposal.DEFAULT_CARB_EQUIV_PER_MIN)
                .toString()

        internal fun decodeCarbEquivPerMin(raw: String?): Double =
            raw?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::clampCarbEquiv)
                ?: ExerciseDisposal.DEFAULT_CARB_EQUIV_PER_MIN

        private fun clampCarbEquiv(v: Double): Double = v.coerceIn(
            ExerciseDisposal.MIN_CARB_EQUIV_PER_MIN,
            ExerciseDisposal.MAX_CARB_EQUIV_PER_MIN,
        )

        private const val K_CGM_SENSOR_LIFE_DAYS = "cgm.sensor_life_days"
        internal const val K_CGM_SHOW_SENSOR_NAMES = "cgm.show_sensor_names"
        /** Hidden: a name that leaks a serial cannot be un-leaked from a screenshot. */
        const val DEFAULT_SHOW_SENSOR_NAMES = false
        const val DEFAULT_SENSOR_LIFE_DAYS = 15
        const val SENSOR_LIFE_DAYS_MIN = 3
        const val SENSOR_LIFE_DAYS_MAX = 30
        /** Usage state, not configuration; catalogue's first family entry is the fallback. */
        internal const val K_LAST_RAPID_PRESET = "insulin.last_rapid_preset"
        internal const val K_LAST_BASAL_PRESET = "insulin.last_basal_preset"
        const val DEFAULT_THEME = "tron"
        const val DEFAULT_FONT = "system"
    }
}
