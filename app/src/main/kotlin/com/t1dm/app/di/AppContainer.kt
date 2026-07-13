package com.t1dm.app.di

import android.content.Context
import android.net.NetworkCapabilities
import android.content.Intent
import android.media.RingtoneManager
import com.t1dm.alerts.AlarmConfig
import com.t1dm.alerts.AlertActuatorConfig
import com.t1dm.app.cgm.AppCgmRepository
import com.t1dm.app.hardware.HardwareProbe
import com.t1dm.app.inference.KvTelemetryStore
import com.t1dm.app.inference.RoomBgHistoryProvider
import com.t1dm.app.settings.SettingsStore
import com.t1dm.app.BuildConfig
import com.t1dm.feature.hardware.HardwareInfo
import com.t1dm.feature.network.NetIface
import com.t1dm.feature.network.NetworkDiagnostics
import com.t1dm.feature.pubs.BlueskyClient
import com.t1dm.feature.pubs.PubsRepository
import com.t1dm.feature.settings.AboutInfo
import com.t1dm.app.sync.RoomPredictionStore
import com.t1dm.app.sync.SyncManager
import com.t1dm.app.sync.SyncStatus
import com.t1dm.app.sync.SyncStatusStore
import com.t1dm.app.watch.AndroidLowPowerProvider
import com.t1dm.app.watch.AppWatchGlanceSource
import com.t1dm.app.watch.RoomNonceStore
import com.t1dm.app.watch.RoomWatchPairingStore
import com.t1dm.watch.WatchLink
import com.t1dm.watch.WatchLinkConfig
import com.t1dm.watch.WatchSecurityState
import com.t1dm.watch.ble.AndroidWatchCentral
import com.t1dm.app.watch.UniffiWatchSessionFactory
import com.t1dm.cgm.AidexXPlugin
import com.t1dm.cgm.AidexXSourceRegistry
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.BasalPreset
import com.t1dm.core.model.BolusPreset
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.AccuracyReport
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.JournalNote
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.RolledForecast
import com.t1dm.core.model.ThermalStatus
import com.t1dm.core.model.DkaTimeline
import com.t1dm.calc.AdviceResult
import com.t1dm.calc.AnchorInfo
import com.t1dm.calc.AnchorInfoSource
import com.t1dm.calc.BackendInfo
import com.t1dm.calc.BackendInfoSource
import com.t1dm.calc.BolusCalculator
import com.t1dm.calc.BolusResolver
import com.t1dm.calc.CalcConfig
import com.t1dm.calc.DoseAdvisor
import com.t1dm.calc.IobSnapshot
import com.t1dm.calc.IobSource
import com.t1dm.calc.Objective
import com.t1dm.calc.RollingForecaster
import com.t1dm.calc.SelectedModelHandle
import com.t1dm.calc.SelectedModelProvider
import com.t1dm.inference.backend.GraphInput
import com.t1dm.core.nativecore.UniffiNativeCore
import com.t1dm.app.stats.AppStatsSource
import com.t1dm.data.T1dmRepository
import com.t1dm.data.settings.BgRange
import com.t1dm.data.settings.GraphSettingsStore
import com.t1dm.feature.dashboard.BgPulses
import com.t1dm.feature.dashboard.BgReachability
import com.t1dm.feature.dashboard.BgSignals
import com.t1dm.feature.dashboard.LinkHealth
import com.t1dm.feature.dashboard.ReachLight
import com.t1dm.app.sync.WsConnState
import com.t1dm.watch.WatchLinkPhase
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.DoseStore
import com.t1dm.data.curve.MealCurveResolver
import com.t1dm.data.curve.RoomDoseStore
import com.t1dm.data.meals.InsulinController
import com.t1dm.data.meals.MealsController
import com.t1dm.data.stats.StatsRepository
import com.t1dm.feature.stats.StatsViewModel
import com.t1dm.core.model.AdvancedStats
import com.t1dm.core.model.Food
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.RecentMeal
import com.t1dm.core.model.InsulinType
import com.t1dm.core.model.SavedMeal
import com.t1dm.core.model.TempUnit
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.NoteEntity
import com.t1dm.sync.EventStatDto
import com.t1dm.sync.NoteWriteDto
import com.t1dm.sync.StatsPushDto
import com.t1dm.sync.toDoseEventDto
import com.t1dm.sync.toMealEventDto
import com.t1dm.inference.ContextChannelSource
import com.t1dm.inference.FutureOverrideSource
import com.t1dm.inference.InferenceController
import com.t1dm.inference.InferenceControllerDefaults
import com.t1dm.inference.buildInferenceController
import com.t1dm.sync.CatchUpCoordinator
import com.t1dm.sync.DrainConfig
import com.t1dm.sync.ModelSyncCoordinator
import com.t1dm.sync.NoActiveProfileException
import com.t1dm.sync.OkHttpSyncClient
import com.t1dm.sync.OutboxEnqueuer
import com.t1dm.sync.QueueDrainer
import com.t1dm.sync.ServerProfile
import com.t1dm.sync.ServerProfileStore
import com.t1dm.sync.SyncHttpClient
import com.t1dm.sync.KeystoreTokenStore
import com.t1dm.sync.TokenStore
import com.t1dm.sync.WebSocketStreamClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

/** kv key + bound for the WARMUP setting (inference-runtime.md). */
private const val KV_WARMUP_HOURS = "inference.warmup_hours"
private const val WARMUP_HOURS_MAX = 72

/** Per-model kv key for the forecast-backend switcher (issue 20 STEP 4): the BackendId enum name per
 *  model id, or absent = auto (the fp32 XNNPACK authority). */
private fun kvForecastBackend(modelId: String) = "inference.forecast_backend.$modelId"

/** The clinical/published horizons the on-device accuracy aggregator reports (Phase 7C). */
private val ACCURACY_HORIZONS_MIN = listOf(30, 60, 120)

/** §3.8 (H7) — kv key holding the last server `store_epoch` the phone has fully re-mirrored to. */
private const val KV_MIRRORED_EPOCH = "sync.mirrored_epoch"

/** H7 re-mirror: rows enqueued per drain-paced batch — small enough that one batch added right after
 *  pacing (which waits for depth ≤ cap/2) stays well under the outbox size cap (§3.7). */
private const val REMIRROR_BATCH = 500

/** H7 re-mirror: abort pacing after this many 1 s waits; a stalled drain then leaves the epoch
 *  unrecorded so the walk resumes on the next connect rather than hanging. */
private const val REMIRROR_MAX_PACE_WAITS = 120


