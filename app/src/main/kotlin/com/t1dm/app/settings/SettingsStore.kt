package com.t1dm.app.settings

import com.t1dm.alerts.AlarmConfig
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
 * The complete, kv-backed configuration surface for the Settings hub (PLAN.private.md Phase 7C —
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

    suspend fun setLossWindows(lossMin: Int, lossEscalatedMin: Int) {
        put(K_LOSS_MIN, lossMin.coerceAtLeast(1).toString())
        put(K_LOSS_ESCALATED_MIN, lossEscalatedMin.coerceAtLeast(1).toString())
    }

    suspend fun setRepeatCadence(min: Int) = put(K_REPEAT_CADENCE, min.coerceAtLeast(1).toString())

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
        return calcDef.copy(
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
    }

    // ── UI (ux-decisions.md — a global "disable all animations" toggle) ────────────────────────

    val animationsEnabled: Flow<Boolean> = boolFlow(K_UI_ANIMATIONS, true)
    suspend fun setAnimationsEnabled(on: Boolean) = put(K_UI_ANIMATIONS, if (on) "1" else "0")

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
        private val CONFIG_EXACT_KEYS = setOf("inference.warmup_hours")

        // ── kv keys ────────────────────────────────────────────────────────────────────────────
        private const val K_ALARM_URGENT_LOW = "alarm.urgent_low_mgdl"
        private const val K_ALARM_LOW = "alarm.low_mgdl"
        private const val K_ALARM_HIGH = "alarm.high_mgdl"
        private const val K_ALARM_URGENT_HIGH = "alarm.urgent_high_mgdl"
        private const val K_LOSS_MIN = "alarm.loss_min"
        private const val K_LOSS_ESCALATED_MIN = "alarm.loss_escalated_min"
        private const val K_REPEAT_CADENCE = "alarm.repeat_cadence_min"

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

        private const val K_UI_ANIMATIONS = "ui.animations"
        private const val K_UI_THEME = "ui.theme"
        private const val K_UI_FONT = "ui.font"
        private const val K_UI_CUSTOM_THEME = "ui.custom_theme_json"
        private const val K_CURVE_CARB_BEZIER = "graph.curve_carb_bezier"
        private const val K_CURVE_INSULIN_BEZIER = "graph.curve_insulin_bezier"
        const val DEFAULT_THEME = "tron"
        const val DEFAULT_FONT = "system"
    }
}
