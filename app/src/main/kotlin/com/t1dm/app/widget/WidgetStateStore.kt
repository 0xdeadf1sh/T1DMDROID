package com.t1dm.app.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.t1dm.app.notify.GlanceReadings
import com.t1dm.alerts.AlarmConfig
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.app.settings.SettingsStore
import com.t1dm.core.design.ThemeIds
import com.t1dm.core.design.normalizeThemeId
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.UnitSpace

/**
 * The last-known render, in Glance's own per-widget Preferences, for when the live pull cannot run.
 * Not a second glance computation: it rebuilds a [CgmReading] and re-runs the same [BgGlanceComputer],
 * so a cached tile ages against the wall clock and, with no [InferenceState] to replay, fails closed
 * to VOID rather than asserting a stale STABLE (§3.6).
 */
internal object WidgetStateStore {

    /** Absent ⇒ no last-known render for this widget id. */
    private val KEY_SAVED_AT = longPreferencesKey("t1dm.widget.saved_at_ms")

    // Removed together, so the cache can never resurrect a reading the live path stopped showing.
    private val KEY_BG = intPreferencesKey("t1dm.widget.bg_mgdl")
    private val KEY_TREND = intPreferencesKey("t1dm.widget.trend_tenths")
    private val KEY_RX_WALL = longPreferencesKey("t1dm.widget.rx_wall_ms")
    private val KEY_RSSI = intPreferencesKey("t1dm.widget.rssi")

    // Without these a cached tile paints the process defaults over the user's own choices.
    private val KEY_UNIT = stringPreferencesKey("t1dm.widget.unit")
    private val KEY_THEME = stringPreferencesKey("t1dm.widget.theme_id")
    private val KEY_CUSTOM_THEME = stringPreferencesKey("t1dm.widget.custom_theme_json")
    private val KEY_BG_ALPHA = intPreferencesKey("t1dm.widget.bg_alpha_pct")
    private val KEY_ANIMATIONS = booleanPreferencesKey("t1dm.widget.animations")
    private val KEY_DEATH = booleanPreferencesKey("t1dm.widget.death")

    // So a cached tile keeps the user's band tint and signal-loss window, not the boot defaults.
    private val KEY_THR_URGENT_LOW = intPreferencesKey("t1dm.widget.thr_urgent_low")
    private val KEY_THR_LOW = intPreferencesKey("t1dm.widget.thr_low")
    private val KEY_THR_HIGH = intPreferencesKey("t1dm.widget.thr_high")
    private val KEY_THR_URGENT_HIGH = intPreferencesKey("t1dm.widget.thr_urgent_high")
    private val KEY_LOSS_MIN = intPreferencesKey("t1dm.widget.loss_min")

    /** Synthetic: never stored, never re-ingested. */
    private val CACHED_SOURCE = CgmSourceId("t1dm.widget.cache")

    /** [nowMs] must be the instant [snap] was computed at, or the recovered receive time is wrong. */
    fun write(prefs: MutablePreferences, snap: WidgetSnapshot, nowMs: Long) {
        prefs[KEY_SAVED_AT] = nowMs
        val g = snap.glance
        if (g.hasReading) {
            prefs[KEY_BG] = g.bgMgdl!!
            prefs[KEY_RX_WALL] = nowMs - g.readingAgeMs
            g.trendTenths?.let { prefs[KEY_TREND] = it } ?: prefs.remove(KEY_TREND)
            snap.rssi?.let { prefs[KEY_RSSI] = it } ?: prefs.remove(KEY_RSSI)
        } else {
            prefs.remove(KEY_BG)
            prefs.remove(KEY_RX_WALL)
            prefs.remove(KEY_TREND)
            prefs.remove(KEY_RSSI)
        }
        prefs[KEY_UNIT] = snap.unit.name
        prefs[KEY_THEME] = snap.themeId
        snap.customThemeJson?.let { prefs[KEY_CUSTOM_THEME] = it } ?: prefs.remove(KEY_CUSTOM_THEME)
        prefs[KEY_BG_ALPHA] = snap.bgAlphaPct
        prefs[KEY_ANIMATIONS] = snap.animationsEnabled
        prefs[KEY_DEATH] = snap.death
        prefs[KEY_THR_URGENT_LOW] = snap.thresholds.urgentLowMgdl
        prefs[KEY_THR_LOW] = snap.thresholds.lowMgdl
        prefs[KEY_THR_HIGH] = snap.thresholds.highMgdl
        prefs[KEY_THR_URGENT_HIGH] = snap.thresholds.urgentHighMgdl
        prefs[KEY_LOSS_MIN] = snap.lossMin
    }