/**
 * The manual composition root (SPEC.private.md — "DI/wiring: manual is fine"). Built once in
 * [com.t1dm.app.T1dmApplication] and reached via `(application as T1dmApplication).container`.
 * Everything long-lived that the UI, the [com.t1dm.app.service.CgmScanService], and the debug
 * hooks share is constructed here exactly once; nothing constructs its own database, dispatchers,
 * or native core.
 *
 * Deliberately free of `:inference` — the Phase-1 walking skeleton (decode → Room → graph →
 * service → model-free alarm → steps) must run with no forecast in sight (§3.6-A).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val dispatchers: T1dmDispatchers = DefaultT1dmDispatchers()

    val nativeCore: NativeCore = UniffiNativeCore()

    /** Application-lifetime scope for the CGM registry's shared scan (survives Activity churn). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val repository: T1dmRepository by lazy { T1dmRepository(database, dispatchers) }

    /** The read-only Bluesky feed for `adapubs.bsky.social` (`:feature:pubs`). */
    val pubsRepository: PubsRepository by lazy { PubsRepository(BlueskyClient(dispatchers), dispatchers) }

    /** The `:cgm` persistence port bound onto the Room-backed [T1dmRepository]. */
    private val cgmRepository by lazy { AppCgmRepository(repository) }

    val plugin: AidexXPlugin by lazy { AidexXPlugin(nativeCore, cgmRepository) }

    val registry: AidexXSourceRegistry by lazy {
        AidexXSourceRegistry(
            plugin = plugin,
            repository = cgmRepository,
            scope = appScope,
        )
    }

    /** The complete kv-backed Settings surface (Phase 7C — items 14 & 17). Assembles the module-level
     *  [AlarmConfig] / [com.t1dm.calc.CalcConfig] policies from the raw persisted knobs. */
    val settingsStore: SettingsStore by lazy { SettingsStore(repository) }

    /**
     * The deterministic-alarm policy (§3.6-A). Conservative boot defaults until [refreshAlarmConfig]
     * hydrates the user's persisted thresholds. A `@Volatile var` (not a `val`) so a Settings edit —
     * after re-persisting — is picked up by the live property readers (dashboard band colouring,
     * glance surfaces, reachability lights); the already-running deterministic [AlarmEngine] captured
     * its snapshot at FGS start, so a threshold change fully applies to that path on the next service
     * start (a reopen), which the Settings screen states plainly.
     */
    @Volatile
    var alarmConfig: AlarmConfig = AlarmConfig.DEFAULT
        private set

    /** Reload [alarmConfig] from the persisted knobs (called at startup + after a Settings save). */
    suspend fun refreshAlarmConfig() {
        alarmConfig = runCatching { settingsStore.currentAlarmConfig() }.getOrDefault(AlarmConfig.DEFAULT)
    }

    // ─── Theme snapshot (issue I1 — per-theme notification icon geometry + accent) ─────────────────
    // The notification presenters run outside Compose (in the FGS / a short-lived service), so they
    // cannot read `LocalT1dmSemantics`. This @Volatile snapshot, kept current by a collector on the
    // persisted `themeId`, lets them resolve the active glyph GEOMETRY + accent synchronously.
    @Volatile
    var themeIdSnapshot: String = com.t1dm.core.design.ThemeIds.TRON
        private set

    @Volatile
    var customThemeJsonSnapshot: String? = null
        private set

    /** The active theme's notification-icon geometry family (Tron angular / Umbrella blocky / Kitty round). */
    val iconStyle: com.t1dm.core.design.IconStyle
        get() = com.t1dm.core.design.iconStyleForTheme(themeIdSnapshot)

    /** The active theme's accent (primary) as an ARGB int for `Notification.Builder.setColor`. */
    val notificationAccentArgb: Int
        get() = com.t1dm.app.notify.NotificationIcons.accentArgb(themeIdSnapshot, customThemeJsonSnapshot)

    // ─── DEATH mode (the total-silence override) — mirrors themeIdSnapshot: a @Volatile snapshot kept
    // current by a collector on the persisted flag, so the FGS alarm + predictive gates read it
    // synchronously. The persisted flag lives in SettingsStore and is deliberately never exported. ────
    @Volatile
    var deathModeSnapshot: Boolean = false
        private set

    val deathMode: Flow<Boolean> get() = settingsStore.deathMode
    suspend fun setDeathMode(on: Boolean) = settingsStore.setDeathMode(on)

    /** GMI (estimated HbA1c, %) over the 30-day window, recomputed on a slow cadence (it moves slowly
     *  and a 30-day recompute is too heavy for the widget's 30 s refresh). Null until first computed or
     *  when there is too little data. Read synchronously by the glucose widget. */
    @Volatile
    var gmiSnapshot: Double? = null
        private set

    /** Today's cumulative step count (local midnight → now), summed from the per-grid-bucket sample
     *  steps. Cheap (≤ 288 buckets/day) — read directly by the widget and the BG panel. */
    suspend fun stepsToday(): Int {
        val zone = java.time.ZoneId.systemDefault()
        val midnight = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return repository.samplesInRange(midnight, System.currentTimeMillis()).sumOf { it.steps ?: 0 }
    }

    // ─── Forecast-cadence snapshot (F2) — mirrors deathModeSnapshot: the FGS's single-consumer forecast
    // driver reads the ADAPTIVE-vs-TIMED mode synchronously off this @Volatile, kept current by a
    // collector on the persisted flag, without re-suspending into SettingsStore on every reading tick. ──
    @Volatile
    var forecastModeSnapshot: String = SettingsStore.FORECAST_MODE_ADAPTIVE
        private set

    /** The TIMED-mode forecast period (whole minutes), read fresh per timed tick by the FGS driver. */
    suspend fun forecastPeriodMin(): Int = settingsStore.currentForecastPeriodMin()

    // ─── Alert actuators (Phase 7B — per-band sound + K90 vibration; kv-backed via SettingsStore) ──

    /**
     * The per-severity sound + vibration config for the alert notifications (item 2), assembled from
     * the [SettingsStore] knobs. Sound is a per-tier on/off over the system ALARM-usage tone (so an
     * urgent-low sounds through DND out of the box); a fully custom mic/mp3 picker is DEFERRED
     * (RECORD_AUDIO not requested). Additive — a sound/vibration choice can never change WHEN an alarm
     * fires, only how it is announced (§3.6-A).
     */
    suspend fun alertActuatorConfig(): AlertActuatorConfig {
        val alarmTone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        return AlertActuatorConfig(
            warningSound = if (settingsStore.currentWarningSoundOn()) alarmTone else null,
            criticalSound = if (settingsStore.currentCriticalSoundOn()) alarmTone else null,
            warningVibration = settingsStore.currentWarningVibration(),
            criticalVibration = settingsStore.currentCriticalVibration(),
            bypassDnd = settingsStore.currentBypassDnd(),
        )
    }

    /** The K90 vibration actuator, reused for the Settings preview (issue 8) so the user feels a
     *  preset the instant they tap it, before committing. Shares the deterministic notifier's actuator
     *  semantics (primitive Composition → waveform fallback). */
    private val vibrationActuator by lazy { com.t1dm.alerts.VibrationActuator(appContext) }

    /** Immediately play a vibration preset by its opaque name (Settings → Alerts preview, issue 8).
     *  Unknown names are ignored. Purely a preview — never touches the alarm path (§3.6-A). */
    fun previewVibration(name: String) {
        val preset = runCatching { com.t1dm.alerts.VibrationPreset.valueOf(name) }.getOrNull() ?: return
        vibrationActuator.buzz(preset)
    }

    /** Persist an alarm-threshold edit and re-hydrate the live [alarmConfig] snapshot. */
    suspend fun saveAlarmThresholds(urgentLow: Int, low: Int, high: Int, urgentHigh: Int) {
        settingsStore.setAlarmThresholds(urgentLow, low, high, urgentHigh)
        refreshAlarmConfig()
    }

    /** Persist a loss-of-signal window edit and re-hydrate [alarmConfig]. */
    suspend fun saveLossWindows(lossMin: Int, lossEscalatedMin: Int) {
        settingsStore.setLossWindows(lossMin, lossEscalatedMin)
        refreshAlarmConfig()
    }

    /** Persist the repeat cadence and re-hydrate [alarmConfig]. */
    suspend fun saveRepeatCadence(min: Int) {
        settingsStore.setRepeatCadence(min)
        refreshAlarmConfig()
    }

    /** Persist the minimum sound+vibration actuation interval and re-hydrate [alarmConfig]. */
    suspend fun saveMinActuationMin(min: Int) {
        settingsStore.setMinActuationMin(min)
        refreshAlarmConfig()
    }

    /** F7 (D1/D4) — persist the over-temperature ALERT knobs and re-hydrate [alarmConfig] so the next
     *  service start builds an [AlarmEngine] carrying them. The over-temp alarm is EXEMPT from DEATH's
     *  global suppression (D4): it still fires when the device runs hot even with alarms silenced. */
    suspend fun saveOverTempConfig(enabled: Boolean, alertC: Double, clearC: Double, critical: Boolean) {
        settingsStore.setOverTempConfig(enabled, alertC, clearC, critical)
        refreshAlarmConfig()
    }

    /** Export the full config as pretty JSON (for a SAF write). Off-main. */
    suspend fun exportConfigJson(): String = withContext(dispatchers.io) { settingsStore.exportJson() }

    /** Import a config JSON (from a SAF read); re-hydrates [alarmConfig]. Returns keys applied, or
     *  throws with a plain-language message on a malformed/foreign file. Off-main. */
    suspend fun importConfigJson(text: String): Int = withContext(dispatchers.io) {
        settingsStore.importJson(text).also { refreshAlarmConfig() }
    }

    // ─── Inference runtime (Phase 2) ──────────────────────────────────────────────────────────

    /** Dev-time models dir on the app's external files (adb-pushable; the 27 MB .pte is NOT bundled).
     *  Push with: `adb push descriptor.json <this>/` and `adb push t1dmai_best.xnnpack.pte <this>/`. */
    val modelsDir: File = File(appContext.getExternalFilesDir(null), "models").apply { mkdirs() }

    /** The Phase-2 orchestrator: builds one shared context per cycle, fans out serially over the
     *  running set, decodes + guards in Rust, publishes + persists predictions tagged by model_id. */
    val inferenceController: InferenceController by lazy {
        buildInferenceController(
            native = nativeCore,
            dispatchers = dispatchers,
            modelsDir = modelsDir,
            history = RoomBgHistoryProvider(repository, registry),
            // Phase 3: the dedicated `prediction` table is the source of truth, and every cycle
            // enqueues a deduped `PREDICTIONS` batch for all running models (retires the kv blob).
            predictionStore = RoomPredictionStore(repository, outboxEnqueuer, syncStatusStore),
            // Phase 4 completion: the main-view forecast now conditions feat 1 / feat 2 on the
            // reconstructed carb-appearance + insulin-action channels (SPEC §3.3), not `normalize(0)`.
            contextChannels = ContextChannelSource { gridStartMs, nSteps ->
                dashboardCurveChannels(gridStartMs, nSteps)
            },
            // ...and the PREDICTION ZONE on the COMMITTED dose tails (already-logged meals/doses still
            // absorbing past the now-boundary), via the SAME curve engine the calculator uses — so a
            // just-logged meal RAISES the forecast rather than pulling it down (SPEC §3.3).
            futureOverrides = FutureOverrideSource { rollStartMs, nFutureSteps ->
                dashboardFutureChannels(rollStartMs, nFutureSteps)
            },
            // WARMUP gate: read the user's setting fresh each cycle (inference-runtime.md).
            warmupHoursProvider = { warmupHours() },
            // Per-model forecast-backend preference, re-read fresh for every discovered id (issue 20).
            backendPrefProvider = { id -> forecastBackendPref(id) },
            // Phase 7C: durable cumulative per-model inference telemetry for the Models drill-down.
            telemetryStore = KvTelemetryStore(repository),
            // F6 THERMAL GATE (D1: battery-sensor °C): re-read the enable flag + thresholds fresh per
            // cycle. Disabled ⇒ null ⇒ no gate. Deliberately NO death-mode check (D4: the over-temp
            // inference gate stays ACTIVE in DEATH — the die does not care about the alarm override).
            thermalProvider = {
                if (!settingsStore.currentThermalGateEnabled()) null
                else withContext(dispatchers.io) { readDeviceTempC() }?.let { c ->
                    ThermalStatus(
                        currentC = c,
                        thresholdC = settingsStore.currentInferenceMaxTempC(),
                        warnMarginC = settingsStore.currentThermalWarnMarginC(),
                        resumeMarginC = THERMAL_RESUME_MARGIN_C,
                    )
                }
            },
        )
    }

    val inferenceState: StateFlow<InferenceState> get() = inferenceController.state

    /**
     * Re-run one inference evaluation now (e.g. on app resume) so the panels reflect the CURRENT context
     * promptly instead of the last 5-min grid cycle's possibly-stale forecast. Serialised with the FGS
     * cycles by the controller's own mutex and gated identically — this never bypasses a §3.6 gate, it
     * only re-runs the same evaluation sooner.
     */
    fun reevaluateInferenceNow() {
        appScope.launch {
            runCatching { inferenceController.runFromHistory(InferenceCause.GRID_TICK, System.currentTimeMillis()) }
        }
    }

    /** Build the read-only About-panel model (Phase 7C — item 18): identity, version/build, licence,
     *  and the loaded model's provenance. Public-safe (no secrets). Reads the selected model's meta. */
    fun aboutInfo(): AboutInfo {
        val meta = inferenceState.value.let { st -> st.selectedPrediction?.modelId ?: st.running.firstOrNull { it.selected }?.modelId }
            ?.let { id -> inferenceState.value.metaOf(id) } ?: inferenceState.value.metas.firstOrNull()
        val nativeOk = runCatching { nativeCore.roundtrip("about") == "rust-core echo: about" }.getOrDefault(false)
        return AboutInfo(
            appName = "T1DM",
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            applicationId = appContext.packageName,
            flavor = BuildConfig.FLAVOR,
            buildType = BuildConfig.BUILD_TYPE,
            gitSha = BuildConfig.GIT_SHA,
            license = "GNU General Public License v3.0 (GPL-3.0). This program is free software: you may " +
                "redistribute it and/or modify it under the terms of the GPL as published by the Free " +
                "Software Foundation, either version 3, or (at your option) any later version. Distributed " +
                "WITHOUT ANY WARRANTY. Advisory only — this software never actuates insulin.",
            modelId = meta?.modelId,
            modelArchVersion = meta?.archVersion,
            modelParamCount = meta?.paramCount,
            executorchVersion = meta?.executorchVersion ?: BuildConfig.EXECUTORCH_VERSION,
            nativeCoreStatus = if (nativeOk) "t1dm-core (uniffi) — alive" else "t1dm-core — stub / unavailable",
        )
    }

    /** Detected-hardware probe for the Hardware panel top readout (Phase 7C — item 8). */
    private val hardwareProbe by lazy { HardwareProbe(appContext) }

    /** Probe the device hardware off-main (Build/proc/sys/services + an EGL renderer query). */
    suspend fun detectHardware(): HardwareInfo =
        withContext(dispatchers.io) { hardwareProbe.probe() }

    // ── Device temperature (U9 — no fan; the RPM is permission-denied even to adb, so we surface a
    // genuinely readable, LABELLED device temperature instead). The source is BatteryManager's
    // EXTRA_TEMPERATURE (tenths of °C); display unit is user-selectable C/F/K.
    val temperatureUnit: Flow<TempUnit> = settingsStore.temperatureUnit.map { TempUnit.fromKey(it) }
    suspend fun setTemperatureUnit(u: TempUnit) = settingsStore.setTemperatureUnit(u.key)

    /** The device (battery-sensor) temperature in Celsius, or null if unreadable. Cheap sticky-intent
     *  read; call off-main from a poller. This is a REAL sensor value — never a proxied fan figure. */
    fun readDeviceTempC(): Double? = runCatching {
        val intent = appContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10.0 }
    }.getOrNull()

    // ── Thermal inference gate (F6, D1/D3) — passthroughs to the persisted knobs. The gate itself is
    // wired into the controller via `thermalProvider` above (re-read fresh each cycle); these expose the
    // same knobs to the Settings sub-screen + the dashboard TEMP-chip colouring. Celsius throughout. ──
    val thermalGateEnabled: Flow<Boolean> = settingsStore.thermalGateEnabled
    val inferenceMaxTempC: Flow<Double> = settingsStore.inferenceMaxTempC
    val thermalWarnMarginC: Flow<Double> = settingsStore.thermalWarnMarginC
    suspend fun setThermalGateEnabled(on: Boolean) = settingsStore.setThermalGateEnabled(on)
    suspend fun setInferenceMaxTempC(c: Double) = settingsStore.setInferenceMaxTempC(c)
    suspend fun setThermalWarnMarginC(c: Double) = settingsStore.setThermalWarnMarginC(c)

    /**
     * On-device realized forecast accuracy for [modelId] over the trailing [days] (Phase 7C — Models
     * drill-down): pairs every matured `prediction` row with the realized MEASURED BG at 30/60/120 min
     * and reduces to per-horizon RMSE/MAE/MARD + central-90 coverage in the golden-gated Rust core.
     * A horizon with fewer than [minSamples] matured pairs is flagged insufficient. Off-main.
     */
    suspend fun modelAccuracy(
        modelId: String,
        days: Int = 14,
        minSamples: Int = 6,
    ): AccuracyReport {
        val now = System.currentTimeMillis()
        val since = now - days.toLong() * 86_400_000L
        val pairs = repository.forecastAccuracyPairs(modelId, ACCURACY_HORIZONS_MIN, since, now)
        return nativeCore.accuracyAtHorizons(pairs, minSamples)
    }

    // ── WARMUP setting (inference-runtime.md) — kv-backed, floored at the model MIN_CONTEXT ──────

    /** The trailing-window WARMUP requirement, in hours. Default 24; floored at the model MIN_CONTEXT
     *  (8 h) so the gate can never fall below the context the model needs to run at all. */
    suspend fun warmupHours(): Double =
        (repository.getKv(KV_WARMUP_HOURS)?.toDoubleOrNull() ?: InferenceControllerDefaults.WARMUP_HOURS)
            .coerceAtLeast(InferenceControllerDefaults.MIN_WARMUP_HOURS.toDouble())

    /** Settings read model: the current whole-hour warmup window (floored), for the human-readable row. */
    val warmupHoursSetting: Flow<Int> = repository.observeKv(KV_WARMUP_HOURS).map { raw ->
        (raw?.toDoubleOrNull() ?: InferenceControllerDefaults.WARMUP_HOURS)
            .coerceAtLeast(InferenceControllerDefaults.MIN_WARMUP_HOURS.toDouble())
            .toInt()
    }

    /** Persist the warmup window (whole hours), clamped to `[MIN_CONTEXT, 72]`. Off-main. */
    suspend fun setWarmupHours(hours: Int) {
        val clamped = hours.coerceIn(InferenceControllerDefaults.MIN_WARMUP_HOURS, WARMUP_HOURS_MAX)
        repository.putKv(KV_WARMUP_HOURS, clamped.toString(), System.currentTimeMillis())
    }

    // ── Forecast-backend switcher (issue 20 STEP 4) — kv-backed; governs the FORECAST CYCLE only ──

    /** The persisted forecast-backend preference for [modelId] (null/blank ⇒ auto = fp32 XNNPACK). */
    private suspend fun forecastBackendPref(modelId: String): BackendId? =
        repository.getKv(kvForecastBackend(modelId))?.takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { BackendId.valueOf(name) }.getOrNull() }

    /** Settings read model: the requested backend for [modelId] (or null for auto), for the selector's
     *  current row on the model's detail screen. */
    fun forecastBackendSetting(modelId: String): Flow<BackendId?> =
        repository.observeKv(kvForecastBackend(modelId)).map { raw ->
            raw?.takeIf { it.isNotBlank() }?.let { name -> runCatching { BackendId.valueOf(name) }.getOrNull() }
        }

    /**
     * Persist + apply the forecast-backend choice for one model. Governs the DISPLAY forecast cycle
     * ONLY; dosing stays fail-closed on a non-authoritative backend until the agreement probe passes
     * (§3.6-E). Persists to kv FIRST (the controller's discovery re-reads it), then re-runs discovery.
     * Returns the backend ACTUALLY active for [modelId] afterwards (may differ from the request if it
     * failed to load).
     */
    suspend fun setForecastBackend(modelId: String, backend: BackendId?): BackendId? {
        repository.putKv(kvForecastBackend(modelId), backend?.name ?: "", System.currentTimeMillis())
        return inferenceController.setForecastBackend(modelId, backend)
    }

    /** Run the on-device GPU-vs-CPU comparison + agreement probe (issue 20 STEP 3). Off the main
     *  thread inside the controller; publishes the result into [inferenceState]. */
    suspend fun runBackendComparison(runs: Int = 20) = inferenceController.runBackendComparison(runs)

    /** Discover on-device models + rehydrate the last predictions once at startup (off-main). Also
     *  hydrates the live [alarmConfig] snapshot from the persisted thresholds before the FGS reads it. */
    fun startInference() {
        appScope.launch {
            refreshAlarmConfig()
            inferenceController.restoreLast()
            // Discovery re-reads each model's persisted forecast-backend choice via backendPrefProvider,
            // so the active handle + "executing on" line are correct from the first tick.
            inferenceController.refreshModels()
            // Surface any update staged in a prior session (killed before applying) even before a sync.
            refreshPendingModelUpdates()
            // Trigger 1 — auto-fetch from the active server at startup (product decision 1). Sequenced
            // AFTER the initial discovery so the running-set is known: an update to the just-loaded dosing
            // model is then staged for manual apply, never applied-in-place before load, while a fresh
            // install adopts a newly-fetched model. A slow/failed network is swallowed inside autoSyncModels.
            autoSyncModels("startup")
        }
        // Keep the notification-icon theme snapshot current (issue I1).
        appScope.launch { settingsStore.themeId.collect { themeIdSnapshot = it } }
        appScope.launch { settingsStore.customThemeJson.collect { customThemeJsonSnapshot = it } }
        appScope.launch { settingsStore.deathMode.collect { deathModeSnapshot = it } }
        appScope.launch { settingsStore.forecastMode.collect { forecastModeSnapshot = it } }
        // GMI is slow-moving; recompute the 30-day estimate every 30 min (once at startup) so the widget
        // reads a cheap cached value instead of a 30-day recompute on every 30 s refresh.
        appScope.launch(dispatchers.default) {
            while (isActive) {
                val now = System.currentTimeMillis()
                gmiSnapshot = runCatching { statsRepository.localStats(StatsWindow.D30).gmi }
                    .getOrNull()?.takeIf { it in 3.0..25.0 }
                // §3.6 — the phone is the sole stats author: push all three windows for the server to
                // store verbatim (it never computes). enqueueStats dedups to ≤1/window/day.
                pushStats(now)
                delay(30 * 60_000L)
            }
        }
        // §3.8 (H7) — on each transition into a live WS connection, re-mirror the phone's authoritative
        // history to the server IFF its store_epoch differs from the last fully-mirrored one (a wiped/new
        // server, or first-ever sync). The epoch guard makes reconnecting to the same server a no-op.
        appScope.launch {
            var wasConnected = false
            syncStatus.collect { st ->
                val connected = st.wsState == WsConnState.CONNECTED
                if (connected && !wasConnected) {
                    runCatching { reMirrorIfNewServer() }.onFailure { Timber.w(it, "server re-mirror failed") }
                }
                wasConnected = connected
            }
        }
    }

    // ─── Server sync (Phase 3) ────────────────────────────────────────────────────────────────

    /** Per-profile `rw` token at rest, Keystore-wrapped — never in the keep-forever Room DB. */
    val tokenStore: TokenStore by lazy { KeystoreTokenStore(appContext) }

    /** N-profile store (one active); the endpoint provider both the client and the stream follow. */
    val serverProfileStore: ServerProfileStore by lazy { ServerProfileStore(repository, tokenStore) }

    val syncHttpClient: SyncHttpClient by lazy {
        OkHttpSyncClient(
            endpoint = { serverProfileStore.activeEndpoint() },
            dispatchers = dispatchers,
        )
    }

    /**
     * Fetches the server's model registry and reconciles it into [modelsDir] so a fresh export becomes
     * discoverable by `ModelStore.discover()`. Inference-agnostic: the "is this the running model?"
     * question is the injected running-set provider — the loaded models' on-disk `.pte` FILENAMES (via
     * [InferenceController.runningArtifactFileNames]), NOT their descriptor ids (which can diverge from
     * the filename for an adb-pushed model and would let the guard miss). So an update to the
     * CURRENTLY-DOSING model is STAGED (never silently swapped) and surfaced for a manual
     * [applyModelUpdate], while a brand-new model is applied in place and adopted on the next
     * `refreshModels()`. Verification (bytes' SHA-256 vs `X-SHA256`) happens inside the coordinator
     * BEFORE anything discoverable is written.
     */
    val modelSyncCoordinator: ModelSyncCoordinator by lazy {
        ModelSyncCoordinator(
            modelsDir = modelsDir,
            http = syncHttpClient,
            runningArtifacts = { inferenceController.runningArtifactFileNames() },
        )
    }

    /** Last human-readable model-sync result line for the Settings → Server read-out (null until run). */
    val modelSyncStatus = MutableStateFlow<String?>(null)

    /** Descriptor ids with a downloaded-but-unapplied update staged in `pending/` — the "update
     *  available — apply" surface for the Models screen (product decision 2). Refreshed after every
     *  sync/apply; a staged update never swaps the running/dosing model on its own. */
    val pendingModelUpdates = MutableStateFlow<Set<String>>(emptySet())

    private suspend fun refreshPendingModelUpdates() {
        pendingModelUpdates.value = withContext(dispatchers.io) {
            runCatching { modelSyncCoordinator.pendingModelIds() }.getOrDefault(emptySet())
        }
    }

    val outboxEnqueuer: OutboxEnqueuer by lazy { OutboxEnqueuer(repository) }

    /** Live Network-panel telemetry (process-scoped; the durable outbox itself is persisted). */
    val syncStatusStore: SyncStatusStore = SyncStatusStore()

    private val drainConfig: DrainConfig = DrainConfig()

    private val queueDrainer: QueueDrainer by lazy {
        QueueDrainer(
            dao = database.outboxDao(),
            http = syncHttpClient,
            sampleAt = repository::sampleAt,
            dispatchers = dispatchers,
            config = drainConfig,
        )
    }

    private val streamClient by lazy {
        WebSocketStreamClient(
            endpoint = { serverProfileStore.activeEndpoint() },
            dispatchers = dispatchers,
        )
    }

    private val catchUpCoordinator by lazy {
        CatchUpCoordinator(stream = streamClient, http = syncHttpClient, repo = repository)
    }

    /** The always-on sync orchestrator; the FGS calls [SyncManager.launch] in its lifecycle scope. */
    val syncManager: SyncManager by lazy {
        SyncManager(
            drainer = queueDrainer,
            catchUp = catchUpCoordinator,
            repository = repository,
            status = syncStatusStore,
            dispatchers = dispatchers,
        )
    }

    val syncStatus: StateFlow<SyncStatus> get() = syncStatusStore.state

    /** Configured outbox bounds (surfaced on the Network panel next to the live depth/age). */
    val outboxMaxAgeMs: Long get() = drainConfig.maxAgeMs
    val outboxMaxSize: Int get() = drainConfig.maxQueueSize

    /**
     * Issue 2 — a snapshot of the DEVICE's own network posture for the Network panel: whether we are
     * online (and internet-validated), the active transport, the Wi-Fi signal/link/SSID, and the up
     * non-loopback interfaces + their addresses (so Tailscale's `tun0`, `wlan0`, etc. surface). Every
     * service read is wrapped so a missing service / SecurityException / Wi-Fi-off yields a safe partial
     * snapshot rather than a throw. Off-main. Advisory display only — never touches any rail.
     */
    suspend fun networkDiagnostics(): NetworkDiagnostics = withContext(dispatchers.io) {
        val cm = runCatching {
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        }.getOrNull()
        val caps = runCatching { cm?.let { it.getNetworkCapabilities(it.activeNetwork) } }.getOrNull()
        val online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "none"
        }
        val metered = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        val wifi = runCatching {
            appContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        }.getOrNull()
        // WifiInfo.INVALID_RSSI (-127) means "no readable RSSI" (Wi-Fi off / disconnected).
        val info = runCatching { @Suppress("DEPRECATION") wifi?.connectionInfo }.getOrNull()
        val rssi = runCatching { info?.rssi }.getOrNull()?.takeIf { it != -127 && it > -200 }
        val level = rssi?.let {
            runCatching { @Suppress("DEPRECATION") android.net.wifi.WifiManager.calculateSignalLevel(it, 5) }.getOrNull()
        }
        val linkMbps = runCatching { info?.linkSpeed }.getOrNull()?.takeIf { it > 0 }
        val freq = runCatching { info?.frequency }.getOrNull()?.takeIf { it > 0 }
        val ssid = runCatching {
            @Suppress("DEPRECATION") info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        }.getOrNull()

        val interfaces = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .map { ni ->
                    val addrs = ni.inetAddresses.toList()
                        .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                        .mapNotNull { it.hostAddress?.substringBefore('%')?.takeIf { s -> s.isNotBlank() } }
                    NetIface(ni.name, addrs)
                }
                .filter { it.addresses.isNotEmpty() }
        }.getOrDefault(emptyList())

        NetworkDiagnostics(
            online = online,
            validated = validated,
            transport = transport,
            metered = metered,
            wifiSsid = ssid,
            wifiRssiDbm = rssi,
            wifiLevel = level,
            wifiLinkMbps = linkMbps,
            wifiFreqMhz = freq,
            interfaces = interfaces,
        )
    }

    // ─── Server profile read models + config actions (Settings → Server) ──────────────────────

    val serverProfiles: Flow<List<ServerProfile>> = serverProfileStore.observeProfiles()

    val activeServerProfile: Flow<ServerProfile?> = serverProfileStore.observeActive()

    /**
     * Create/update the primary server profile and make it active (the Phase-3 single-profile UI;
     * the store is N-profile so multi-profile CRUD is additive later). A blank [token] keeps the
     * stored one. Runs off-main.
     */
    suspend fun saveServerProfile(label: String, baseUrl: String, token: String) {
        val existing = repository.activeProfile()
        serverProfileStore.upsert(
            id = existing?.id ?: "default",
            label = label.ifBlank { "server" },
            baseUrl = baseUrl,
            token = token.ifBlank { null },
            makeActive = true,
            nowMs = System.currentTimeMillis(),
        )
        // Trigger 2 — a profile is now active: auto-fetch models (product decision 1). Silent/logged;
        // covers both the Settings save and the debug configureServer entrypoint that funnel here.
        launchAutoModelSync("profile-saved")
    }

    /**
     * Re-download the FULL historical series from the active server profile (the Phase-3 REST
     * catch-up). Pages `GET /v1/series` from the very start and LWW-merges every row into the wide
     * `sample` table. This is the re-sync the reset round-trip relies on: after a wipe the user
     * re-enters the token, and this refills the (now-empty) series from T1DMSERVER. Returns the number
     * of rows merged; 0 when no profile/token is configured. Off-main; never blocks the alarm path.
     */
    suspend fun resyncFromServer(): Int = withContext(dispatchers.io) {
        if (serverProfileStore.activeEndpoint() == null) 0
        else runCatching { catchUpCoordinator.catchUp(null) }.getOrDefault(0)
    }

    /**
     * §3.8 (H7) — re-mirror the phone's authoritative history to a freshly-wiped or brand-new server.
     * The clean-break cutover (§6) wipes the server, and the outbox holds only *pending* writes, not
     * history — so on connecting we compare the server's `store_epoch` (authed `GET /v1/health`) against
     * the last fully-mirrored epoch (kv [KV_MIRRORED_EPOCH]). When they differ (a wiped/new server, or
     * first-ever sync) we walk the local meals / doses / scalar-sample / stats history in `ts` order and
     * enqueue each as its idempotent PUT, in bounded, drain-paced batches so the outbox size cap (§3.7)
     * is never blown in one shot. The epoch is recorded only on FULL completion, so a partial re-mirror
     * (or a stalled drain) simply resumes on the next connect; every write being idempotent, an overlap
     * with surviving server rows is a no-op. This is the upload inverse of the [resyncFromServer]
     * download. Off-main, fully guarded — never actuates, never blocks the alarm path.
     */
    private suspend fun reMirrorIfNewServer() = withContext(dispatchers.io) {
        if (serverProfileStore.activeEndpoint() == null) return@withContext
        val epoch = syncHttpClient.health().store_epoch?.takeIf { it.isNotBlank() } ?: return@withContext
        val mirrored = repository.getKv(KV_MIRRORED_EPOCH)
        if (epoch == mirrored) return@withContext
        Timber.i("re-mirroring history: server store_epoch %s ≠ mirrored %s", epoch, mirrored)
        val now = System.currentTimeMillis()
        // Meals + doses: irreplaceable clinical records (non-age-evictable, high outbox priority).
        for (chunk in repository.loggedMealsInRange(0L, now).sortedBy { it.tsMs }.chunked(REMIRROR_BATCH)) {
            pace(); for (m in chunk) outboxEnqueuer.enqueueMeal(m.toMealEventDto(), now)
        }
        for (chunk in repository.loggedDosesInRange(0L, now).sortedBy { it.tsMs }.chunked(REMIRROR_BATCH)) {
            pace(); for (d in chunk) outboxEnqueuer.enqueueDose(d.toDoseEventDto(), now)
        }
        // Scalar sample history: one INGEST dirty-marker per bucket (the drainer resolves the current
        // row and POSTs `/v1/ingest`). Age-evictable, so it is paced hardest against the drain.
        for (chunk in repository.samplesInRange(0L, now).sortedBy { it.ts }.chunked(REMIRROR_BATCH)) {
            pace(); for (s in chunk) outboxEnqueuer.enqueueIngest(s.ts, now)
        }
        // Latest-per-window stats blocks (deduped ≤1/window/day; a no-op if the slow loop already pushed).
        pushStats(now)
        // The whole walk enqueued without a stalled-drain abort ⇒ record this epoch as fully mirrored.
        repository.putKv(KV_MIRRORED_EPOCH, epoch, now)
    }

    /**
     * H7 back-pressure: suspend until the outbox drains below half its size cap before enqueuing the
     * next re-mirror batch, so a bulk re-push never blows the cap (§3.7) and self-evicts the very
     * (age-evictable) INGEST markers it just added. Bounded — a drain stalled past [REMIRROR_MAX_PACE_WAITS]
     * throws, aborting the walk before the epoch is recorded, so it resumes cleanly on the next connect.
     */
    private suspend fun pace() {
        var waits = 0
        while (syncStatus.value.outboxDepth > outboxMaxSize / 2) {
            check(waits < REMIRROR_MAX_PACE_WAITS) { "re-mirror drain stalled at depth ${syncStatus.value.outboxDepth}" }
            delay(1_000L)
            waits++
        }
    }

    // ─── Full app reset (issue 5 — DESTRUCTIVE, IRREVERSIBLE) ──────────────────────────────────

    /**
     * Erase EVERYTHING and return the app to a first-run state, without a manual force-stop. Order
     * matters: (1) stop the always-on foreground service so the inference cycle, the sync drain, the
     * 5-min watch push, and the glance collectors all halt — nothing may re-write a reading or a watch
     * nonce ceiling under the wipe (§3.6-A: there is no data left to alarm on, and the deterministic
     * path is rebuilt on the post-reset relaunch); (2) row-wipe every user/runtime table at the
     * current schema version, keeping the shipped model artifacts + seed dictionaries (see
     * [T1dmRepository.wipeAllData]) — this also clears the watch pairing/epoch/nonce-ceiling kv rows;
     * (3) burn the secrets that live OUTSIDE Room — the Keystore-wrapped server token(s) and the watch
     * key-wrapping alias (the watch key material itself was a kv blob, already gone in step 2). The
     * caller then relaunches via [restartApp] so every app-lifetime StateFlow rebuilds from the empty
     * store. Off-main.
     */
    suspend fun resetAllData() = withContext(dispatchers.io) {
        runCatching { appContext.stopService(Intent(appContext, com.t1dm.app.service.CgmScanService::class.java)) }
        serviceRunning.value = false
        // Drop the in-memory watch session + disable the link BEFORE the wipe so no late 5-min push can
        // re-persist key material or a nonce ceiling into the kv rows we are about to clear (which would
        // resurrect the pairing the reset is erasing). Only touch it if the watch was ever wired up.
        runCatching { watchLink.stopForReset() }
        repository.wipeAllData()
        runCatching { tokenStore.clearAll() }
        com.t1dm.app.watch.WatchKeyCipher.deleteKey()
    }

    /**
     * Relaunch the app in a fresh process (issue 5) — the clean alternative to a manual force-stop.
     * A brand-new process rebuilds [AppContainer] against the wiped store, so every cached StateFlow
     * (predictions, IOB/COB, watch session, theme, settings) returns to its first-run value and the
     * foreground service restarts from [MainActivity]. Never returns.
     */
    fun restartApp() {
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { appContext.startActivity(it) }
        Runtime.getRuntime().exit(0)
    }

    /**
     * Issue 7 — upload a meal photo (`POST /v1/photos`, multipart) around a just-logged meal. A direct
     * multipart call (not the JSON outbox), wrapped so no server/IO fault can crash the UI: with no
     * profile/token, or a transport/HTTP failure, this returns a failed [Result] the caller renders as
     * a plain status line. Never actuates anything. Off-main.
     */
    suspend fun uploadMealPhoto(tsMs: Long, bytes: ByteArray, ext: String): Result<Unit> =
        withContext(dispatchers.io) {
            runCatching { syncHttpClient.postPhoto(tsMs, bytes, ext); Unit }
        }

    /**
     * The MANUAL "Sync models from server" entrypoint (Settings → Server). Runs the coordinator off the
     * main thread, then re-discovers so a fresh download becomes loadable, and publishes a plain-language
     * result line into [modelSyncStatus]. Never throws — a network/list failure surfaces as a status
     * line, not a crash. A running-model update is downloaded but STAGED (see [applyModelUpdate]); a new
     * model is adopted on the trailing `refreshModels()`.
     */
    suspend fun syncModelsFromServer(): String {
        val line = if (serverProfileStore.activeEndpoint() == null) {
            "no active profile / token configured"
        } else {
            runCatching {
                val summary = withContext(dispatchers.io) { modelSyncCoordinator.sync() }
                inferenceController.refreshModels()
                summarizeModelSync(summary)
            }.getOrElse { e -> "sync failed — ${e.message ?: e::class.simpleName}" }
        }
        refreshPendingModelUpdates()
        modelSyncStatus.value = line
        return line
    }

    /**
     * Promote a staged running-model update (`pending/`) into the live models dir and re-discover so the
     * new artifact is loaded and re-selected — the manual half of auto-download / manual-apply (product
     * decision 2), mirroring the running-set selection flow. Returns false if no complete staged pair
     * exists. Off-main.
     */
    suspend fun applyModelUpdate(modelId: String): Boolean = withContext(dispatchers.io) {
        val applied = runCatching { modelSyncCoordinator.applyPending(modelId) }.getOrDefault(false)
        if (applied) inferenceController.refreshModels()
        refreshPendingModelUpdates()
        applied
    }

    /**
     * F4 — delete a discovered model (Models screen ✕). Removes the on-disk descriptor+pte pair and prunes
     * the controller's per-model state, then wipes its persisted predictions + forecast-backend kv row and
     * re-runs discovery so the running-set + any pending-update surface reflow. Each step is guarded so a
     * partial failure never crashes the UI; a re-evaluation follows so the panels drop the gone model's
     * forecast promptly (the selected model deleted ⇒ fail-closed "no model" until another is selected).
     */
    suspend fun removeModel(modelId: String) {
        runCatching { inferenceController.deleteModel(modelId) }
        withContext(dispatchers.io) {
            runCatching { repository.deletePredictionsForModel(modelId) }
            runCatching { repository.putKv(kvForecastBackend(modelId), "", System.currentTimeMillis()) }
        }
        refreshPendingModelUpdates()
        reevaluateInferenceNow()
    }

    /** "downloaded N · M update(s) available · K up to date · S skipped · F failed" (empty ⇒ nothing served). */
    private fun summarizeModelSync(s: com.t1dm.sync.ModelSyncSummary): String {
        if (s.outcomes.isEmpty()) return "no models served"
        return buildList {
            if (s.fetchedNew.isNotEmpty()) add("downloaded ${s.fetchedNew.size}")
            if (s.updatesPendingApply.isNotEmpty()) add("${s.updatesPendingApply.size} update(s) available — apply in Models")
            if (s.alreadyCurrent.isNotEmpty()) add("${s.alreadyCurrent.size} up to date")
            if (s.skipped.isNotEmpty()) add("${s.skipped.size} skipped")
            if (s.failed.isNotEmpty()) add("${s.failed.size} failed")
        }.joinToString(" · ").ifEmpty { "nothing to do" }
    }

    /**
     * The AUTO model-sync body (startup + after a profile is saved): silent, off-main, guarded. A
     * down/absent endpoint throws in the coordinator and is swallowed (logged, never a crash — product
     * decision 1), then discovery re-runs so any fresh download is adopted and the pending-update surface
     * refreshed. Suspends so the startup path can sequence it AFTER the initial discovery (so the
     * running-set — hence the "never swap the running model" gate — is populated). Deliberately does NOT
     * touch [modelSyncStatus] (that line is the manual button's).
     */
    private suspend fun autoSyncModels(reason: String) {
        if (serverProfileStore.activeEndpoint() == null) return
        runCatching { modelSyncCoordinator.sync() }
            .onFailure { Timber.tag(ModelSyncCoordinator.TAG).w(it, "auto model sync failed (%s)", reason) }
        runCatching { inferenceController.refreshModels() }
        refreshPendingModelUpdates()
    }

    /** Fire-and-forget wrapper for the profile-saved trigger, where the model is already loaded so no
     *  ordering vs. the initial discovery is needed (the startup path awaits [autoSyncModels] directly). */
    private fun launchAutoModelSync(reason: String) {
        appScope.launch { autoSyncModels(reason) }
    }

    /** One-shot health probe against the active profile; a human-readable status line. */
    suspend fun checkServerHealth(): String = runCatching {
        val h = syncHttpClient.health()
        "reachable — status=${h.status}, ${h.ws_clients} ws client(s)"
    }.getOrElse { e ->
        when (e) {
            is NoActiveProfileException -> "no active profile / token configured"
            else -> "unreachable — ${e.message ?: e::class.simpleName}"
        }
    }

    // ─── Curve engine + manual entry + journal (Phase 4) ──────────────────────────────────────

    /** The shared curve/PK engine (thin JNI bridge; SPEC §3.3), reused for entry previews, the
     *  dashboard overlays, IOB/COB, and (downstream) `:inference`/`:calc` conditioning. */
    val curveEngine: CurveEngine by lazy { CurveEngine(nativeCore, dispatchers) }

    private val doseStore: DoseStore by lazy {
        RoomDoseStore(
            engine = curveEngine,
            loggedDoses = database.loggedDoseDao(),
            loggedMeals = database.loggedMealDao(),
            basalSchedules = database.basalScheduleDao(),
        )
    }

    /** Reconstructs the carb-appearance / insulin-action channels from the logged events (SPEC §3.3). */
    val channelBuilder: ChannelBuilder by lazy { ChannelBuilder(curveEngine, doseStore) }

    // ── Meal builder + insulin-type builder (Phase 4 deliverables 3/4) ─────────────────────────

    /** Mixes multi-food GI/custom shapes into one carb-appearance curve (reuses [curveEngine]). */
    val mealCurveResolver: MealCurveResolver by lazy { MealCurveResolver(curveEngine) }

    /** Glycemic dictionary (FTS5) + saved-meal orchestration; seeds the bundled dataset once. */
    val mealsController: MealsController by lazy { MealsController(repository, mealCurveResolver, dispatchers) }

    /** Custom insulin-type registry (quick presets + user types w/ drawn action curves). */
    val insulinController: InsulinController by lazy { InsulinController(repository, curveEngine, dispatchers) }

    val savedMeals: Flow<List<SavedMeal>> get() = mealsController.savedMeals
    val customFoods: Flow<List<Food>> get() = mealsController.customFoods

    /** The last 3 distinct GI-bearing logged meals, as quick-pick chips (Phase 7C, item 9). */
    val recentMeals: Flow<List<RecentMeal>> get() = repository.observeRecentMeals(3)
    val insulinTypes: Flow<List<InsulinType>> get() = insulinController.types

    /** Seed the bundled glycemic dictionary + the three insulin presets once, off-main (idempotent). */
    fun startBuilders() {
        appScope.launch {
            mealsController.seedIfEmpty()
            insulinController.seedBuiltinsIfEmpty()
        }
    }

    // Journal read models.
    val journalNotes: Flow<List<JournalNote>> = repository.observeNotes()
    val latestMood: Flow<Int?> = repository.observeLatestMood()

    /** Live preview of the exact carb appearance (Ra) curve the model will see for a GI. */
    val previewCarbCurve: suspend (Double, Double) -> DoubleArray = { grams, gi ->
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        curveEngine.gamma(grams, k, theta, dur)
    }

    /** Live preview of the rapid-acting bolus PK-action curve (== `bolus_pk_for_dose`). */
    val previewBolusCurve: suspend (Double) -> DoubleArray = { units ->
        val rapid = resolveRapid()
        curveEngine.expAction(units, rapid.peakMin, rapid.diaMin)
    }

    /** Live preview of the LONG-ACTING basal PK-action Bateman curve (issue N9): the same curve
     *  [logBasal] commits — the opted-in clinical basal preset's DIA/ka/ke if selected, else the
     *  in-distribution simulator Bateman for the chosen [BasalPreset]. Broad + near-flat by design. */
    val previewBasalCurve: suspend (Double, BasalPreset) -> DoubleArray = { units, _ ->
        val basal = resolveBasal()
        curveEngine.bateman(units, basal.diaMin, basal.kaPerHour, basal.kePerHour)
    }

    /** The dashboard overlay resolver: the two reconstructed channels over a grid window (off-main). */
    suspend fun dashboardCurveChannels(gridStartMs: Long, nSteps: Int): Pair<DoubleArray, DoubleArray> {
        val ch = channelBuilder.contextChannels(gridStartMs, nSteps)
        return ch.carb to ch.insulin
    }

    /** The BASAL-only overlay sub-channel over the same grid (issue 18): auto-extended schedule +
     *  logged long-acting injections, for the dashboard's separate basal series. Off-main. */
    suspend fun dashboardBasalChannel(gridStartMs: Long, nSteps: Int): DoubleArray =
        channelBuilder.basalChannel(gridStartMs, nSteps)

    /**
     * The COMMITTED dose tails over the prediction horizon `[rollStartMs, +nFutureSteps·STEP)` — the
     * already-logged meals/doses (+ auto-extended basal) still absorbing past the now-boundary (PLAN
     * §3.3). `announced`/`candidate` are empty here: those are the calculator's what-if injections, and
     * the committed logged doses are carried by `futureOverrides`' OWN store reads (passing them again
     * as `announced` would double-count). This is exactly the `RollingForecaster` baseline-roll input,
     * so the dashboard's directional response to a logged dose matches the calculator's. Off-main. */
    suspend fun dashboardFutureChannels(rollStartMs: Long, nFutureSteps: Int): Pair<DoubleArray, DoubleArray> {
        val fc = channelBuilder.futureOverrides(rollStartMs, nFutureSteps, announced = emptyList(), candidate = null)
        return fc.carb to fc.insulin
    }

    /** IOB/COB now, with §3.6-F provenance (logged doses only; last-logged age; basal presence). */
    suspend fun iobCobNow(): IobCobReadout {
        val now = System.currentTimeMillis()
        val iob = channelBuilder.onBoard(now, CurveKind.INSULIN)
        val cob = channelBuilder.onBoard(now, CurveKind.CARB)
        val lastLogged = repository.latestLoggedInsulinTs()
        val hasBasal = repository.activeBasalDoses().isNotEmpty()
        // F5: the instant the last active insulin (logged doses + basal tails) decays to zero — the
        // landmark the circadian panel's insulin-exhaustion countdown projects forward from.
        val iobZeroMs = runCatching { channelBuilder.insulinZeroMs(now) }.getOrNull()
        return IobCobReadout(
            atMs = now,
            iobU = iob,
            cobG = cob,
            minsSinceLastLoggedInsulin = lastLogged?.let { (now - it) / 60_000L },
            hasBasalSchedule = hasBasal,
            iobZeroMs = iobZeroMs,
        )
    }

    /** F5: the user-tunable DKA→coma→death offsets (hours, forward from IOB-zero), for the circadian
     *  panel's morbid insulin-exhaustion projection. DISPLAY-ONLY — no §3.6 gate reads this. */
    val dkaTimeline: Flow<DkaTimeline> =
        combine(
            settingsStore.dkaAfterIobZeroH,
            settingsStore.comaAfterDkaH,
            settingsStore.deathAfterComaH,
        ) { a, b, c -> DkaTimeline(a, b, c) }

    // ─── Dose calculator (Phase 4 §5 + §3.6 safety architecture) ────────────────────────────────

    /** The selected fp32-authoritative model handle for `:calc`; null (⇒ fail-closed refusal) when
     *  nothing is loaded/selected OR the [StubBackend] stood in for a missing `.pte` (`real == false`). */
    private val selectedModelProvider = SelectedModelProvider {
        val info = inferenceController.authorityModelInfo()
        if (info == null || !info.real) null
        else object : SelectedModelHandle {
            override val descriptor = info.descriptor
            override val backendInfo = calcBackendInfo(info)
            override suspend fun run(input: GraphInput): com.t1dm.inference.backend.GraphOutput =
                inferenceController.runSelectedAuthority(input)
        }
    }

    /**
     * The `:calc` backend provenance, pinned to the fp32 XNNPACK CPU **authority** (§3.6-E). Dose
     * advice always runs on the authority regardless of the switcher, so [info] is [authorityModelInfo]
     * (backend == XNNPACK, agreementOk == null ⇒ trustworthy by construction). We additionally carry the
     * currently-DISPLAYED backend when it differs, so the advisor can emit a small non-blocking note that
     * a GPU/NPU rendered the forecast while the dose was computed on the CPU authority. Informational
     * only — it can never affect `trustworthy` or a rail.
     */
    private fun calcBackendInfo(info: com.t1dm.inference.InferenceController.SelectedModelInfo): BackendInfo {
        val displayed = inferenceController.selectedModelInfo()?.backend
        return BackendInfo(
            backend = info.backend,
            precision = info.precision,
            agreementOk = info.agreementOk,
            displayedBackend = displayed?.takeIf { it != info.backend },
        )
    }

    /** The production rolled-forecast port: reuses the shared curve/channel engine + BG history, drives
     *  the selected fp32 model to the full ~5 h window, gating every roll on the Rust degeneracy check. */
    private val rollingForecaster by lazy {
        RollingForecaster(
            native = nativeCore,
            dispatchers = dispatchers,
            channels = channelBuilder,
            history = RoomBgHistoryProvider(repository, registry),
            selected = selectedModelProvider,
        )
    }

    /** Resolves a candidate dose into its dose-scaled gamma PK announced-future events (§3.3). */
    private val bolusResolver = BolusResolver { doseU, atMs ->
        val spec = resolveRapid()
        listOf(curveEngine.rapidEvent(doseU, atMs, spec.peakMin, spec.diaMin))
    }

    private val bolusCalculator by lazy { BolusCalculator(rollingForecaster, bolusResolver) }

    /** §3.6-D anchor facts from the active source's recent grid readings (fail-closed: null ⇒ no signal). */
    private val anchorSource = AnchorInfoSource { nowMs -> buildAnchorInfo(nowMs) }

    /** §3.6-F logged-doses-only IOB/COB snapshot (fail-closed: null ⇒ store failure). */
    private val iobSource = IobSource { nowMs -> buildIobSnapshot(nowMs) }

    /** §3.6-E backend/precision provenance; null (⇒ refusal) when there is no real selected model. */
    private val backendSource = BackendInfoSource {
        val info = inferenceController.authorityModelInfo()
        if (info == null || !info.real) null else calcBackendInfo(info)
    }

    /** The fail-closed bolus advisor: freshness/fp16 gate → grid search → degeneracy → rails → card. */
    val doseAdvisor: DoseAdvisor by lazy { DoseAdvisor(bolusCalculator, anchorSource, iobSource, backendSource) }

    /** The calculator UI/service surface: Idle → Running → Ready(result). Never actuates. */
    sealed interface BolusAdviceUi {
        data object Idle : BolusAdviceUi
        data object Running : BolusAdviceUi
        data class Ready(val result: AdviceResult) : BolusAdviceUi
    }

    val bolusAdvice = MutableStateFlow<BolusAdviceUi>(BolusAdviceUi.Idle)

    /** Run one fail-closed bolus recommendation (optionally conditioned on an announced meal). Called
     *  from [com.t1dm.app.service.DoseCalcService] on a cancellable foreground job. */
    suspend fun runBolusAdvice(
        announcedCarbG: Double,
        announcedGi: Double,
        manualTargetMgdl: Double? = null,
        config: CalcConfig? = null,
    ) {
        bolusAdvice.value = BolusAdviceUi.Running
        // The user's persisted calculator policy (target / objective / asymmetry / rails / thresholds),
        // loaded fresh per run so a Settings edit takes effect on the next recommendation.
        val base = config ?: runCatching { settingsStore.currentCalcConfig() }.getOrDefault(CalcConfig())
        // The Bolus advisor screen drives the search toward a single user-set target BG (§3.6, UNBOUNDED —
        // the slider's [low, high] bounds are the only limit): override the scoring objective so the grid
        // lands the forecast median on that value. Absent ⇒ the persisted objective stands.
        val cfg = if (manualTargetMgdl != null) base.copy(objective = Objective.HitTargetBg(manualTargetMgdl)) else base
        val now = System.currentTimeMillis()
        val announced: List<CurveEvent> = if (announcedCarbG > 0.0) {
            val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(announcedGi)
            listOf(curveEngine.carbEvent(announcedCarbG, now, k, theta, dur))
        } else emptyList()
        // DEATH mode also lifts the structural §3.6-B degeneracy refusal (the rails are already off via
        // currentCalcConfig) so the advisor emits a number rather than refusing off a bad forecast.
        val result = runCatching { doseAdvisor.recommendBolus(now, announced, cfg, bypassDegeneracyGate = deathModeSnapshot) }
            .getOrElse { AdviceResult.Refused(listOf("Calculator error — ${it.message ?: it::class.simpleName}. Refusing to recommend a dose.")) }
        bolusAdvice.value = BolusAdviceUi.Ready(result)
    }

    fun clearBolusAdvice() { bolusAdvice.value = BolusAdviceUi.Idle }

    // ── I2: the ON-DEMAND, DISPLAY-ONLY rolled forecast ────────────────────────────────────────────
    //
    // This is EPHEMERAL UI state, structurally isolated from the safety surfaces: it is a
    // [RolledForecast] (a distinct type from [ModelPrediction]/[PredFan]), it is NEVER written into
    // [inferenceState].predictions, it is NEVER passed to [doseAdvisor], and it is NEVER read by the
    // ongoing-notification computer or the top-bar indicator (both read [inferenceState]). So a
    // 12×-rolled fan can never raise an alert or influence a dose — "HYPO in 19H" is impossible.

    /** The ephemeral rolled fan the BG panel draws, or null when none is requested. */
    val rolledForecast = MutableStateFlow<RolledForecast?>(null)

    /** True while a roll is being computed (drives the panel's progress spinner). */
    val rollComputing = MutableStateFlow(false)

    private var rollJob: Job? = null

    /**
     * Compute one on-demand autoregressive roll to [requestedHours] on the fp32 CPU **authority**
     * (never the GPU — sequential rolls multiply forwards, and the GPU is ~4.5× worse per forward),
     * reusing the `:calc` [RollingForecaster] math with the Rust degeneracy guard PER ROLL. Fail-closed:
     * a missing model, a degenerate roll, or any error yields a non-eligible [RolledForecast] with a
     * plain reason — never a throw. The result is display-only.
     */
    fun requestRollForDisplay(requestedHours: Double) {
        rollJob?.cancel()
        rollJob = appScope.launch {
            rollComputing.value = true
            try {
                val cfg = runCatching { settingsStore.currentCalcConfig() }.getOrDefault(CalcConfig())
                val validated = cfg.horizon.validatedSteps
                val rf = runCatching {
                    rollingForecaster.rollForDisplay(System.currentTimeMillis(), requestedHours, validated)
                }.getOrElse {
                    RolledForecast.missing(
                        requestedHours,
                        Math.ceil(requestedHours / 2.0).toInt(),
                        "Roll failed — ${it.message ?: it::class.simpleName}. No rolled forecast is shown.",
                    )
                }
                rolledForecast.value = rf
            } finally {
                rollComputing.value = false
            }
        }
    }

    /** Dismiss the ephemeral rolled forecast. */
    fun clearRoll() {
        rollJob?.cancel()
        rollComputing.value = false
        rolledForecast.value = null
    }

    /** Record the human's acceptance of an advised bolus — logs it exactly like a manual bolus (the
     *  same self-describing `logged_dose` + series push). This never actuates; it only journals the
     *  dose the user tells us they administered. A 0 U / carb-rescue acceptance logs nothing here. */
    suspend fun acceptAdvisedBolus(units: Double) {
        if (units > 0.0) logBolus(units, BolusPreset.NOVORAPID)
    }

    private suspend fun buildAnchorInfo(nowMs: Long): AnchorInfo? {
        val srcId = repository.activeSourceId() ?: return null
        val recent = repository.recentReadings(srcId, 36) // ~3 h of 5-min grid context
        if (recent.isEmpty()) return null
        val lastMeasured = recent
            .filter { it.provenance == ReadingProvenance.MEASURED && it.flag == ReadingFlag.NORMAL && it.bgMgdl != null }
            .maxByOrNull { it.tsMs }
        val newest = recent.maxByOrNull { it.tsMs }!!
        val fabricated = recent.count { it.provenance == ReadingProvenance.INTERPOLATED || it.flag == ReadingFlag.WARMUP }
        return AnchorInfo(
            lastMeasuredTsMs = lastMeasured?.tsMs,
            anchorTsMs = newest.tsMs,
            currentBgMgdl = lastMeasured?.bgMgdl?.toDouble(),
            interpolatedFraction = fabricated.toDouble() / recent.size,
            warmup = newest.flag == ReadingFlag.WARMUP,
        )
    }

    private suspend fun buildIobSnapshot(nowMs: Long): IobSnapshot? = runCatching {
        IobSnapshot(
            iobU = channelBuilder.onBoard(nowMs, CurveKind.INSULIN),
            cobG = channelBuilder.onBoard(nowMs, CurveKind.CARB),
            lastLoggedDoseTsMs = repository.latestLoggedInsulinTs(),
        )
    }.getOrNull()

    // ── Entry writers: persist the self-describing event, project the wide sample, mirror the series.

    /** Log a single-food meal: persist the self-describing `logged_meal` (GI→gamma; the repository
     *  grid-snaps `ts` and mints the `client_id`) and push it as a `PUT /v1/meals` event built from the
     *  PERSISTED entity, so app + sample + wire agree on one grid ts and one id (§3.1/§3.2). */
    suspend fun logCarb(grams: Double, gi: Double) {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        val meal = repository.logMeal(
            LoggedMealEntity(
                clientId = "", tsMs = now, grams = grams, gi = gi, k = k, theta = theta,
                durationMin = dur, customCurve = null, tzOffsetMin = tz, note = null, updatedAt = now,
            ),
        )
        outboxEnqueuer.enqueueMeal(meal.toMealEventDto(), now)
    }

    /**
     * Log a multi-food builder meal (the Meals-screen builder path, invoked from Navigation). Persists
     * via [MealsController] — which resolves the combined appearance curve into `customCurve`, grid-snaps
     * `ts`, and mints the `client_id` — then pushes the resulting self-describing event as a
     * `PUT /v1/meals`. Before this the builder persisted but synced nothing (§3.2 builder-never-synced fix).
     */
    suspend fun logBuilderMeal(components: List<MealComponent>) {
        val now = System.currentTimeMillis()
        val meal = mealsController.logMeal(components)
        outboxEnqueuer.enqueueMeal(meal.toMealEventDto(), now)
    }

    /** The clinical insulin preset catalogue (issue 19), for the Settings picker + apply-at-log. */
    suspend fun insulinPresetCatalog(): List<InsulinPresetSpec> = curveEngine.presetCatalog()

    /** Resolve a preset's action curve for a 5 U reference (the Settings picker's live preview). */
    suspend fun previewPresetCurve(spec: InsulinPresetSpec): DoubleArray = when (spec.family) {
        InsulinFamily.RapidExp -> curveEngine.expAction(5.0, spec.peakMin, spec.diaMin)
        InsulinFamily.BasalBateman -> curveEngine.bateman(5.0, spec.diaMin, spec.kaPerHour, spec.kePerHour)
    }

    /** The active rapid preset spec (issue 19): the selected label, else the default, else the first. */
    private suspend fun resolveRapid(): InsulinPresetSpec {
        val cat = insulinPresetCatalog().filter { it.family == InsulinFamily.RapidExp }
        val label = settingsStore.currentRapidPreset()
        return cat.firstOrNull { it.label == label } ?: cat.firstOrNull { it.label == SettingsStore.DEFAULT_RAPID_PRESET_LABEL } ?: cat.first()
    }

    /** The active basal preset spec (issue 19): the selected label, else the default, else the first. */
    private suspend fun resolveBasal(): InsulinPresetSpec {
        val cat = insulinPresetCatalog().filter { it.family == InsulinFamily.BasalBateman }
        val label = settingsStore.currentBasalPreset()
        return cat.firstOrNull { it.label == label } ?: cat.firstOrNull { it.label == SettingsStore.DEFAULT_BASAL_PRESET_LABEL } ?: cat.first()
    }

    /** Log a bolus: self-describing `logged_dose` (SELECTED clinical rapid preset's exponential action
     *  model, default NovoRapid, resolved into `customCurve` so it reconstructs exactly) + a
     *  `PUT /v1/doses` event built from the PERSISTED (grid-snapped, client_id-minted) entity (§3.1/§3.2). */
    suspend fun logBolus(units: Double, preset: BolusPreset) {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val rapid = resolveRapid()
        val curve = curveEngine.expAction(units, rapid.peakMin, rapid.diaMin)
        val dose = repository.logLoggedDose(
            LoggedDoseEntity(
                clientId = "", tsMs = now, kind = DoseKind.BOLUS, units = units, durationMin = rapid.diaMin,
                k = null, theta = null, kaPerHour = null, kePerHour = null,
                customCurve = if (curve.isEmpty()) null else curve.toList().toBlob(),
                tzOffsetMin = tz, note = rapid.label, updatedAt = now,
            ),
        )
        outboxEnqueuer.enqueueDose(dose.toDoseEventDto(), now)
    }

    /** Log a discrete long-acting basal injection: `logged_dose` (Bateman; SELECTED clinical basal
     *  preset's DIA + ka/ke, default Lantus, so it reconstructs analytically) + a `PUT /v1/doses` event
     *  built from the PERSISTED (grid-snapped, client_id-minted) entity (§3.1/§3.2). */
    suspend fun logBasal(units: Double, preset: BasalPreset) {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val basal = resolveBasal()
        val dose = repository.logLoggedDose(
            LoggedDoseEntity(
                clientId = "", tsMs = now, kind = DoseKind.BASAL, units = units, durationMin = basal.diaMin,
                k = null, theta = null, kaPerHour = basal.kaPerHour, kePerHour = basal.kePerHour,
                tzOffsetMin = tz, note = basal.label, updatedAt = now,
            ),
        )
        outboxEnqueuer.enqueueDose(dose.toDoseEventDto(), now)
    }

    /** Save a mood into its 5-min `sample` bucket. `recordMood` folds it into the wide sample and
     *  enqueues the INGEST push, so mood rides the six-scalar `POST /v1/ingest` — no separate curve push. */
    suspend fun saveMood(mood: Int) {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val gridTs = snapToGrid(now)
        repository.recordMood(gridTs, tz, mood, now)
    }

    /** Save a free-text note: local `note` table + `POST /v1/notes` (`NOTE` outbox). */
    suspend fun saveNote(text: String) {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        repository.logNote(NoteEntity(tsMs = now, tzOffsetMin = tz, text = text, updatedAt = now))
        outboxEnqueuer.enqueueNote(
            NoteWriteDto(
                client_id = java.util.UUID.randomUUID().toString(),
                ts = now, tz_offset = tz, text = text, updated_at = now,
            ),
            now,
        )
    }

    private fun tzOffsetMin(nowMs: Long): Int =
        java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(nowMs)).totalSeconds / 60

    private fun snapToGrid(ts: Long): Long = Math.floorDiv(ts + 150_000L, 300_000L) * 300_000L

    // ─── Dashboard read models (DB-backed so they survive process death) ──────────────────────

    val activeSource: Flow<CgmSourceDescriptor?> = repository.observeActiveSource()

    val allSources: Flow<List<CgmSourceDescriptor>> = repository.observeSources()

    /** Every reading of the active source (widest window; Phase-1 volumes are tiny). Server-synced
     *  history is gap-filled into `cgm_reading` by the catch-up merge (T1dmRepository.mergeServerSample),
     *  so it flows through here to the graph — and through recentBgSeries to the model — automatically. */
    val dashboardReadings: Flow<List<CgmReading>> = activeSource.flatMapLatest { d ->
        if (d == null) flowOf(emptyList()) else repository.observeReadings(d.id, 0L, Long.MAX_VALUE)
    }

    val latestReading: Flow<CgmReading?> = activeSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeLatestReading(d.id)
    }

    /**
     * IOB/COB (§3.6-F) recomputed off-main on ANY trigger that can change it: a reading emit AND a
     * dose/meal write. Every log path (`logCarb`/`logBolus`/`logBasal`, `MealsController.logMeal`,
     * `InsulinController.logDose`) folds its amount into a `sample` row via `mergeSampleInTx`, so
     * `observeSamples` fires on every write — the fix for the fresh-log staleness (the old per-screen
     * `produceState(readings.size)` only refreshed on a reading emit ⇒ 0 U/0 g until the next reading).
     * `mapLatest` cancels an in-flight compute on a newer trigger; collected on [appScope] (default
     * dispatcher) and the store reads themselves hop to IO, so this never touches the main thread.
     */
    val iobCob: StateFlow<IobCobReadout?> =
        merge(
            dashboardReadings.map { },
            repository.observeSamples(0L, Long.MAX_VALUE).map { },
        )
            .onStart { emit(Unit) }
            .mapLatest { runCatching { iobCobNow() }.getOrNull() }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set once [com.t1dm.app.service.CgmScanService] is up, so the UI can reflect service state. */
    val serviceRunning = MutableStateFlow(false)

    // ─── BG-panel display settings + chrome (Phase 7A) ────────────────────────────────────────

    /** kv-backed Y-axis range + default window length (items 1 & 5); lives in `:data`. */
    val graphSettings: GraphSettingsStore by lazy { GraphSettingsStore(repository) }

    val graphRange: Flow<BgRange> get() = graphSettings.range
    val graphWindowHours: Flow<Int> get() = graphSettings.windowHours

    suspend fun setGraphWindowHours(hours: Int) = graphSettings.setWindowHours(hours)

    suspend fun setGraphRange(minMgdl: Int, maxMgdl: Int) = graphSettings.setRange(minMgdl, maxMgdl)

    /** A slow wall-clock tick so the reachability lights age even without a new emission (freshness
     *  is time-relative). 15 s is ample for a 5-min data cadence and negligible for battery. */
    private val reachabilityTicker: Flow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(15_000L) }
    }

    /**
     * The three BG-panel reachability lights (item 23): SERVER (sync transport), CGM (last MEASURED
     * age vs the loss-of-signal window), WATCH (link phase). Every state carries a plain-language
     * label the panel reveals on tap. Neutral-typed so `:feature:dashboard` never sees `:sync`/`:watch`.
     */
    val bgReachability: Flow<BgReachability> by lazy {
        combine(
            syncStatus,
            activeServerProfile,
            latestReading,
            watchSecurity,
            reachabilityTicker,
        ) { sync, profile, latest, watch, now ->
            BgReachability(
                server = serverLight(sync, profile),
                cgm = cgmLight(latest, now),
                watch = watchLight(watch.phase),
            )
        }
    }

    /** BLE signal strengths (item 20): CGM RSSI from the active source's last advert, and the watch
     *  RSSI now sourced from the `:watch` periodic `readRemoteRssi` poll (Phase 7C — fills the null the
     *  7A BG panel left). Null on either side ⇒ "no signal" in the WCH/CGM lights. */
    val bgSignals: Flow<BgSignals> by lazy {
        combine(latestReading, watchSecurity) { latest, watch ->
            BgSignals(cgmRssi = latest?.rssi, watchRssi = watch.rssiDbm)
        }
    }

    /** I12 — per-channel "last activity" tokens that advance the instant a channel MOVES, so the BG
     *  panel can flash that light. CGM: the newest reading's timestamp. SRV: a monotonic sum of
     *  streamed-in rows (wsCursor) + successful model pushes + server alerts — i.e. any send/receive.
     *  WCH: the last push instant. A token change fires a one-shot flash; the value itself is opaque. */
    val bgPulses: Flow<BgPulses> by lazy {
        combine(latestReading, syncStatus, watchSecurity) { latest, sync, watch ->
            val serverToken = (sync.wsCursor ?: 0L) + sync.alertCount +
                sync.modelPushes.values.sumOf { it.count }
            BgPulses(
                server = serverToken,
                cgm = latest?.tsMs ?: 0L,
                watch = watch.lastPushMs ?: 0L,
            )
        }
    }

    /** I11 — the user-entered CGM sensor-lifetime expiry instant (epoch-ms), or null when unset. */
    val sensorExpiryMs: Flow<Long?> get() = settingsStore.sensorExpiryMs

    /** Set/renew the sensor lifetime from a user-entered remaining duration; stores the absolute
     *  expiry so the countdown survives restarts. */
    suspend fun setSensorLifetime(days: Int, hours: Int, minutes: Int) {
        val durationMs = ((days.toLong() * 24 + hours) * 60 + minutes) * 60_000L
        settingsStore.setSensorExpiryMs(System.currentTimeMillis() + durationMs)
    }

    suspend fun clearSensorLifetime() = settingsStore.clearSensorExpiry()

    private fun serverLight(sync: SyncStatus, profile: ServerProfile?): ReachLight = when {
        profile == null -> ReachLight(LinkHealth.OFF, "no server profile configured")
        sync.lastDrain?.standDown == com.t1dm.sync.DrainResult.StandDown.AUTH ->
            ReachLight(LinkHealth.DEGRADED, "auth failed — check token")
        sync.wsState == WsConnState.CONNECTED -> ReachLight(LinkHealth.OK, "connected — streaming & draining")
        sync.wsState == WsConnState.RECONNECTING -> ReachLight(LinkHealth.DEGRADED, "reconnecting…")
        else -> ReachLight(LinkHealth.DOWN, "disconnected from ${profile.baseUrl}")
    }

    private fun cgmLight(latest: CgmReading?, nowMs: Long): ReachLight {
        if (latest == null) return ReachLight(LinkHealth.DOWN, "no CGM readings yet")
        val ageMin = (nowMs - latest.tsMs) / 60_000L
        val measured = latest.provenance == ReadingProvenance.MEASURED && latest.flag != ReadingFlag.WARMUP
        return when {
            ageMin <= 7 && measured -> ReachLight(LinkHealth.OK, "receiving — ${ageMin}m since last reading")
            ageMin <= alarmConfig.lossMin -> ReachLight(LinkHealth.DEGRADED, "aging/interpolated — ${ageMin}m old")
            else -> ReachLight(LinkHealth.DOWN, "signal lost — ${ageMin}m since last MEASURED")
        }
    }

    private fun watchLight(phase: WatchLinkPhase): ReachLight = when (phase) {
        WatchLinkPhase.UNPAIRED -> ReachLight(LinkHealth.OFF, "no watch paired")
        WatchLinkPhase.LIVE -> ReachLight(LinkHealth.OK, "paired — pushing every 5 min")
        WatchLinkPhase.SUSPENDED_LOW_POWER -> ReachLight(LinkHealth.DEGRADED, "low-power — push suspended")
        WatchLinkPhase.ERROR -> ReachLight(LinkHealth.DOWN, "link error — re-pair needed")
        else -> ReachLight(LinkHealth.DEGRADED, "connecting — ${phase.name.lowercase().replace('_', ' ')}")
    }

    // ─── Stats (Phase 6) ──────────────────────────────────────────────────────────────────────
    // The server cached block (O(1) fast path) ⊕ the local Rust `advancedStats` recompute over the
    // wide `sample` series. Settings (target range, unit space) are kv-backed in :data; the server
    // fetch is the :sync client; the two are unioned by the feature VM off the main thread.

    /** kv-backed target range + unit space, plus the off-main local recompute (Rust). */
    val statsRepository: StatsRepository by lazy { StatsRepository(repository, nativeCore, dispatchers) }

    private val statsSource by lazy { AppStatsSource(statsRepository, syncHttpClient, nativeCore, dispatchers) }

    /** The hoisted Stats state holder; app-lifetime so the window/composite survive Activity churn. */
    val statsViewModel: StatsViewModel by lazy { StatsViewModel(statsSource, appScope) }

    /** Map a locally-recomputed [AdvancedStats] block onto the flat wire block the server stores
     *  verbatim (§3.6). `mean_hr`/`bg_hr_corr` are not in the phone's [AdvancedStats] yet (§8.2) ⇒ 0. */
    private fun AdvancedStats.toPush(window: StatsWindow, nowMs: Long): StatsPushDto = StatsPushDto(
        window = window.wire,
        updated_at = nowMs,
        tir = tir, time_below = tbr, time_above = tar,
        mean_bg = meanBg, gmi = gmi, cv = cv, sd = sd,
        hypo_events = EventStatDto(hypoEpisodes.count, hypoEpisodes.totalDurationMs),
        hyper_events = EventStatDto(hyperEpisodes.count, hyperEpisodes.totalDurationMs),
        mean_daily_carbs = meanDailyCarbs, tdd = tdd, bolus_basal_ratio = bolusBasalRatio,
        n_samples = nSamples,
    )

    /**
     * §3.6 — compute and enqueue the 7/30/90-day stats blocks the server stores verbatim (it never
     * computes). The sole stats producer: driven from the 30-min slow loop and the H7 re-mirror; each
     * window is deduped ≤1/window/day inside [OutboxEnqueuer.enqueueStats]. Guarded per window.
     */
    private suspend fun pushStats(nowMs: Long) {
        for (w in StatsWindow.entries) {
            runCatching { outboxEnqueuer.enqueueStats(statsRepository.localStats(w).toPush(w, nowMs), nowMs) }
                .onFailure { Timber.w(it, "stats push failed for %s", w.wire) }
        }
    }

    // ─── Watch link (Phase 5) — a CLEAN REMOVABLE SEAM ────────────────────────────────────────
    // The optional ESP32-C3 accessory. Everything the :watch module needs is bound here from the
    // rest of the app; deleting this block + AppWatchWiring + the module excises the whole feature.
    // The crypto is the AUTHORITATIVE uniffi-backed WatchSession (t1dm-core: X25519 → HKDF-SHA256 →
    // per-direction AES-128-GCM, deterministic SAS, windowed+burned nonce; docs/WATCH_BLE.md). The
    // :watch module's loopback session is now a host-test double only.

    /** Battery-saver / low-power detector, shared by the watch push (which suspends in low power) and
     *  the dashboard's issue-1 low-power indicator ([lowPowerActive]). Reads its knobs fresh per call. */
    private val lowPower: AndroidLowPowerProvider by lazy {
        AndroidLowPowerProvider(
            context = appContext,
            enabled = { settingsStore.currentLowPowerEnabled() },
            thresholdPercent = { settingsStore.currentLowPowerPercent() },
            useOsSaver = { settingsStore.currentLowPowerUseOsSaver() },
        )
    }

    /** Issue 1 — whether battery-saver/low-power is engaged, polled off-main every 30 s (mirrors the
     *  dashboard's device-temperature poll). A read failure fails OPEN (not low-power). */
    val lowPowerActive: Flow<Boolean> = flow {
        while (true) {
            emit(withContext(dispatchers.io) { runCatching { lowPower.isLowPower() }.getOrDefault(false) })
            delay(30_000)
        }
    }

    val watchLink: WatchLink by lazy {
        WatchLink(
            centralProvider = { AndroidWatchCentral(appContext, dispatchers) },
            sessionFactory = UniffiWatchSessionFactory(),
            nonceStore = RoomNonceStore(repository),
            pairingStore = RoomWatchPairingStore(repository, appContext),
            glanceSource = AppWatchGlanceSource(
                repository = repository,
                inferenceState = inferenceState,
                thresholds = alarmConfig.thresholds,
                lossMin = alarmConfig.lossMin,
            ),
            lowPower = lowPower,
            dispatchers = dispatchers,
            config = WatchLinkConfig(enabled = true, autoConnect = true),
        )
    }

    /** The Security/Crypto panel's read model (session state, key fingerprint, nonce counter, SAS). */
    val watchSecurity: StateFlow<WatchSecurityState> get() = watchLink.state

    fun pairWatch() = watchLink.beginPairing()
    fun confirmWatchSas() = watchLink.confirmSas()
    fun rotateWatchKeys() = watchLink.rotate()
    fun unpairWatch() = watchLink.unpair()

    /** The FGS 5-min grid tick calls this to seal + push one glance (suspends in low-power mode). */
    suspend fun pushToWatch(nowMs: Long) = watchLink.pushNow(nowMs)

    companion object {
        /** F6 — hysteresis: once the thermal gate has tripped, inference resumes only after the die cools
         *  to `thresholdC - THERMAL_RESUME_MARGIN_C`, so a reading hovering at the threshold cannot flap
         *  the forecast on and off cycle-to-cycle. */
        const val THERMAL_RESUME_MARGIN_C = 2.0
    }
}
