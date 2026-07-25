package com.t1dm.app.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * The widget's last-known render, mirrored into Glance's OWN per-widget Preferences store (the
 * `PreferencesGlanceStateDefinition` every `GlanceAppWidget` already carries) so the tile has
 * something truthful to paint when the live pull cannot run.
 *
 * It exists because the tile has no platform-driven refresh at all: `updatePeriodMillis="0"` means
 * AppWidgetServiceImpl registers no repeating update alarm, and the AppWidget service does not persist
 * RemoteViews across a reboot — so after every boot the host inflates the `initialLayout` spinner and
 * stays there until this app pushes. The first push can arrive long after the host is up (or never, if
 * the FGS's BLE-permission gate `stopSelf()`s it), and the pull it drives reaches straight into Room
 * through the container: on a cold process that read can throw, and a throw out of `provideGlance`
 * REPLACES the tile with Glance's "Can't show content" surface rather than leaving the last render
 * standing. This store is what the fallback renders instead.
 *
 * Deliberately NOT a second glance computation: the cached render rebuilds a [CgmReading] from the
 * persisted (BG, trend, receive-time) triple and re-runs the SAME [BgGlanceComputer] the notification
 * and the watch use, so a stale tile agrees with the live one by construction and its age is measured
 * against the wall clock at render time rather than frozen at write time. The forecast half degrades
 * fail-closed on its own: with no [InferenceState] to replay there is no eligible prediction, so the
 * cached tile reads "Forecast unavailable" / VOID and can never assert a stale STABLE (§3.6).
 *
 * Only the fields a render actually needs are kept. Everything the process holds in memory and cannot
 * reconstruct offline (IOB/COB, GMI, steps, the circadian clock) is left null and renders "—", which
 * is the honest answer for a tile drawn without the app's runtime.
 */
internal object WidgetStateStore {

    /** Absent ⇒ nothing was ever written for this widget id, so there is no last-known render. */
    private val KEY_SAVED_AT = longPreferencesKey("t1dm.widget.saved_at_ms")

    // The reading triple. All three are REMOVED together when the live render had no reading, so the
    // cache can never resurrect a value the live path had already stopped showing.
    private val KEY_BG = intPreferencesKey("t1dm.widget.bg_mgdl")
    private val KEY_TREND = intPreferencesKey("t1dm.widget.trend_tenths")
    private val KEY_RX_WALL = longPreferencesKey("t1dm.widget.rx_wall_ms")
    private val KEY_RSSI = intPreferencesKey("t1dm.widget.rssi")

    // Presentation settings — without these a cached tile would paint the process defaults (mg/dL,
    // Tron) over a user who chose otherwise, which is the quieter half of the cold-process bug.
    private val KEY_UNIT = stringPreferencesKey("t1dm.widget.unit")
    private val KEY_THEME = stringPreferencesKey("t1dm.widget.theme_id")
    private val KEY_CUSTOM_THEME = stringPreferencesKey("t1dm.widget.custom_theme_json")
    private val KEY_BG_ALPHA = intPreferencesKey("t1dm.widget.bg_alpha_pct")
    private val KEY_ANIMATIONS = booleanPreferencesKey("t1dm.widget.animations")
    private val KEY_DEATH = booleanPreferencesKey("t1dm.widget.death")

    // The user's alarm geometry, so the cached number keeps its measured band tint and the age-based
    // signal-loss line keeps the user's window instead of silently reverting to the boot defaults.
    private val KEY_THR_URGENT_LOW = intPreferencesKey("t1dm.widget.thr_urgent_low")
    private val KEY_THR_LOW = intPreferencesKey("t1dm.widget.thr_low")
    private val KEY_THR_HIGH = intPreferencesKey("t1dm.widget.thr_high")
    private val KEY_THR_URGENT_HIGH = intPreferencesKey("t1dm.widget.thr_urgent_high")
    private val KEY_LOSS_MIN = intPreferencesKey("t1dm.widget.loss_min")

    /** The synthetic source the rebuilt reading is stamped with; never persisted, never re-ingested. */
    private val CACHED_SOURCE = CgmSourceId("t1dm.widget.cache")

    /** Mirror a render that actually happened. [nowMs] must be the instant [snap] was computed at, so
     *  the receive-time recovered from the glance's age is the reading's real one. */
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

    /** The persisted (theme id, custom JSON) pair, coerced onto a theme this build still carries —
     *  the seed for [com.t1dm.core.design.applyWidgetPalette] when the live settings read failed. */
    fun themeOf(prefs: Preferences): Pair<String, String?> =
        normalizeThemeId(prefs[KEY_THEME]) to prefs[KEY_CUSTOM_THEME]

    /**
     * The floor render: no live pull, no cache (a widget pinned onto a process that has never yet
     * completed a render). Everything unknown, nothing invented — the tile shows "--", "no reading"
     * and a VOID status rather than the host's loading spinner or Glance's error surface.
     */
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
        // An empty InferenceState is not a placeholder, it is the truth: nothing off-process knows what
        // the model last said, so every forecast-derived field must fail closed.
        val state = InferenceState()
        val (glyText, glyKind) = computeGlyStatus(state, thresholds, nowMs)
        return WidgetSnapshot(
            glance = BgGlanceComputer.compute(
                latest = latest,
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