    /** The last-known render re-aged to [nowMs], or null when this widget id has never rendered. */
    fun read(prefs: Preferences, nowMs: Long): WidgetSnapshot? {
        if (prefs[KEY_SAVED_AT] == null) return null
        val thresholds = AlertThresholds(
            urgentLowMgdl = prefs[KEY_THR_URGENT_LOW] ?: DEFAULT_THRESHOLDS.urgentLowMgdl,
            lowMgdl = prefs[KEY_THR_LOW] ?: DEFAULT_THRESHOLDS.lowMgdl,
            highMgdl = prefs[KEY_THR_HIGH] ?: DEFAULT_THRESHOLDS.highMgdl,
            urgentHighMgdl = prefs[KEY_THR_URGENT_HIGH] ?: DEFAULT_THRESHOLDS.urgentHighMgdl,
        )
        val rxWallMs = prefs[KEY_RX_WALL]
        val bgMgdl = prefs[KEY_BG]
        val latest = if (rxWallMs != null && bgMgdl != null) {
            CgmReading(
                sourceId = CACHED_SOURCE,
                tsMs = rxWallMs - rxWallMs.mod(300_000L),
                bgMgdl = bgMgdl,
                trendTenthsPerMin = prefs[KEY_TREND],
                minFromStart = null,
                quality = null,
                provenance = ReadingProvenance.MEASURED,
                flag = ReadingFlag.NORMAL,
                tzOffsetMin = 0,
                rxWallMs = rxWallMs,
                rssi = prefs[KEY_RSSI],
            )
        } else {
            null
        }
        return snapshotOf(
            latest = latest,
            thresholds = thresholds,
            lossMin = prefs[KEY_LOSS_MIN] ?: AlarmConfig.DEFAULT.lossMin,
            unit = UnitSpace.entries.firstOrNull { it.name == prefs[KEY_UNIT] } ?: UnitSpace.MgDl,
            animationsEnabled = prefs[KEY_ANIMATIONS] ?: true,
            bgAlphaPct = prefs[KEY_BG_ALPHA] ?: SettingsStore.DEFAULT_BG_ALPHA_PCT,
            rssi = prefs[KEY_RSSI],
            death = prefs[KEY_DEATH] ?: false,
            themeId = normalizeThemeId(prefs[KEY_THEME]),
            customThemeJson = prefs[KEY_CUSTOM_THEME],
            nowMs = nowMs,
        )
    }

    fun themeOf(prefs: Preferences): Pair<String, String?> =
        normalizeThemeId(prefs[KEY_THEME]) to prefs[KEY_CUSTOM_THEME]

    /** The floor render: nothing known, nothing invented. */
    fun unknown(nowMs: Long): WidgetSnapshot = snapshotOf(
        latest = null,
        thresholds = DEFAULT_THRESHOLDS,
        lossMin = AlarmConfig.DEFAULT.lossMin,
        unit = UnitSpace.MgDl,
        animationsEnabled = true,
        bgAlphaPct = SettingsStore.DEFAULT_BG_ALPHA_PCT,
        rssi = null,
        death = false,
        themeId = ThemeIds.TRON,
        customThemeJson = null,
        nowMs = nowMs,
    )

    private val DEFAULT_THRESHOLDS = AlarmConfig.DEFAULT.thresholds

    private fun snapshotOf(
        latest: CgmReading?,
        thresholds: AlertThresholds,
        lossMin: Int,
        unit: UnitSpace,
        animationsEnabled: Boolean,
        bgAlphaPct: Int,
        rssi: Int?,
        death: Boolean,
        themeId: String,
        customThemeJson: String?,
        nowMs: Long,
    ): WidgetSnapshot {
        // Nothing off-process knows what the model last said, so every forecast field fails closed.
        val state = InferenceState()
        val (glyText, glyKind) = computeGlyStatus(state, thresholds, nowMs)
        return WidgetSnapshot(
            // glance.bgMgdl already reads the last MEASUREMENT, so the cache can only hold one.
            glance = BgGlanceComputer.compute(
                readings = GlanceReadings.create(listOfNotNull(latest)),
                state = state,
                thresholds = thresholds,
                lossMin = lossMin,
                staleMin = STALE_MIN,
                nowMs = nowMs,
            ),
            unit = unit,
            animationsEnabled = animationsEnabled,
            bgAlphaPct = bgAlphaPct,
            iobU = null,
            cobG = null,
            rssi = rssi,
            clockHour = null,
            clockConf = null,
            gmi = null,
            steps = null,
            death = death,
            glyText = glyText,
            glyKind = glyKind,
            thresholds = thresholds,
            lossMin = lossMin,
            themeId = themeId,
            customThemeJson = customThemeJson,
        )
    }
}
