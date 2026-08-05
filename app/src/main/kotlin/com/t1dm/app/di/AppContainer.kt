package com.t1dm.app.di

import android.content.Context
import android.net.NetworkCapabilities
import android.content.Intent
import android.media.RingtoneManager
import com.t1dm.alerts.ActiveAlarm
import com.t1dm.alerts.AlarmConfig
import com.t1dm.alerts.AlarmEngine
import com.t1dm.alerts.AlarmState
import com.t1dm.alerts.SnoozeState
import com.t1dm.alerts.AlertActuatorConfig
import com.t1dm.alerts.VibrationPreset
import androidx.glance.appwidget.updateAll
import com.t1dm.app.cgm.AppCgmRepository
import com.t1dm.app.hardware.HardwareProbe
import com.t1dm.app.inference.KvTelemetryStore
import com.t1dm.app.inference.RoomBgHistoryProvider
import com.t1dm.app.backup.BackupManager
import com.t1dm.app.settings.ConfigBackup
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
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.BandFitRefusal
import com.t1dm.core.model.ClarkeZoneGrid
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.IobCobReadout
import com.t1dm.core.model.LogState
import com.t1dm.core.model.LoggedEntry
import com.t1dm.core.model.PaintStroke
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
import com.t1dm.calc.CarbResolver
import com.t1dm.calc.SelectedModelHandle
import com.t1dm.calc.SelectedModelProvider
import com.t1dm.calc.SensitivityProbe
import com.t1dm.core.model.SensitivityEstimate
import com.t1dm.inference.backend.GraphInput
import com.t1dm.core.nativecore.UniffiNativeCore
import com.t1dm.app.stats.AppStatsSource
import com.t1dm.data.PushWithdrawal
import com.t1dm.data.T1dmRepository
import com.t1dm.data.backup.ArchiveCounts
import com.t1dm.data.backup.ArchiveResult
import com.t1dm.data.backup.NotAnArchiveException
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
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinType
import com.t1dm.core.model.SavedMeal
import com.t1dm.core.model.TempUnit
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.OutboxKind
import com.t1dm.sync.EventStatDto
import com.t1dm.sync.StatsPushDto
import com.t1dm.sync.doseDedupKey
import com.t1dm.sync.mealDedupKey
import com.t1dm.sync.toDoseEventDto
import com.t1dm.sync.toMealEventDto
import com.t1dm.inference.ContextChannelSource
import com.t1dm.inference.FutureOverrideSource
import com.t1dm.inference.InferenceController
import com.t1dm.inference.InferenceControllerDefaults
import com.t1dm.inference.buildInferenceController
import com.t1dm.sync.CatchUpCoordinator
import com.t1dm.sync.DrainConfig
import com.t1dm.sync.HistoryReMirror
import com.t1dm.sync.ModelSyncCoordinator
import com.t1dm.sync.NoActiveProfileException
import com.t1dm.sync.OkHttpSyncClient
import com.t1dm.sync.OutboxEnqueuer
import com.t1dm.sync.QueueDrainer
import com.t1dm.sync.ReMirrorLedger
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import java.util.concurrent.atomic.AtomicBoolean

/** kv key + bound for the WARMUP setting (inference-runtime.md). */
private const val KV_WARMUP_HOURS = "inference.warmup_hours"
private const val WARMUP_HOURS_MAX = 72

/** How much recent trace the Graph-settings smoothing miniature draws — long enough to contain a
 *  real excursion, short enough that widening the filter to 25 samples still fits inside it. */
private const val SMOOTHING_PREVIEW_HOURS = 3L

/** Per-model kv key for the forecast-backend switcher (issue 20 STEP 4): the BackendId enum name per
 *  model id, or absent = auto (the fp32 XNNPACK authority). */
private fun kvForecastBackend(modelId: String) = "inference.forecast_backend.$modelId"

/** How many logged meals/doses the Logs feed carries. Bounded at the QUERY, per table, because both
 *  stores are keep-forever: at a handful of entries a day this is months of scrolling, and the panel is
 *  a review surface rather than an export. The interleaved list is trimmed to the same bound, so the
 *  cut is by TIME rather than by whichever table happens to be busier. */
private const val LOG_FEED_LIMIT = 400

/** The clinical/published horizons the on-device accuracy aggregator reports (Phase 7C). The
 *  longest also fixes the WINDOW the suite scores: `SPEC/invariants.md` §6.2's level metrics are
 *  reported at each of these, and §6.3's CG-EGA over the whole span of the last. */
private val ACCURACY_HORIZONS_MIN = listOf(30, 60, 120)

/** The trailing window the realized-accuracy suite scores, and the same window a band
 *  recalibration fits over. One number: a correction fitted on a longer history than the figures
 *  beside it are scored on would be evidence about a different fortnight. */
private const val ACCURACY_WINDOW_DAYS = 14

/**
 * How many matured windows a band recalibration's CALIBRATION split must carry before the fit is
 * allowed to produce a correction (`SPEC/inference.md` §8.4). 144 is half a day of five-minute
 * cycles, and it is far above the point the arithmetic degenerates — `NativeCore
 * .conformalMinCalWindows()` derives that floor (19 for the seven levels of §6) and the core
 * raises anything below it.
 *
 * It is deliberately unrelated to `MetricsConfig.minSamples`, the display gate the drill-down's
 * tables use. That one asks whether an RMSE is worth printing; this one asks whether 24 × 6 = 144
 * one-sided order statistics can each be resolved from their own residuals. At six windows every
 * extreme level's offset would be the minimum or the maximum of a six-element sample — a
 * correction made entirely of the two worst things that happened.
 */
private const val CONFORMAL_MIN_CAL_WINDOWS = 144

/** mg/dL slack that forgives a near-boundary FALSE ALARM in the excursion precision — CGM noise at
 *  a threshold should not deflate it; recall stays strict. Matches `T1DMAI`'s
 *  `EXCURSION_PRECISION_TOLERANCE_MGDL`, without which the phone's precision figure and that
 *  project's validation table would not be the same statistic. `SPEC/invariants.md` §6.1 leaves the
 *  hypo/hyper THRESHOLD to the consumer (here the patient's own alarm bands) but says nothing of
 *  this tolerance, so the two projects hold separate copies of it. */
private const val EXCURSION_PRECISION_TOLERANCE_MGDL = 10.0

// §3.8 (H7) — every kv key this handshake keeps its state under lives in `ReMirrorKeys`; the walk here
// reaches them only through [ReMirrorLedger], and the epoch itself is written by the coordinator.

/** H7 re-mirror: local `sample` rows enqueued per resumable scalar page. Each page is drained and
 *  proved delivered before its cursor is banked, so this is a work-per-round-trip choice rather than a
 *  safety bound — the queue is back at its live depth before the next page is raised. */
private const val REMIRROR_SCALAR_PAGE = 500

/** H7 re-mirror: drain passes spent getting one page (or the event/stats phase) out of the queue
 *  before the pass gives up and resumes on the next connect. `DrainConfig.batchLimit` rows go per
 *  pass, so this covers a page several times over and still leaves the queue's own retry backoff to
 *  handle a server that is merely slow. */
private const val REMIRROR_MAX_DRAIN_PASSES = 12

/** H7 re-mirror: scalar pages banked per connect. It bounds one pass's work, nothing more — the
 *  persisted cursor makes the next connect resume rather than restart, so a history of any size
 *  converges over as many connects as it takes. */
private const val REMIRROR_MAX_PAGES_PER_PASS = 20

/**
 * The manual composition root ("DI/wiring: manual is fine"). Built once in
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

    /** The automatic-backup destination, run and retention sweep (`com.t1dm.app.backup`). */
    val backupManager: BackupManager by lazy {
        BackupManager(appContext, repository, settingsStore, dispatchers, BuildConfig.VERSION_NAME)
    }

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
     * glance surfaces, reachability lights). The already-running deterministic [AlarmEngine] now ALSO
     * adopts the change live: [refreshAlarmConfig] pushes the new config through [liveAlarmConfigSink]
     * into the running engine (see [CgmScanService]) — a threshold/timing/cadence edit applies to a
     * currently-firing alarm immediately, without a service restart.
     */
    @Volatile
    var alarmConfig: AlarmConfig = AlarmConfig.DEFAULT
        private set

    /**
     * The same value as [alarmConfig], as a flow, for Compose readers.
     *
     * A `@Volatile` read is invisible to the snapshot system: a composition that read the thresholds
     * did not invalidate when a Settings edit replaced them, so the app-wide glycemic badge went on
     * judging against the superseded bounds until something else happened to recompose it.
     */
    private val _alarmConfigFlow = MutableStateFlow(AlarmConfig.DEFAULT)
    val alarmConfigFlow: StateFlow<AlarmConfig> = _alarmConfigFlow.asStateFlow()

    /**
     * False until [refreshAlarmConfig] has run at least once, i.e. while [alarmConfig] still holds the
     * coded defaults rather than the user's persisted thresholds.
     *
     * Transient readers may ignore this. A reader that PERSISTS what it reads must not: the widget
     * writes the config into its Glance state as the tile's authoritative alarm geometry, so a render
     * that wins the race against startup hydration would bake the defaults in.
     */
    @Volatile
    var alarmConfigHydrated: Boolean = false
        private set

    /**
     * The running [AlarmEngine]'s live-config seam. The FGS registers a sink on start ([setAlarmConfigSink])
     * that pushes a new [AlarmConfig] into the already-running engine on the engine's own single-thread
     * dispatcher; [refreshAlarmConfig] invokes it after every persist. Null while the FGS is down — the
     * next start reads [alarmConfig] fresh. Presentation/threshold params only; it never re-arms or clears
     * an active breach/latch (§3.6-A — the engine re-classifies on the next reading).
     */
    @Volatile
    private var liveAlarmConfigSink: ((AlarmConfig) -> Unit)? = null

    fun setAlarmConfigSink(sink: ((AlarmConfig) -> Unit)?) {
        liveAlarmConfigSink = sink
    }

    /** Reload [alarmConfig] from the persisted knobs (called at startup + after a Settings save) and push
     *  it into the live engine if the FGS is up. */
    suspend fun refreshAlarmConfig() {
        alarmConfig = runCatching { settingsStore.currentAlarmConfig() }.getOrDefault(AlarmConfig.DEFAULT)
        alarmConfigHydrated = true
        _alarmConfigFlow.value = alarmConfig
        liveAlarmConfigSink?.invoke(alarmConfig)
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

    // ─── Snooze / dismiss (the TIME-BOUNDED presentation-layer alarm silence — §3.6 C1–C5). Mirrors
    // deathModeSnapshot: the FGS notifier reads this synchronously at every emit/reAlert to decide
    // whether to ANNOUNCE a still-active breach. It NEVER touches the engine (the pure AlarmEngine keeps
    // firing). Process-scoped (deliberately NOT persisted): a restart safely forgets snoozes and the
    // engine re-fires, and startPipeline clears it on a fresh service instance. Distinct from DEATH —
    // DEATH is the permanent fail-OPEN override; this is a bounded, per-episode silence. ───────────────
    @Volatile
    var snoozeSnapshot: SnoozeState = SnoozeState.NONE
        private set

    /** Snooze (timed, until [untilMs]) or dismiss (until the breach clears) the given live alarm. */
    @Synchronized
    fun snoozeAlarm(alarm: ActiveAlarm, untilMs: Long, dismiss: Boolean) {
        snoozeSnapshot = if (dismiss) snoozeSnapshot.dismiss(alarm) else snoozeSnapshot.snooze(alarm, untilMs)
    }

    /** Prune snooze/dismiss entries whose kind has cleared, and expired timed snoozes (§3.6 C1/C3).
     *  Called by the FGS on every engine-state change. */
    @Synchronized
    fun pruneSnooze(state: AlarmState) {
        val pruned = snoozeSnapshot.pruned(state, System.currentTimeMillis())
        if (pruned !== snoozeSnapshot) snoozeSnapshot = pruned
    }

    /** Forget all snoozes (a fresh FGS instance — no stale silence may outlive the alarm it covered). */
    @Synchronized
    fun clearSnooze() {
        snoozeSnapshot = SnoozeState.NONE
    }

    /** The snooze window (whole minutes), kept current by a collector for the notification action label. */
    @Volatile
    var snoozeMinSnapshot: Int = SettingsStore.DEFAULT_SNOOZE_MIN
        private set

    val snoozeMin: Flow<Int> get() = settingsStore.snoozeMin
    suspend fun currentSnoozeMin(): Int = settingsStore.currentSnoozeMin()
    suspend fun setSnoozeMin(min: Int) = settingsStore.setSnoozeMin(min)

    /** GMI (estimated HbA1c, %) over the 30-day window, recomputed on a slow cadence (it moves slowly
     *  and a 30-day recompute is too heavy for the widget's 30 s refresh). Null until first computed or
     *  when there is too little data. Read synchronously by the glucose widget. */
    @Volatile
    var gmiSnapshot: Double? = null
        private set

    /** Today's cumulative step count (local midnight → now), summed from the per-grid-bucket sample
     *  steps. Read directly by the widget and the BG panel — so it runs on every widget push, which is
     *  why the sum is SQL's and not Kotlin's: the day's ≤ 288 buckets were being materialised as whole
     *  entities, every column of them, to add up one nullable Int. */
    suspend fun stepsToday(): Int {
        val zone = java.time.ZoneId.systemDefault()
        val midnight = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return repository.stepsInRange(midnight, System.currentTimeMillis())
    }

    // ─── Forecast-cadence snapshot (F2) — mirrors deathModeSnapshot: the FGS's single-consumer forecast
    // driver reads the ADAPTIVE-vs-TIMED mode synchronously off this @Volatile, kept current by a
    // collector on the persisted flag, without re-suspending into SettingsStore on every reading tick. ──
    @Volatile
    var forecastModeSnapshot: String = SettingsStore.FORECAST_MODE_ADAPTIVE
        private set

    /** The TIMED-mode forecast period (whole minutes), read fresh per timed tick by the FGS driver. */
    suspend fun forecastPeriodMin(): Int = settingsStore.currentForecastPeriodMin()

    // ─── Aggressive-scan snapshots (mirror forecastModeSnapshot) — the FGS's screen-off receiver reads
    // these synchronously off @Volatiles to decide whether to raise the keep-screen-on AOD surface,
    // kept current by collectors on the persisted flags. The activity reads `aggressiveShowGlucose`
    // (live flow) for its content and this snapshot for its window brightness at onCreate. ──────────
    @Volatile
    var aggressiveScanSnapshot: Boolean = false
        private set

    @Volatile
    var aggressiveOnlyChargingSnapshot: Boolean = false
        private set

    @Volatile
    var aggressiveShowGlucoseSnapshot: Boolean = true
        private set

    val aggressiveScanEnabled: Flow<Boolean> get() = settingsStore.aggressiveScanEnabled
    val aggressiveShowGlucose: Flow<Boolean> get() = settingsStore.aggressiveShowGlucose
    val aggressiveOnlyCharging: Flow<Boolean> get() = settingsStore.aggressiveOnlyCharging
    suspend fun setAggressiveScanEnabled(on: Boolean) = settingsStore.setAggressiveScanEnabled(on)
    suspend fun setAggressiveShowGlucose(on: Boolean) = settingsStore.setAggressiveShowGlucose(on)
    suspend fun setAggressiveOnlyCharging(on: Boolean) = settingsStore.setAggressiveOnlyCharging(on)

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

    /**
     * The actuator knobs as a synchronous snapshot, for the same reason [themeIdSnapshot] exists: the
     * deterministic notifier and the predictive presenter both run outside Compose and cannot suspend
     * to read [alertActuatorConfig]. Both hold it as a live provider, so a save reaches an already-
     * running foreground service — the channels re-mint on the version change.
     */
    @Volatile
    var alertActuatorSnapshot: AlertActuatorConfig = AlertActuatorConfig.SILENT
        private set

    /** Re-read the actuator knobs into [alertActuatorSnapshot] (startup, and after any alerts save). */
    suspend fun refreshAlertActuatorConfig() {
        alertActuatorSnapshot =
            runCatching { alertActuatorConfig() }.getOrDefault(AlertActuatorConfig.SILENT)
    }

    /** Persist an alert-presentation edit and re-hydrate [alertActuatorSnapshot]. The five knobs went
     *  straight to the store before, which is why an edit did nothing until the service restarted. */
    suspend fun saveWarningVibration(preset: VibrationPreset) {
        settingsStore.setWarningVibration(preset)
        refreshAlertActuatorConfig()
    }

    suspend fun saveCriticalVibration(preset: VibrationPreset) {
        settingsStore.setCriticalVibration(preset)
        refreshAlertActuatorConfig()
    }

    suspend fun saveWarningSoundOn(on: Boolean) {
        settingsStore.setWarningSoundOn(on)
        refreshAlertActuatorConfig()
    }

    suspend fun saveCriticalSoundOn(on: Boolean) {
        settingsStore.setCriticalSoundOn(on)
        refreshAlertActuatorConfig()
    }

    suspend fun saveBypassDnd(on: Boolean) {
        settingsStore.setBypassDnd(on)
        refreshAlertActuatorConfig()
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

    /** Persist the weak-signal (low-RSSI) alarm knobs and re-hydrate [alarmConfig] so the running
     *  engine adopts them live. A signal-QUALITY alert distinct from loss-of-signal (§3.6-A). */
    suspend fun saveWeakSignal(enabled: Boolean, dbm: Int, sustainMin: Int) {
        settingsStore.setWeakSignal(enabled, dbm, sustainMin)
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

    /**
     * Import a backup (from a SAF read); re-hydrates [alarmConfig]. Accepts the wrapped shape AND the
     * legacy flat settings-only file, so every backup already on disk still restores. Throws with a
     * plain-language message on a malformed/foreign file. Off-main.
     */
    suspend fun importConfigJson(text: String): ImportResult = withContext(dispatchers.io) {
        val parsed = ConfigBackup.parse(text)
        // Null ONLY for a file that identifies itself as a drawings-only backup; a foreign file arrives
        // here as its own text and is refused by `importJson`'s root format tag, so skipping the
        // importer can never dress a wholly-ignored file up as a zero-key success.
        // The drawings are applied whatever the settings do. They used to share the settings
        // importer's fate: any failure — an empty allowlist above all — threw straight past the loop
        // below, so a file carrying both restored NEITHER, under a message about the settings alone.
        var settingsError: String? = null
        val keys = if (parsed.configJson != null) {
            runCatching {
                settingsStore.importJson(parsed.configJson).also {
                    refreshAlarmConfig()
                    refreshAlertActuatorConfig()
                }
            }.getOrElse {
                settingsError = it.message ?: "Settings could not be restored"
                0
            }
        } else {
            0
        }
        var added = 0
        if (parsed.paintings.isNotEmpty()) {
            // De-duplicate on the authoring instant, so re-importing the same file does not stack a
            // second copy of every drawing on top of the first. A stroke takes far longer than a
            // millisecond to draw, so a genuine collision between two distinct strokes cannot arise.
            val seen = repository.observePaintStrokes(0L, Long.MAX_VALUE).first()
                .mapTo(HashSet()) { it.createdAtMs }
            for (s in parsed.paintings) {
                if (seen.add(s.createdAtMs)) {
                    repository.addPaintStroke(s)
                    added++
                }
            }
        }
        ImportResult(keys, added, parsed.skippedPaintings, settingsError)
    }

    /** What a restore actually did: settings keys applied, drawings added, drawings that would not
     *  decode, and — when the settings half failed while the drawings still landed — why. */
    class ImportResult(
        val keys: Int,
        val paintingsAdded: Int,
        val paintingsSkipped: Int,
        val settingsError: String? = null,
    )

    // ─── Full-record archive (the Backup panel) ────────────────────────────────────────────────

    /** What restoring a `t1dm.archive` did — the row tallies from the archive itself, plus the
     *  settings half, which is applied here because only the composition root can re-hydrate the
     *  alarm and actuator policies afterwards. */
    class RestoreResult(
        val archive: ArchiveResult,
        val settingsKeys: Int,
        val settingsError: String?,
    )

    /**
     * Restore from an archive, falling back to the legacy settings-and-drawings reader when the file
     * turns out not to be one.
     *
     * [open] is a FACTORY rather than a stream because that fallback needs to read the same file
     * from the beginning a second time, and the archive attempt has already consumed its header. A
     * content URI can be reopened; a half-consumed stream cannot be rewound.
     */
    suspend fun restoreArchive(open: suspend () -> java.io.InputStream): RestoreResult =
        withContext(dispatchers.io) {
            val result = try {
                open().use { repository.readArchive(it) }
            } catch (e: NotAnArchiveException) {
                // Not an archive at all. Read it as the older settings-and-drawings document, whose
                // own format tag decides whether it is ours — so a foreign JSON the user mis-picked
                // is still refused there rather than reported as an empty success.
                val bytes = open().use { it.readNBytes(MAX_LEGACY_BACKUP_BYTES + 1) }
                if (bytes.size > MAX_LEGACY_BACKUP_BYTES) {
                    throw IllegalArgumentException("file is too large to be a backup")
                }
                val legacy = importConfigJson(bytes.decodeToString())
                return@withContext RestoreResult(
                    archive = ArchiveResult(
                        configJson = null,
                        applied = ArchiveCounts(strokes = legacy.paintingsAdded),
                        duplicates = 0,
                        skipped = legacy.paintingsSkipped,
                        truncated = false,
                        schemaVersion = null,
                        createdAtMs = null,
                    ),
                    settingsKeys = legacy.keys,
                    settingsError = legacy.settingsError,
                )
            }

            // The archive's rows are already in. Apply its settings document separately so a
            // configuration that will not import cannot cost the user the history that already did
            // — the same split the settings-and-drawings importer arrived at.
            var settingsError: String? = null
            val configJson = result.configJson
            val keys = if (configJson != null) {
                runCatching {
                    settingsStore.importJson(configJson).also {
                        refreshAlarmConfig()
                        refreshAlertActuatorConfig()
                    }
                }.getOrElse {
                    settingsError = it.message ?: "Settings could not be restored"
                    0
                }
            } else {
                0
            }
            RestoreResult(result, keys, settingsError)
        }

    /** Ceiling on a LEGACY (uncompressed, settings-and-drawings) restore read wholly into memory.
     *  The archive path is streamed and needs no such bound — which is most of why it exists. */
    private val MAX_LEGACY_BACKUP_BYTES = 32 * 1024 * 1024

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
            // BG input filter (INFERENCE.md §7.1): the SAME window the calculator's roll and the
            // dashboard's smoothed overlay read, so one setting governs one signal everywhere.
            smoothingWindowProvider = { smoothingWindow() },
            // Running-set cap: how many discovered models run (and push a prediction) each cycle,
            // read fresh each discovery so a Settings edit takes on the next refresh (mirrors warmup).
            maxRunningProvider = { maxRunningModels() },
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

    /** Set while a debounced curve-write cycle is already scheduled; see [reforecastAfterCurveWrite]. */
    private val curveReforecastScheduled = AtomicBoolean(false)

    /**
     * Re-run inference because a write moved a channel the model conditions on — a logged meal or
     * dose, or the withdrawal of one. Without this the curve answered nothing until the next cadence
     * tick: the user logged 60 g, watched the forecast sit flat for up to five minutes, and had no way
     * to tell a slow response from an ignored one.
     *
     * **Debounced, leading-edge, coalescing.** The first write schedules the cycle
     * `inference.log_reforecast_debounce_s` ahead; every write inside that window sees the guard
     * already set and folds into the run rather than queueing its own or pushing the deadline back —
     * so a meal and the bolus that follows it cost ONE forward, and a burst of writes cannot postpone
     * the response indefinitely the way a trailing-edge debounce would. The guard is released just
     * BEFORE the forward, not after: a write landing while the cycle is in flight may well have missed
     * the snapshot it read, and must earn a cycle of its own.
     *
     * **It bypasses nothing.** This is the same [InferenceController.runFromHistory] the cadence
     * driver calls, so the thermal gate, the warmup latch, the freshness/staleness marking, the
     * degeneracy classification and every §3.6 eligibility rule apply exactly as they do to a tick.
     * The only thing that differs is [InferenceCause.LOG_WRITE], which is a label.
     *
     * The write itself does NOT wait on this: the caller has already committed its row, and the
     * forecast reads Room rather than the queue, so nothing here can delay or fail a log.
     */
    fun reforecastAfterCurveWrite() {
        if (!curveReforecastScheduled.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val debounceMs = runCatching { settingsStore.currentLogReforecastDebounceS() }
                    .getOrDefault(SettingsStore.DEFAULT_LOG_REFORECAST_DEBOUNCE_S)
                    .toLong() * 1_000L
                if (debounceMs > 0L) delay(debounceMs)
            } finally {
                curveReforecastScheduled.set(false)
            }
            runCatching { inferenceController.runFromHistory(InferenceCause.LOG_WRITE, System.currentTimeMillis()) }
                .onFailure { Timber.tag("InferenceController").w(it, "log-driven cycle failed (alarm path unaffected)") }
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
     * The Clarke zone lattice the drill-down's error grid paints its five regions from, classified
     * by the core. Data-independent — it is a picture of the zone algebra, not of this patient — so
     * one instance serves every model and it is built once, on first open of the drill-down.
     *
     * It exists so no Kotlin has to know a zone boundary: the inequalities live in
     * `t1dm-core::accuracy::clarke_zones` alone, and the figure paints cells it was handed rather
     * than an outline it derived. Empty on a stub core, which the figure renders as no regions.
     *
     * **Suspending, and off-main, like every other native reduction this screen makes.** The build
     * is a 160-square lattice: 25 600 classifications inside the core, the same number lifted across
     * uniffi and re-mapped on this side, then three verification probes. Read as a plain property it
     * ran inside the composition that opened the drill-down — the one expensive thing on that screen
     * that was not moved off the frame — and the transition stuttered once per process. The `lazy`
     * still does the once-only work; this only decides which thread pays for it.
     */
    private val clarkeZoneGridOnce: ClarkeZoneGrid by lazy { ClarkeZoneGrid.build(nativeCore::clarkeZoneGrid) }

    suspend fun clarkeZoneGrid(): ClarkeZoneGrid = withContext(dispatchers.default) { clarkeZoneGridOnce }

    /**
     * On-device realized forecast accuracy for [modelId] over the trailing [days] (Phase 7C — Models
     * drill-down): walks every matured `prediction` row into a whole-window record against the
     * realized MEASURED BG and scores it in the golden-gated Rust core — per horizon on the band
     * projection of `SPEC/invariants.md` §6.2, with the median line nested beneath. A horizon with
     * fewer than [minSamples] scored windows is flagged insufficient. Off-main.
     *
     * CG-EGA is NOT computed here; it is the costly whole-window pass and the drill-down asks for it
     * separately, through [modelCgEga].
     */
    suspend fun modelMetrics(
        modelId: String,
        days: Int = ACCURACY_WINDOW_DAYS,
        minSamples: Int = 6,
    ): ModelMetrics = modelMetrics(modelId, days, minSamples, includeCgEga = false)

    /**
     * The whole-window CG-EGA (§6.3) for [modelId] over the same trailing [days] — a separate call
     * because it walks every step of every window through the P-EGA × R-EGA zone algebra, and the
     * drill-down renders it only when asked. Null when nothing scoreable was found. Off-main.
     */
    suspend fun modelCgEga(modelId: String, days: Int = ACCURACY_WINDOW_DAYS, minSamples: Int = 6): CgEga? =
        modelMetrics(modelId, days, minSamples, includeCgEga = true).suite.cgega

    private suspend fun modelMetrics(
        modelId: String,
        days: Int,
        minSamples: Int,
        includeCgEga: Boolean,
    ): ModelMetrics {
        val now = System.currentTimeMillis()
        val since = now - days.toLong() * 86_400_000L
        val horizonMax = ACCURACY_HORIZONS_MIN.max()
        val set = repository.forecastWindows(modelId, horizonMax, since, now)
        // §6.1 fixes which band EDGE the excursion detectors read but leaves what it is compared
        // against to the consumer: on the phone that is the patient's own alarm bands, never a
        // clinical pair transcribed from the validation table.
        val config = MetricsConfig(
            hypoThresholdMgdl = settingsStore.alarmLow.first().toDouble(),
            hyperThresholdMgdl = settingsStore.alarmHigh.first().toDouble(),
            excursionPrecisionToleranceMgdl = EXCURSION_PRECISION_TOLERANCE_MGDL,
            minSamples = minSamples,
        )
        // The reduction is pure CPU over the whole 14-day window — never on the caller's thread.
        val suite = withContext(dispatchers.default) {
            nativeCore.forecastMetricsSuite(set.windows, ACCURACY_HORIZONS_MIN, config, includeCgEga)
        }
        return ModelMetrics(suite, set.nMatured, set.nIncomplete, minSamples)
    }

    // ── Band recalibration (`SPEC/inference.md` §8.4), fitted on device ──────────────────────────
    //
    // The scope of this feature, stated once so no later reader has to reconstruct it:
    //
    //   * the MEDIAN never moves — §8.4 pins it and the core rejects a delta that does not, which
    //     is what keeps the dose calculator's score identical before and after a fit;
    //   * every classifier reads the RAW fan — the alarm engine, the rolling forecaster's rails,
    //     the excursion detectors and the accuracy suite all read `ModelPrediction.bandsMgdl` as
    //     stored, and this correction never touches it;
    //   * the WIRE carries the raw fan — `SPEC/http-api.md`'s Prediction has no calibrated/raw
    //     discriminator and, because the median is pinned, a calibrated fan would satisfy its
    //     "row index 3 equals `line`" and travel indistinguishably. Nothing calibrated is written
    //     to `prediction` or pushed. Marking the distinction on the wire would be a contract
    //     change across three repositories.
    //
    // So the correction reaches exactly one surface: the BG panel's forecast overlay, through
    // [calibratedBands].

    /** One fit at a time, process-wide. The panel disables its own button while a fit runs; this is
     *  the guard that holds when it cannot — a second entry is refused, never queued. */
    private val bandCalibrationRunning = AtomicBoolean(false)

    /**
     * Every model's stored band correction, keyed by model id — the map the BG panel reads.
     *
     * Observed from Room rather than fetched, so a fit lands on the graph without the panel being
     * reopened, and so the correction survives process death by construction: there is no in-memory
     * authority to rebuild, only a table to re-observe.
     */
    val bandCalibrations: StateFlow<Map<String, BandCalibration>> =
        repository.observeBandCalibrations()
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The §8.4 apply, for the one display surface entitled to it: add the model's stored delta to a
     * raw fan, hold the median exactly, keep the fan monotone. Returns null — meaning "draw the raw
     * fan" — when there is no correction for [modelId], when it has aged past
     * [BandCalibration.expiresAtMs], when its shape disagrees with the fan's, or when the core
     * rejects the pair.
     *
     * Synchronous and allocation-light on purpose: the caller is `predOverlayOf`, already off the
     * composing frame, and this is one pass over 168 doubles across the FFI. The map is passed in
     * rather than read from [bandCalibrations] — so the overlay's `produceState` can key on the map
     * and rebuild exactly when the correction changes. The one thing it does not take as an argument
     * is the clock: an expiry evaluated here takes effect on the next rebuild, which is at worst one
     * five-minute cycle after the correction lapses.
     */
    fun calibratedBands(
        calibrations: Map<String, BandCalibration>,
        modelId: String,
        bandsMgdl: List<Double>,
        horizonSteps: Int,
        nQuantiles: Int,
    ): List<Double>? {
        val cal = calibrations[modelId] ?: return null
        // A delta fitted at a different horizon or fan width is not this forecast's correction. The
        // core would reject the length mismatch anyway; refusing here says why without an FFI hop.
        if (cal.steps != horizonSteps || cal.nQuantiles != nQuantiles) return null
        // Nor is a delta whose evidence has gone stale. The row is kept rather than deleted — the
        // drill-down still has to be able to say what lapsed and when — but it stops being drawn.
        if (cal.expiredAt(System.currentTimeMillis())) return null
        return nativeCore.applyQuantileConformal(bandsMgdl, cal.delta)
    }

    /**
     * [modelId]'s OWN forecast horizon in minutes, or null when it cannot be established.
     *
     * This is the number a band correction must be fitted at, because it is the number
     * [calibratedBands] compares the stored `steps` against: `ModelPrediction.horizonSteps` is
     * `medianBg.size`, which the core sizes from the descriptor's `PREDICTION_HORIZON_HOURS`. Fitting
     * at the accuracy suite's longest horizon instead would tie every model's correction to 120 min
     * and leave anything else structurally inapplicable — stored, reported, and never once drawn.
     *
     * The descriptor is asked first because it is available from discovery onward; the live forecast
     * is the fallback for a model whose descriptor omits the constant, and it is the same quantity.
     */
    private fun modelHorizonMin(modelId: String): Int? {
        val state = inferenceState.value
        val fromDescriptor = state.metas.firstOrNull { it.modelId == modelId }?.predictionHorizonHours
        if (fromDescriptor != null && fromDescriptor > 0) return fromDescriptor * 60
        val p = state.predictions.firstOrNull { it.modelId == modelId } ?: return null
        if (p.horizonSteps <= 0 || p.stepMs <= 0L) return null
        return (p.horizonSteps.toLong() * p.stepMs / 60_000L).toInt()
    }

    /**
     * Fit a split-conformal band correction for [modelId] from its own matured forecasts, and
     * persist it if it is real (Models drill-down — "Recalibrate").
     *
     * Off-main throughout: the window walk is a Room read on IO and the fit is pure CPU on the
     * default pool, so nothing here touches the frame that composed the button.
     *
     * **Atomic in effect.** The correction is written once, at the end, and only when the fit was
     * sufficient. A cancelled or failed fit therefore leaves the previous correction exactly as it
     * was — there is no partial state to half-write. A REFUSAL is likewise non-destructive: it has
     * established that too little history matured to fit on, which is not evidence that what is
     * already stored is wrong.
     *
     * Fitted at the MODEL's own horizon, never the accuracy suite's — see [modelHorizonMin] — so the
     * correction's `steps` is by construction the length [calibratedBands] will require of it.
     *
     * Returns what happened, so the panel can say it rather than merely re-render. A refusal that
     * never reached the window walk carries a [BandFitRefusal] rather than a zeroed count, because
     * "no fit ran" and "nothing matured to fit on" are different facts about the patient's history
     * and only one of them is about the patient.
     */
    suspend fun fitBandCalibration(
        modelId: String,
        days: Int = ACCURACY_WINDOW_DAYS,
        minCalWindows: Int = CONFORMAL_MIN_CAL_WINDOWS,
    ): BandCalibrationOutcome {
        if (!bandCalibrationRunning.compareAndSet(false, true)) {
            return BandCalibrationOutcome(null, false, 0, 0, BandFitRefusal.BUSY)
        }
        try {
            val horizonMin = modelHorizonMin(modelId)
                ?: return BandCalibrationOutcome(null, false, 0, 0, BandFitRefusal.HORIZON_UNKNOWN)
            val now = System.currentTimeMillis()
            val since = now - days.toLong() * 86_400_000L
            val set = repository.forecastWindows(modelId, horizonMin, since, now)
            if (set.windows.isEmpty()) {
                return BandCalibrationOutcome(null, false, set.nMatured, set.nIncomplete)
            }
            // `forecastWindows` walks `PredictionDao.range`, which is newest-first. The conformal
            // split is CHRONOLOGICAL — older fitted on, newer held out and scored — so the order
            // is load-bearing here in a way it never is for the order-free metric suite.
            val chronological = set.windows.asReversed()
            val fit = withContext(dispatchers.default) {
                nativeCore.fitQuantileConformal(chronological, minCalWindows)
            }
            // `steps == 0` is the core's "nothing here was scoreable" — an empty or degenerate
            // window set, or a `CoreException` mapped to `ConformalFit.NONE`. It carries no counts
            // worth printing, so it reads as no result rather than as a refusal with n = 0.
            if (fit.steps == 0) {
                return BandCalibrationOutcome(null, false, set.nMatured, set.nIncomplete)
            }
            if (!fit.sufficient) {
                return BandCalibrationOutcome(fit, false, set.nMatured, set.nIncomplete)
            }
            repository.putBandCalibration(
                BandCalibration(
                    modelId = modelId,
                    delta = fit.delta,
                    steps = fit.steps,
                    nQuantiles = fit.nQuantiles,
                    nCal = fit.nCal,
                    nEval = fit.nEval,
                    maxAbsDeltaMgdl = fit.maxAbsDeltaMgdl,
                    cov90Raw = fit.cov90Raw,
                    cov90Cal = fit.cov90Cal,
                    meanWidth90Raw = fit.meanWidth90Raw,
                    meanWidth90Cal = fit.meanWidth90Cal,
                    windowDays = days,
                    fittedAtMs = now,
                ),
            )
            return BandCalibrationOutcome(fit, true, set.nMatured, set.nIncomplete)
        } finally {
            bandCalibrationRunning.set(false)
        }
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

    // ── Running-set cap (§2.3) — how many discovered models run each cycle; every
    // running model forecasts + pushes to the server, the SELECTED one draws the BG panel. kv-backed
    // via SettingsStore (mirrors the warmup knob); the controller re-reads it fresh each discovery. ──

    /** Settings read model: the current running-set cap, for the human-readable stepper row. */
    val maxModelsSetting: Flow<Int> get() = settingsStore.inferenceMaxModels

    /** Persist the running-set cap (clamped to the SettingsStore bounds). Off-main. */
    suspend fun setMaxModels(n: Int) = settingsStore.setInferenceMaxModels(n)

    /** One-shot read of the running-set cap for the controller's per-discovery [maxRunningProvider]. */
    suspend fun maxRunningModels(): Int = settingsStore.currentInferenceMaxModels()

    // ── BG input filter (INFERENCE.md §7.1) — the causal SavGol window the BG channel is filtered
    // at before normalization. ONE value feeds the forecast cycle, the calculator's rolls and the
    // dashboard's smoothed overlay; splitting them would draw a line the model never saw. ──

    /** Settings read model: the current window, for the detent row + the live miniature. */
    val savgolWindow: Flow<Int> get() = settingsStore.savgolWindow

    /** One-shot read for the controller's / forecaster's per-cycle provider. */
    suspend fun smoothingWindow(): Int = settingsStore.currentSavgolWindow()

    /** Persist the window (snapped to an offered detent). Off-main. */
    suspend fun setSmoothingWindow(window: Int) = settingsStore.setSavgolWindow(window)

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
        appScope.launch { settingsStore.snoozeMin.collect { snoozeMinSnapshot = it } }
        appScope.launch { settingsStore.forecastMode.collect { forecastModeSnapshot = it } }
        appScope.launch { settingsStore.aggressiveScanEnabled.collect { aggressiveScanSnapshot = it } }
        appScope.launch { settingsStore.aggressiveOnlyCharging.collect { aggressiveOnlyChargingSnapshot = it } }
        appScope.launch { settingsStore.aggressiveShowGlucose.collect { aggressiveShowGlucoseSnapshot = it } }
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
        // §3.8 (H7) re-mirror is NOT launched here. It is the `reMirror` hook on [catchUpCoordinator],
        // which already runs on every WS Connected/Reconnected and owns the epoch gate — including the
        // rule that the epoch is recorded only once the walk has been delivered. A second driver on the
        // `syncStatus` CONNECTED edge used to do the walk independently and record the epoch itself,
        // which is precisely how a partial upload came to be marked complete.
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
        // Share the SAME desync flag the StreamClient latches on a live-channel overflow, so a dropped
        // WS frame escalates the next catch-up to a full resync (both defaulted to their own instance,
        // so the overflow signal never reached the coordinator).
        //
        // `reMirror` is the §3.8 walk. Left at its default no-op the whole gate is inert — which it was:
        // a second, independent walk hung off the `syncStatus` CONNECTED edge did the real work and
        // banked the epoch itself, so the coordinator's own gate (the one that records only on delivery)
        // never fired. One implementation now, called from one place.
        //
        // `scope` is the process-lived appScope, NOT the foreground service scope that collects
        // `events()`: the walk drains the outbox and waits on it, and the collector is where the drain
        // it is waiting for gets kicked from.
        CatchUpCoordinator(
            stream = streamClient,
            http = syncHttpClient,
            repo = repository,
            scope = appScope,
            reMirror = HistoryReMirror { epoch -> reMirrorHistory(epoch) },
            desync = streamClient.desync,
        )
    }

    /** The §3.8 walk's persisted bookkeeping — the pending epoch, the store it targets, the walk stamp,
     *  and the resumable scalar cursor. Deliberately given the repository's kv + outbox reads as plain
     *  functions: every judgement it makes is then testable without Room. */
    private val reMirrorLedger: ReMirrorLedger by lazy {
        ReMirrorLedger(
            getKv = repository::getKv,
            putKv = repository::putKv,
            oldestQueuedAtMs = repository::oldestOutboxCreatedAt,
            maxQueueAgeMs = drainConfig.maxAgeMs,
        )
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
     * The store one §3.8 walk is being raised against, as a single opaque string, or null when there is
     * no usable target. It carries the active profile's id, its base URL, and the wall clock of its last
     * edit, because those are the three ways the destination can move under a walk in flight:
     * [SyncHttpClient] resolves the endpoint per REQUEST, so an outbox row queued for one server drains
     * to whatever server the profile names by the time the drainer reaches it. Folding the edit stamp in
     * means any profile save at all — a repointed host, a fresh token, a switch away and back —
     * invalidates the walk rather than letting it credit an epoch to a store that never received it.
     */
    private suspend fun activeStoreIdentity(): String? {
        if (serverProfileStore.activeEndpoint() == null) return null
        val p = repository.activeProfile() ?: return null
        return "${p.id}\u001f${p.baseUrl}\u001f${p.updatedAtMs}"
    }

    /**
     * §3.8 (H7) — re-mirror the phone's authoritative history to a freshly-wiped or brand-new server.
     * The clean-break cutover (§6) wipes the server, and the outbox holds only *pending* writes, not
     * history, so a changed `store_epoch` means the server retains nothing of what this phone authored.
     * Wired as the [HistoryReMirror] the [catchUpCoordinator] gate calls; the gate owns the epoch
     * comparison and the epoch WRITE, this owns the walk and the judgement of when it has landed, and
     * [reMirrorLedger] owns the state that survives between the two.
     *
     * **A pass need not finish, but everything it does finish is banked.** A month of five-minute
     * samples is some ten thousand rows and will not drain inside one connect. So the scalar history is
     * a resumable page walk over [T1dmRepository.reMirrorScalarsBatch]: each page is enqueued, DRAINED,
     * and only once the queue is provably empty of it is its `ts` written back as the cursor. Every
     * bail-out below is a `return false`, never a throw, so the pass keeps the ground it proved and the
     * next connect resumes from the cursor instead of starting again at the beginning of time — which is
     * what a full-history walk with no cursor does, forever, on a history this size.
     *
     * The meal/dose/stats phase is not paged: those are bounded by hand-logging rather than by the grid,
     * MEAL and DOSE are never age-evictable, and re-enqueuing is idempotent on the phone-minted
     * `client_id`. It is raised whole whenever the ledger says the walk is new, and then drained through
     * before the scalar walk begins so the queue is at its live depth when the first page lands.
     *
     * Off-main and fully guarded — never actuates, never blocks the alarm path. It does hold a coroutine
     * for as long as the drains it drives take, which is why the coordinator kicks it into the process
     * scope rather than awaiting it on the stream collector.
     */
    private suspend fun reMirrorHistory(serverEpoch: String): Boolean = withContext(dispatchers.io) {
        val identity = activeStoreIdentity() ?: return@withContext false
        val walk = reMirrorLedger.resume(serverEpoch, identity, System.currentTimeMillis())

        if (walk.raiseEvents) {
            Timber.i(
                "re-mirroring history to store_epoch %s (stamp %d, resuming scalars after ts %d)",
                serverEpoch, walk.stampMs, walk.scalarCursor,
            )
            // Meals + doses: irreplaceable clinical records (never age-evictable, top outbox priority).
            for (m in repository.loggedMealsInRange(0L, walk.stampMs).sortedBy { it.tsMs }) {
                outboxEnqueuer.enqueueMeal(m.toMealEventDto(), walk.stampMs)
            }
            for (d in repository.loggedDosesInRange(0L, walk.stampMs).sortedBy { it.tsMs }) {
                outboxEnqueuer.enqueueDose(d.toDoseEventDto(), walk.stampMs)
            }
            // Latest-per-window stats blocks (deduped ≤1/window/day; a no-op if the slow loop already pushed).
            pushStats(walk.stampMs)
        }
        // Prove the phase out of the queue before crediting it. A pass that bailed here — or died here —
        // banks nothing, so the next connect raises the whole phase again under a fresh stamp rather than
        // resuming atop a half-enqueued one. Unconditional, because a resumed pass must still see any
        // straggler land: a scalar page's proof is "nothing older than me remains".
        if (!drainThrough(walk.stampMs)) return@withContext false
        reMirrorLedger.bankEvents(walk.stampMs, System.currentTimeMillis())

        var cursor = walk.scalarCursor
        var pages = 0
        var scalarsComplete = false
        while (pages < REMIRROR_MAX_PAGES_PER_PASS) {
            val stamp = System.currentTimeMillis()
            // One INGEST dirty-marker per bucket; the drainer resolves the current `sample` row at drain
            // time and POSTs `/v1/ingest`. Null = no sample past the cursor, so the scalar walk is done.
            val next = repository.reMirrorScalarsBatch(cursor, REMIRROR_SCALAR_PAGE, stamp)
            if (next == null) { scalarsComplete = true; break }
            if (!drainThrough(stamp)) return@withContext false
            // Re-read the target before crediting the page. The endpoint is resolved per REQUEST, so a
            // profile repointed while this page drained sent it to a store the epoch does not name, and a
            // cursor banked over it would skip those rows for good — the one mistake in this walk that
            // never gets a second attempt. Bail without banking; the ledger restarts the walk on its own.
            if (activeStoreIdentity() != identity) return@withContext false
            cursor = next
            reMirrorLedger.bankScalarCursor(cursor, stamp)
            pages++
        }
        if (!scalarsComplete) {
            Timber.i("re-mirror banked %d scalar page(s) to ts %d; resumes on the next connect", pages, cursor)
            return@withContext false
        }
        // Re-read the store identity rather than trusting the one this pass opened with: the profile may
        // have been repointed while the walk was draining, in which case the history went somewhere this
        // epoch does not name and the ledger must refuse to promote it.
        val stillIdentity = activeStoreIdentity() ?: return@withContext false
        reMirrorLedger.delivered(serverEpoch, stillIdentity, System.currentTimeMillis())
    }

    /**
     * Drive the outbox until every row created at or before [throughMs] has left it, and say whether it
     * did. This is what makes a banked scalar cursor honest: a page is credited only once it is provably
     * gone, so an aborted pass loses nothing and a resumed pass never re-walks proved ground.
     *
     * It drains DIRECTLY rather than waiting for someone else to. The predecessor polled the outbox
     * depth while the only thing that could lower it was a `drainNow()` sitting downstream of the very
     * stream collector the pass was blocking — back-pressure against itself. [QueueDrainer] serialises
     * passes on its own mutex and releases it between them, so pumping it here is safe and leaves the
     * service's own drains room to interleave.
     *
     * Bounded three ways, each of them a plain `false`: a drain that stands down (no profile, or a token
     * the server refuses), a drain that moves nothing at all (everything due has failed and is in
     * backoff), and a hard ceiling on passes.
     */
    private suspend fun drainThrough(throughMs: Long): Boolean {
        var passes = 0
        while (!reMirrorLedger.drainedThrough(throughMs)) {
            if (passes >= REMIRROR_MAX_DRAIN_PASSES) {
                Timber.i("re-mirror: rows at or before %d still queued after %d drain pass(es)", throughMs, passes)
                return false
            }
            val result = runCatching { queueDrainer.drainOnce() }
                .onFailure { Timber.w(it, "re-mirror drain pass failed") }
                .getOrNull() ?: return false
            syncStatusStore.onDrain(result, repository.oldestOutboxCreatedAt())
            if (result.standDown != null) {
                Timber.i("re-mirror stood down: %s", result.standDown)
                return false
            }
            if (result.sent == 0 && result.dropped == 0) {
                Timber.i("re-mirror drain made no progress (retried %d, remaining %d)", result.retried, result.remaining)
                return false
            }
            passes++
        }
        return true
    }

    // ─── Full app reset (issue 5 — DESTRUCTIVE, IRREVERSIBLE) ──────────────────────────────────

    /**
     * Erase EVERYTHING and return the app to a first-run state IN-PLACE (issue 5) — deliberately WITHOUT
     * stopping the foreground service or killing the process, so the advertisement scan keeps RUNNING
     * across the reset (the user's requirement) and the sensor's next adverts immediately repopulate the
     * just-wiped `cgm_reading` table. Consequences of keeping the process alive: (a) the
     * `cgm_source` rows are PRESERVED so the active-source binding survives; (b) the in-memory,
     * process-scoped caches that Room-backed flows don't self-heal (the alarm config, snooze state, the
     * inference warmup latch, GMI, the ephemeral bolus/roll state, the published forecast) are returned
     * to first-run here. It (1) drops the in-memory watch session before the wipe; (2) row-wipes every
     * user/runtime table at the current schema version except `cgm_source`, keeping the shipped model
     * artifacts + seed dictionaries (see [T1dmRepository.wipeAllData]) — this also clears the watch
     * pairing/epoch/nonce-ceiling kv rows;
     * (3) burn the secrets that live OUTSIDE Room — the Keystore-wrapped server token(s) and the watch
     * key-wrapping alias (the watch key material itself was a kv blob, already gone in step 2); (4) reset
     * the process-scoped in-memory caches to first-run. The caller then relaunches the UI IN-PROCESS via
     * [restartApp] (a fresh Activity task, NOT a process kill) so the app-lifetime StateFlows re-emit the
     * empty store while the FGS + sensor connection live on. Off-main.
     */
    suspend fun resetAllData() = withContext(dispatchers.io) {
        // KEEP THE SCAN RUNNING across the reset. The BLE scan is process-scoped (the FGS in this same
        // process owns it), so — unlike the old stop-service + kill-process reset — we deliberately DO
        // NOT stop CgmScanService, do NOT stop the registry, and do NOT kill the process. The scan lives
        // on and the next adverts repopulate the just-wiped cgm_reading table (the user's explicit
        // requirement). We therefore also PRESERVE the cgm_source rows so the active-source binding
        // survives the wipe.
        //
        // Drop the in-memory watch session + disable the link BEFORE the wipe so no late 5-min push can
        // re-persist key material or a nonce ceiling into the kv rows we are about to clear (which would
        // resurrect the pairing the reset is erasing). Only touch it if the watch was ever wired up.
        runCatching { watchLink.stopForReset() }
        repository.wipeAllData(preserveCgmSources = true)
        runCatching { tokenStore.clearAll() }
        com.t1dm.app.watch.WatchKeyCipher.deleteKey()
        // Return the in-memory, process-scoped caches to first-run WITHOUT a process kill (which would
        // drop the scan). The Room-backed StateFlows self-heal from the wiped store; these are the
        // caches that would otherwise show stale data after the in-place relaunch.
        refreshAlarmConfig()          // thresholds → coded defaults, pushed live into the running engine
        runCatching { clearSnooze() }
        runCatching { clearBolusAdvice() }
        runCatching { clearRoll() }
        gmiSnapshot = null
        // The memoized stats blocks are derived patient data on an app-lifetime object; a
        // process-preserving reset must not leave them resident.
        runCatching { statsRepository.invalidateCache() }
        // The inference warmup latch is monotonic + in-memory, so it would survive the process-preserving
        // reset and let the forecast run on the now-empty history; drop it so warmup is re-earned.
        runCatching { inferenceController.resetWarmupLatch() }
        // Re-arm the watch link now the wipe is done. `stopForReset` disabled it so no late push could
        // re-persist key material into rows being cleared; that guard has served its purpose, and
        // since this reset no longer kills the process nothing else would ever undo it.
        runCatching { watchLink.resumeAfterReset() }
        reevaluateInferenceNow()      // recompute off the (now-empty) history → drops any stale forecast
    }

    /**
     * Return the UI to a first-run state IN-PROCESS (issue 5) — relaunch [MainActivity] as a fresh task
     * WITHOUT killing the process, so the foreground service (and the advertisement scan it holds)
     * survive the reset. The app-scoped [AppContainer] is reused; [resetAllData] has already returned
     * its caches to first-run, and every Room-backed StateFlow re-emits the wiped store, so the rebuilt
     * Activity opens at the empty home. (Contrast the old `Runtime.exit(0)`, which dropped the scan.)
     */
    fun restartApp() {
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { appContext.startActivity(it) }
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
     *
     * **The promotion is a substitution, and everything keyed to the old artifact goes with it.**
     * `applyPending` renames the staged pair in place under an UNCHANGED local id, so nothing
     * downstream can tell one checkpoint from the next: the stored forecasts, the realized-accuracy
     * figures computed from them, and any band correction fitted on them would all survive the swap
     * and be re-attributed to a network that never produced them. Worse, the correction would go on
     * being drawn, and the next recalibration would fit across a calibration set mixing two models'
     * error distributions — the exchangeability the whole method rests on. So this drops them, for
     * the reason [removeModel] drops them: they are evidence about an artifact that is gone.
     */
    suspend fun applyModelUpdate(modelId: String): Boolean = withContext(dispatchers.io) {
        val applied = runCatching { modelSyncCoordinator.applyPending(modelId) }.getOrDefault(false)
        if (applied) {
            runCatching { repository.deletePredictionsForModel(modelId) }
            runCatching { repository.deleteBandCalibration(modelId) }
            inferenceController.refreshModels()
        }
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
            // The correction was fitted on THIS model's forecasts and means nothing without them.
            runCatching { repository.deleteBandCalibration(modelId) }
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

    // ─── Curve engine + manual entry (Phase 4) ────────────────────────────────────────────────

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

    /** The mood last folded into the wide sample; seeds the Logs panel's picker, which is its only
     *  user-facing writer (see [saveMood]). */
    val latestMood: Flow<Int?> = repository.observeLatestMood()

    /** Live preview of the exact carb appearance (Ra) curve the model will see for a GI. */
    val previewCarbCurve: suspend (Double, Double) -> DoubleArray = { grams, gi ->
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        curveEngine.gamma(grams, k, theta, dur)
    }

    /**
     * Live preview of the PK-action curve a dose of [units] would commit for [spec] — bit-for-bit the
     * curve [logBolus]/[logBasal] persist for that preset, since both go through [presetCurve]. The
     * basal branch is the long-acting Bateman (issue N9): broad and near-flat by design.
     *
     * Taking the preset by value rather than resolving one is what lets the panel's sparkline redraw
     * on a chip tap. It used to resolve the Settings selection, so the preview stood still while the
     * user moved between presets — and stood for a curve the panel had not offered.
     */
    val previewDoseCurve: suspend (Double, InsulinPresetSpec) -> DoubleArray = { units, spec ->
        presetCurve(units, spec)
    }

    /** The MODEL's two reconstructed channels over a grid window (feat 1 / feat 2), off-main. The
     *  model consumes the COMBINED insulin channel and has no use for the basal series. */
    suspend fun dashboardCurveChannels(gridStartMs: Long, nSteps: Int): Pair<DoubleArray, DoubleArray> {
        val ch = channelBuilder.contextChannels(gridStartMs, nSteps)
        return ch.carb to ch.insulin
    }

    /** The dashboard overlay resolver: carbs, combined insulin, and the BASAL-only sub-channel
     *  (issue 18 — auto-extended schedule XOR logged long-acting injections) over one grid window,
     *  from ONE gather. The panel draws all three together and used to pull the basal series through
     *  a second entry point, which resolved the same padded window and rebuilt the same basal
     *  representation a second time on every overlay rebuild. Off-main. */
    suspend fun dashboardOverlayChannels(
        gridStartMs: Long,
        nSteps: Int,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val ch = channelBuilder.overlayChannels(gridStartMs, nSteps)
        return Triple(ch.carb, ch.insulin, ch.basal)
    }

    /**
     * The BG panel's STEPS overlay: the pedometer count per 5-min bucket over the same kind of grid
     * window [dashboardOverlayChannels] answers for, as `out[i]` = steps in
     * `[gridStartMs + i·GRID_MS, +GRID_MS)`.
     *
     * The DENSIFY happens here, on the IO hop, for two reasons. It keeps `:feature:dashboard` free of
     * any `:data` type — the panel is handed a primitive array and never a Room row, as it is for the
     * curve channels. And the read itself is sparse: a sleeping night stores no rows for its buckets
     * at all, so walking a short result into a zeroed array costs less than making SQL emit the
     * zeroes, and the panel gets the dense array its frame builder wants either way.
     */
    suspend fun dashboardStepSeries(gridStartMs: Long, nSteps: Int): IntArray {
        if (nSteps <= 0) return IntArray(0)
        val step = T1dmRepository.GRID_MS
        val out = IntArray(nSteps)
        val endMs = gridStartMs + (nSteps - 1).toLong() * step
        for (row in repository.stepSeriesInRange(gridStartMs, endMs)) {
            val i = ((row.ts - gridStartMs) / step).toInt()
            if (i in 0 until nSteps) out[i] = row.steps
        }
        return out
    }

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
        // F5: the second half is the instant the last active insulin (logged doses + basal tails) decays
        // to zero — the landmark the circadian panel's insulin-exhaustion countdown projects forward
        // from. It used to be a separate call that re-read the same padded 48 h window, re-ran the same
        // basal-schedule lookup and rebuilt the same PK curves the IOB had just been derived from; the
        // widget pulls this on every refresh, so the whole reconstruction was running twice a push.
        // Its `runCatching` went with it and is not missed: the only failing step it covered was the
        // gather, which the IOB above needs first and does not guard, and the zero derivation itself is
        // total arithmetic over events already in hand. Both callers guard this whole method anyway.
        val insulin = channelBuilder.insulinOnBoard(now)
        val cob = channelBuilder.onBoard(now, CurveKind.CARB)
        val lastLogged = repository.latestLoggedInsulinTs()
        val hasBasal = repository.activeBasalDoses().isNotEmpty()
        return IobCobReadout(
            atMs = now,
            iobU = insulin.iobU,
            cobG = cob,
            minsSinceLastLoggedInsulin = lastLogged?.let { (now - it) / 60_000L },
            hasBasalSchedule = hasBasal,
            iobZeroMs = insulin.zeroMs,
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
            smoothingWindowProvider = { smoothingWindow() },
        )
    }

    /** Resolves a candidate dose into its dose-scaled gamma PK announced-future events (§3.3). The
     *  advisor has no pick of its own, so it searches against the insulin last actually logged. */
    private val bolusResolver = BolusResolver { doseU, atMs ->
        val spec = resolveRapidPreset(null)
        listOf(curveEngine.rapidEvent(doseU, atMs, spec.peakMin, spec.diaMin))
    }

    private val bolusCalculator by lazy { BolusCalculator(rollingForecaster, bolusResolver) }

    /** Resolves the sensitivity probe's announced meal into its appearance (Ra) gamma, at the
     *  mixed-meal GI the bolus advisor also defaults to. The GI is pinned rather than followed from a
     *  setting because it shapes how much of the meal has appeared by the probe's horizon: a GI that
     *  moved between probes would surface as the patient's ratio changing. */
    private val probeCarbResolver = CarbResolver { grams, atMs ->
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(PROBE_GI)
        listOf(curveEngine.carbEvent(grams, atMs, k, theta, dur))
    }

    private val sensitivityProbe by lazy {
        SensitivityProbe(rollingForecaster, bolusResolver, probeCarbResolver, anchorSource) {
            inferenceController.authorityModelInfo()?.takeIf { it.real }?.id
        }
    }

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
    val doseAdvisor: DoseAdvisor by lazy {
        DoseAdvisor(bolusCalculator, anchorSource, iobSource, backendSource, { smoothingWindow() })
    }

    /** The calculator UI/service surface: Idle → Running → Ready(result). Never actuates. */
    sealed interface BolusAdviceUi {
        data object Idle : BolusAdviceUi
        data object Running : BolusAdviceUi
        /**
         * A finished recommendation, stamped with WHEN it was computed and how long it may stand.
         *
         * The stamp is the whole point: this flow is application-scoped and has no ticker, so a
         * Recommended result used to be held across navigation and Activity recreation with Accept
         * live and every freshness fact on its decision card — the anchor age above all — frozen at
         * search time yet rendered in the present tense.
         */
        data class Ready(
            val result: AdviceResult,
            val computedAtMs: Long,
            val staleAfterMs: Long,
        ) : BolusAdviceUi
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
            .getOrElse { AdviceResult.Refused(listOf("Calculator error — ${it.message ?: it::class.simpleName}")) }
        // The advice may stand exactly as long as the anchor it was computed from: past the
        // calculator's own §3.6-D freshness limit the recommendation is describing a BG that is no
        // longer current, so the screen must stop offering it.
        bolusAdvice.value = BolusAdviceUi.Ready(result, now, cfg.freshnessMaxAgeMs)
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
                        "Roll failed — ${it.message ?: it::class.simpleName}",
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

    // ── The model-probed ISF / ICR read-out ───────────────────────────────────────────────────────
    //
    // Structurally isolated exactly as the rolled forecast above: a [SensitivityEstimate] is a type
    // neither [doseAdvisor], [inferenceState], the store, nor the outbox accepts, so a probe can
    // never raise an alert, move a rail, or be mistaken for a logged fact. Its readers are the three
    // panels that display it — BG, Meals and Insulin — each through `:core:design` OnBoardReadout,
    // and nothing else. Isolation is the type's, not the reader count's: adding a fourth display
    // costs nothing here, and no reader can make it act.

    /** The current ISF/ICR estimate, or null when no model response was obtained — which the panels
     *  render as "N/A" rather than hiding, so an absence reads as one. See [SensitivityProbe]. */
    val sensitivity = MutableStateFlow<SensitivityEstimate?>(null)

    private var sensitivityJob: Job? = null

    /** When a probe was last STARTED, whatever it returned — the attempt rate limiter's clock. */
    private var lastProbeAtMs: Long? = null

    /**
     * Re-probe ISF/ICR if the held estimate has aged past [SENSITIVITY_TTL_MS], and drop it outright
     * once it is older than [SENSITIVITY_LAPSE_MS].
     *
     * Called on a coarse ticker by each panel that displays the figures (`:app` Navigation's
     * `rememberSensitivity`), so three model forwards are spent only while the read-out is actually
     * on screen, and the rate limits below — not the caller — decide how often a probe really runs.
     * Deliberately NOT driven off the inference cycle: `lastCycleTsMs` stops advancing on the
     * thermal, warm-up and no-context paths, which are exactly the states where a held figure is most
     * likely to be out of date, so the lapse would never fire in them.
     *
     * Insulin sensitivity moves with the circadian phase, so a held figure is a claim about a past
     * hour: the lapse is what stops a probe taken before the phone was pocketed from being read as
     * current after it comes back out.
     */
    fun refreshSensitivityIfStale() {
        val now = System.currentTimeMillis()
        var held = sensitivity.value

        // A selection change invalidates the figure OUTRIGHT — it describes the artifact it was
        // probed on, and the whole point of switching models is to compare them. Age has nothing to
        // say about it: a two-minute-old estimate from the model you just replaced is exactly as
        // wrong as a two-hour-old one, and waiting out the TTL to find that out is the behaviour
        // this branch exists to remove.
        //
        // Read from the controller rather than taken as a parameter: `selectedId` is set under
        // `cycleMutex` by `selectModel` and is true the instant the user taps, whereas the UI's view
        // of the selection comes from `predictions`, which carries no entry for a model that has not
        // forecast yet — precisely the case here, since the model was just switched to.
        val selectedModelId = runCatching { inferenceController.authorityModelInfo()?.id }.getOrNull()
        if (held != null && held.modelId != selectedModelId) {
            sensitivity.value = null
            lastProbeAtMs = null   // the retry/TTL clock belongs to the old model too
            held = null
        }

        // Age is absolute, not elapsed: a backwards clock correction must expire a held estimate
        // rather than freeze it. This runs BEFORE every other branch, so the lapse is enforced on
        // each call even when nothing below decides to re-probe.
        val age = held?.let { Math.abs(now - it.atMs) }
        if (age != null && age >= SENSITIVITY_LAPSE_MS) sensitivity.value = null

        // While the app is collecting context it publishes no forecast at all, and a figure
        // differenced off three rolls it declines to draw would be the only model-derived number on
        // screen. Drop what is held rather than merely skipping the re-probe: warm-up can begin
        // (a sensor change, a wipe) with an estimate already up.
        if (inferenceState.value.warmup != null) {
            sensitivity.value = null
            return
        }
        if (age != null && age < SENSITIVITY_TTL_MS) return
        // Rate-limit ATTEMPTS, not just successes. A probe that withholds leaves nothing to age, so
        // gating on the held estimate alone would re-run three fp32 forwards on every tick for as
        // long as the model cannot justify a figure — which is precisely when the phone is already
        // busy or hot. A withheld probe retries on the shorter interval so a recovering signal is
        // picked up without waiting out the full TTL.
        val sinceAttempt = lastProbeAtMs?.let { Math.abs(now - it) }
        if (sinceAttempt != null && sinceAttempt < (if (held != null) SENSITIVITY_TTL_MS else SENSITIVITY_RETRY_MS)) return
        if (sensitivityJob?.isActive == true) return
        lastProbeAtMs = now
        sensitivityJob = appScope.launch {
            val cfg = runCatching { settingsStore.currentCalcConfig() }.getOrDefault(CalcConfig())
            val pinned = runCatching { smoothingWindow() }.getOrNull()
            sensitivity.value = runCatching {
                sensitivityProbe.probe(System.currentTimeMillis(), cfg, pinned)
            }.getOrElse {
                Timber.tag("Sensitivity").w(it, "probe failed")
                null
            }
        }
    }

    /** Record the human's acceptance of an advised bolus — logs it exactly like a manual bolus (the
     *  same self-describing `logged_dose` + series push). This never actuates; it only journals the
     *  dose the user tells us they administered. A 0 U / carb-rescue acceptance logs nothing here and
     *  therefore returns NO handle: a receipt offering to undo a row that was never written would
     *  dangle, and its Undo would silently delete whatever rowid 0 happens to be. */
    suspend fun acceptAdvisedBolus(units: Double): LogHandle? =
        if (units.isFinite() && units > 0.0) logBolus(units) else null

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

    /**
     * How long a freshly logged meal/dose push is held back before its first send attempt — the window
     * in which the Logs panel can still withdraw it whole. Read per write rather than cached, so an
     * edit to the knob governs the very next log.
     *
     * It delays ONLY the outbox row. The forecast reads the local `logged_*` rows through
     * `ChannelBuilder` (via [RoomDoseStore]) and never the queue, so a held push cannot change what the
     * model is conditioned on — a log is in the carb/insulin channel the moment the row exists,
     * whatever the queue is doing. Likewise IOB/COB, which [iobCob] recomputes off
     * [T1dmRepository.logEvents].
     */
    private suspend fun pushHoldMs(): Long =
        settingsStore.currentPushHoldMin().toLong() * 60_000L

    /** Log a single-food meal: persist the self-describing `logged_meal` (GI→gamma; the repository
     *  grid-snaps `ts` and mints the `client_id`) and push it as a `PUT /v1/meals` event built from the
     *  PERSISTED entity, so app + sample + wire agree on one grid ts and one id (§3.1/§3.2). */
    suspend fun logCarb(grams: Double, gi: Double): LogHandle {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        val meal = repository.logMeal(
            LoggedMealEntity(
                clientId = "", tsMs = now, grams = grams, gi = gi, k = k, theta = theta,
                durationMin = dur, customCurve = null, tzOffsetMin = tz, note = null, updatedAt = now,
            ),
        )
        val outboxId = outboxEnqueuer.enqueueMeal(meal.toMealEventDto(), now, holdMs = pushHoldMs())
        reforecastAfterCurveWrite()
        return meal.handle(outboxId, "${fmtAmount(grams)} g (GI ${fmtAmount(gi)})")
    }

    /**
     * Log a multi-food builder meal (the Meals-screen builder path, invoked from Navigation). Persists
     * via [MealsController] — which resolves the combined appearance curve into `customCurve`, grid-snaps
     * `ts`, and mints the `client_id` — then pushes the resulting self-describing event as a
     * `PUT /v1/meals`. Before this the builder persisted but synced nothing (§3.2 builder-never-synced fix).
     */
    suspend fun logBuilderMeal(components: List<MealComponent>): LogHandle {
        val now = System.currentTimeMillis()
        val meal = mealsController.logMeal(components)
        val outboxId = outboxEnqueuer.enqueueMeal(meal.toMealEventDto(), now, holdMs = pushHoldMs())
        reforecastAfterCurveWrite()
        val foods = components.size
        return meal.handle(
            outboxId,
            "${fmtAmount(meal.grams)} g ($foods food${if (foods == 1) "" else "s"})",
        )
    }

    /** The clinical insulin preset catalogue (issue 19) — the insulin panel's chips, and the only
     *  set of insulins a dose write can name. */
    suspend fun insulinPresetCatalog(): List<InsulinPresetSpec> = curveEngine.presetCatalog()

    /** [spec]'s action curve for [units]: the exponential activity model for rapid, the Bateman for
     *  long-acting. The single place a preset becomes numbers, so preview and commit cannot diverge. */
    private suspend fun presetCurve(units: Double, spec: InsulinPresetSpec): DoubleArray = when (spec.family) {
        InsulinFamily.RapidExp -> curveEngine.expAction(units, spec.peakMin, spec.diaMin)
        InsulinFamily.BasalBateman -> curveEngine.bateman(units, spec.diaMin, spec.kaPerHour, spec.kePerHour)
    }

    /**
     * The preset a write of [family] should commit given the caller's [requestedLabel] — see
     * [resolveInsulinPreset] for the precedence and why the requested label wins.
     *
     * Throws on an empty catalogue rather than substituting a curve: that is a broken native build,
     * and a dose row carrying an invented PK would be worse than no row at all.
     */
    private suspend fun resolvePreset(family: InsulinFamily, requestedLabel: String?): InsulinPresetSpec =
        requireNotNull(
            resolveInsulinPreset(
                catalog = insulinPresetCatalog(),
                family = family,
                requested = requestedLabel,
                lastLogged = when (family) {
                    InsulinFamily.RapidExp -> settingsStore.lastRapidPreset()
                    InsulinFamily.BasalBateman -> settingsStore.lastBasalPreset()
                },
            ),
        ) { "The insulin preset catalogue holds no $family entry." }

    private suspend fun resolveRapidPreset(label: String?) = resolvePreset(InsulinFamily.RapidExp, label)

    private suspend fun resolveBasalPreset(label: String?) = resolvePreset(InsulinFamily.BasalBateman, label)

    /**
     * The insulin a dose logged RIGHT NOW with no pick of its own would carry — the sticky memory of
     * the last committed dose of that kind, falling back to the head of the catalogue. Read by the
     * insulin panel to seed its chip row, and by the bolus advisor to name the insulin it searched
     * against; both would otherwise have to guess, and a guess shown beside a dose is a claim.
     */
    suspend fun resolvedRapidLabel(): String = resolveRapidPreset(null).label

    suspend fun resolvedBasalLabel(): String = resolveBasalPreset(null).label

    /**
     * The guard every dose write shares. `units > 0.0` is not it: that rejects NaN by accident but
     * admits +Infinity, which enters the action curve as an infinite scale and settles as NaN in IOB
     * — where it defeats the §3.6-C ceiling outright, every comparison against NaN being false. The
     * UI gates on the same predicate, so reaching this throw means a non-UI caller is at fault; it
     * fails closed, leaving no row rather than a poisoned one.
     */
    private fun requireLoggableDose(units: Double) {
        require(units.isFinite() && units > 0.0) { "Dose units must be positive and finite (was $units)." }
    }

    /**
     * Log a bolus against [presetLabel] — the rapid preset the insulin panel showed and the
     * confirmation dialog named. Writes the self-describing `logged_dose` (that preset's exponential
     * action model resolved into `customCurve`, so the row reconstructs exactly) and a
     * `PUT /v1/doses` event built from the PERSISTED (grid-snapped, client_id-minted) entity
     * (§3.1/§3.2).
     *
     * A null [presetLabel] means the caller had no pick to offer — the accepted advisory bolus, a
     * debug quick action — and falls back to the last insulin logged. Anything else is honoured, and
     * that is the point: this writer used to take a preset argument and DISCARD it for a Settings
     * selection, so the dialog restated one insulin while the row carried another.
     */
    suspend fun logBolus(units: Double, presetLabel: String? = null): LogHandle {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val rapid = resolveRapidPreset(presetLabel)
        val curve = presetCurve(units, rapid)
        val dose = repository.logLoggedDose(
            LoggedDoseEntity(
                clientId = "", tsMs = now, kind = DoseKind.BOLUS, units = units, durationMin = rapid.diaMin,
                k = null, theta = null, kaPerHour = null, kePerHour = null,
                customCurve = if (curve.isEmpty()) null else curve.toList().toBlob(),
                tzOffsetMin = tz, note = rapid.label, updatedAt = now,
            ),
        )
        rememberLoggedPreset(rapid, presetLabel)
        val outboxId = outboxEnqueuer.enqueueDose(dose.toDoseEventDto(), now, holdMs = pushHoldMs())
        reforecastAfterCurveWrite()
        return dose.handle(outboxId, "${fmtAmount(units)} U bolus · ${rapid.label}")
    }

    /**
     * Log a discrete long-acting basal injection against [presetLabel]: `logged_dose` carrying that
     * preset's DIA + ka/ke, so the Bateman reconstructs analytically, plus a `PUT /v1/doses` event
     * built from the PERSISTED (grid-snapped, client_id-minted) entity (§3.1/§3.2). Null resolves as
     * in [logBolus].
     */
    suspend fun logBasal(units: Double, presetLabel: String? = null): LogHandle {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val basal = resolveBasalPreset(presetLabel)
        val dose = repository.logLoggedDose(
            LoggedDoseEntity(
                clientId = "", tsMs = now, kind = DoseKind.BASAL, units = units, durationMin = basal.diaMin,
                k = null, theta = null, kaPerHour = basal.kaPerHour, kePerHour = basal.kePerHour,
                tzOffsetMin = tz, note = basal.label, updatedAt = now,
            ),
        )
        rememberLoggedPreset(basal, presetLabel)
        val outboxId = outboxEnqueuer.enqueueDose(dose.toDoseEventDto(), now, holdMs = pushHoldMs())
        reforecastAfterCurveWrite()
        return dose.handle(outboxId, "${fmtAmount(units)} U basal · ${basal.label}")
    }

    /**
     * Make [spec] the insulin the next unpicked dose of its family will use — stickiness, not a
     * setting. Called only AFTER the row is persisted, so a failed write leaves the memory alone,
     * and only when the caller actually named a preset ([requestedLabel] non-null): a fallback
     * resolution has expressed no preference and must not overwrite one.
     */
    private suspend fun rememberLoggedPreset(spec: InsulinPresetSpec, requestedLabel: String?) {
        if (requestedLabel == null) return
        when (spec.family) {
            InsulinFamily.RapidExp -> settingsStore.setLastRapidPreset(spec.label)
            InsulinFamily.BasalBateman -> settingsStore.setLastBasalPreset(spec.label)
        }
    }

    /**
     * Log a dose against a picked/drawn insulin **type** (the `insulin/types` surface). Persisted by
     * [InsulinController] — which resolves the type's PK action curve, grid-snaps `ts` and mints the
     * `client_id` — then pushed as a `PUT /v1/doses` built from the persisted entity, exactly as
     * [logBolus] and [logBasal] are.
     *
     * That push is new here. This path reached `InsulinController` straight from Navigation with no
     * `:app` entry point, so it enqueued nothing and its rows reached the server only via a §3.8
     * re-mirror. The Logs panel is what closes the asymmetry: it reads committed-vs-delivered off the
     * QUEUE, and a row that never enqueues is indistinguishable from one whose push has already
     * drained — so leaving this path unpushed would have had the panel call a typed dose "delivered"
     * the instant it was written, and refuse to delete it, which is the one claim the panel must never
     * make.
     */
    suspend fun logTypedDose(type: InsulinType, units: Double): LogHandle {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val dose = insulinController.logDose(type, units)
        val outboxId = outboxEnqueuer.enqueueDose(dose.toDoseEventDto(), now, holdMs = pushHoldMs())
        // The re-run is owed to the ROW, not the push: the forecast reads the `logged_dose` through
        // ChannelBuilder and never the queue, so this path would need it even if it enqueued nothing.
        reforecastAfterCurveWrite()
        val kind = if (type.kind == InsulinKind.BOLUS) "bolus" else "basal"
        return dose.handle(outboxId, "${fmtAmount(units)} U $kind · ${type.name}")
    }

    /**
     * Take back a just-logged meal/dose: the event row and its still-queued push are deleted in ONE
     * repository transaction, and `T1dmRepository.logEvents` — the only trigger an event delete can
     * fire, since it touches neither `cgm_reading` nor `sample` — is bumped, which is what repaints
     * [iobCob] and, through it, the dashboard's curve overlay and the §3.6-F IOB provenance line.
     *
     * The returned [PushWithdrawal] is what the receipt must be honest about: everything local is
     * gone unconditionally, but a push that already drained is on the server for good.
     *
     * The local row goes whatever the push's fate, so the carb/insulin channel has moved and the
     * forecast is re-run — the withdrawal of a dose lowers assumed IOB exactly as logging it raised it.
     */
    suspend fun undoLog(handle: LogHandle): PushWithdrawal = when (handle.kind) {
        LoggedEventKind.MEAL -> repository.undoLoggedMeal(handle.rowId, handle.outboxId, handle.dedupKey)
        LoggedEventKind.DOSE -> repository.undoLoggedDose(handle.rowId, handle.outboxId, handle.dedupKey)
    }.also { reforecastAfterCurveWrite() }

    private fun LoggedMealEntity.handle(outboxId: Long, label: String) = LogHandle(
        kind = LoggedEventKind.MEAL,
        rowId = id,
        clientId = clientId,
        tsMs = tsMs,
        outboxId = outboxId,
        dedupKey = mealDedupKey(clientId),
        label = label,
    )

    private fun LoggedDoseEntity.handle(outboxId: Long, label: String) = LogHandle(
        kind = LoggedEventKind.DOSE,
        rowId = id,
        clientId = clientId,
        tsMs = tsMs,
        outboxId = outboxId,
        dedupKey = doseDedupKey(clientId),
        label = label,
    )

    // ─── The logged-event feed both the Logs panel and the BG panel's marks are read from ─────

    /**
     * The logged-event feed: the newest logged meals and doses interleaved newest-first, each carrying
     * whether the server has accepted it yet.
     *
     * ONE feed for both surfaces that show these rows — the Logs panel's list, and the BG panel, which
     * reduces it to [com.t1dm.core.model.LogMarker] at its own edge so the drawing layer receives no
     * amount and no row id. Reducing it there rather than here is what makes a mark and the row behind
     * it the same list position, and therefore what lets a tap on a mark name what it stands for.
     *
     * **The join happens HERE and nowhere else, on purpose.** The verdict is queue membership under the
     * event's dedup key, and that key's format ([mealDedupKey] / [doseDedupKey]) is owned by `:sync`,
     * which `:data` must not depend on and must not re-spell — so `:data` hands up the two event flows
     * and the queued key set, and this is the one place that holds them against each other. There is no
     * SENT state (`QueueDrainer` DELETEs on a 2xx), so presence in that set is the whole of "not
     * delivered", and time alone can never promote a row: an offline phone keeps every log
     * [LogState.COMMITTED] for as long as it stays offline.
     *
     * Absence is read as delivered, which is honest rather than proven — see [LogState].
     */
    val loggedEntries: Flow<List<LoggedEntry>> = combine(
        repository.observeRecentLoggedMeals(LOG_FEED_LIMIT),
        repository.observeRecentLoggedDoses(LOG_FEED_LIMIT),
        repository.observeQueuedDedupKeys(listOf(OutboxKind.MEAL, OutboxKind.DOSE)),
    ) { meals, doses, queued ->
        val rows = meals.map { it.toLoggedEntry(queued) } + doses.map { it.toLoggedEntry(queued) }
        rows
            // Totally ordered, not merely sorted by time: two rows can share a grid slot (the event ts
            // is snapped to the 5-min grid), and an unstable order would reshuffle the list under the
            // reader on every unrelated emission.
            .sortedWith(
                compareByDescending<LoggedEntry> { it.tsMs }
                    .thenBy { it.kind }
                    .thenByDescending { it.rowId },
            )
            .take(LOG_FEED_LIMIT)
    }

    /**
     * Remove a logged entry the user has decided against. Reuses the undo path's single deletion
     * transaction, and inherits its refusal: with the push already drained the server holds the event,
     * the API has no DELETE, and the next WS catch-up would re-hydrate it by `clientId` — so nothing is
     * removed and [PushWithdrawal.ALREADY_SENT] comes back for the caller to say so.
     *
     * The re-run is conditional for the same reason: on the refusal NOTHING was deleted, so no channel
     * moved and a cycle would only recompute the forecast it already published.
     */
    suspend fun deleteLoggedEntry(entry: LoggedEntry): PushWithdrawal = when (entry.kind) {
        CurveKind.CARB -> repository.deleteCommittedMeal(entry.rowId, mealDedupKey(entry.clientId))
        CurveKind.INSULIN -> repository.deleteCommittedDose(entry.rowId, doseDedupKey(entry.clientId))
    }.also { if (it != PushWithdrawal.ALREADY_SENT) reforecastAfterCurveWrite() }

    /** The withdrawal window, in minutes, and its writer — the Logs panel's own knob. */
    val pushHoldMin: Flow<Int> get() = settingsStore.pushHoldMin

    suspend fun setPushHoldMin(minutes: Int) = settingsStore.setPushHoldMin(minutes)

    private fun LoggedMealEntity.toLoggedEntry(queued: Set<String>) = LoggedEntry(
        rowId = id,
        clientId = clientId,
        kind = CurveKind.CARB,
        insulin = null,
        tsMs = tsMs,
        tzOffsetMin = tzOffsetMin,
        amount = grams,
        // Both carried as they are STORED: the index when the row has one (a builder meal has a
        // combined curve and no single index), and the note beside it rather than instead of it. How
        // either reads is the reader's business — `:core:design` owns that wording for every surface
        // at once — and a phrase rendered here would be the copy those surfaces later disagreed over.
        gi = gi,
        detail = note,
        state = stateFor(mealDedupKey(clientId), queued),
    )

    private fun LoggedDoseEntity.toLoggedEntry(queued: Set<String>) = LoggedEntry(
        rowId = id,
        clientId = clientId,
        kind = CurveKind.INSULIN,
        insulin = if (kind == DoseKind.BOLUS) InsulinKind.BOLUS else InsulinKind.BASAL,
        tsMs = tsMs,
        tzOffsetMin = tzOffsetMin,
        amount = units,
        gi = null,
        // The resolved insulin the writer persisted — the clinical preset or the custom type, i.e. the
        // curve this row actually reconstructs through, not whatever chip was on screen.
        detail = note,
        state = stateFor(doseDedupKey(clientId), queued),
    )

    private fun stateFor(dedupKey: String, queued: Set<String>): LogState =
        if (dedupKey in queued) LogState.COMMITTED else LogState.DELIVERED

    /** Receipt/dialog numerals: integral amounts read as "45", a half unit still as "4.5". */
    private fun fmtAmount(v: Double): String =
        if (v == Math.rint(v) && !v.isInfinite()) v.toLong().toString() else "%.1f".format(v)

    /** Save a mood into its 5-min `sample` bucket. `recordMood` folds it into the wide sample and
     *  enqueues the INGEST push, so mood rides the six-scalar `POST /v1/ingest` — no separate curve push. */
    suspend fun saveMood(mood: Int) {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val gridTs = snapToGrid(now)
        repository.recordMood(gridTs, tz, mood, now)
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

    /** The active source's trailing [SMOOTHING_PREVIEW_HOURS] of mg/dL, oldest→newest — the sample the
     *  Graph-settings BG-input-filter miniature redraws at each detent. Bounded at the QUERY rather
     *  than by tailing [dashboardReadings]: a settings screen has no business scanning the whole store. */
    val smoothingPreviewMgdl: Flow<DoubleArray> = activeSource.flatMapLatest { d ->
        if (d == null) flowOf(emptyList()) else {
            val from = System.currentTimeMillis() - SMOOTHING_PREVIEW_HOURS * 3_600_000L
            repository.observeReadings(d.id, from, Long.MAX_VALUE)
        }
    }.map { readings ->
        readings.asSequence()
            .filter { it.bgMgdl != null && it.flag != ReadingFlag.INVALID }
            .sortedBy { it.tsMs }
            .map { it.bgMgdl!!.toDouble() }
            .toList()
            .toDoubleArray()
    }

    /** The BG panel's freehand annotation layer (Room v8). Read over the WHOLE store, mirroring
     *  [dashboardReadings]: the panel pans across the entire history (and 24 h into the empty future),
     *  and strokes are few, display-only and unindexed by source. `:ui:graph` never sees the repository —
     *  this is collected here and handed down as plain state, like every other dashboard read model. */
    val paintStrokes: Flow<List<PaintStroke>> = repository.observePaintStrokes(0L, Long.MAX_VALUE)

    /**
     * Persist one finished stroke and hand back the row id the store minted — the id is what makes the
     * dashboard's undo stack and its eraser addressable. Off-main (the repository wraps it), and the
     * ONLY write path the annotation layer has.
     */
    suspend fun addPaintStroke(stroke: PaintStroke): Long = repository.addPaintStroke(stroke)

    /** Remove one stroke, whole: the eraser and undo both work in units of a stroke, never of geometry. */
    suspend fun deletePaintStroke(id: Long) = repository.deletePaintStroke(id)

    val latestReading: Flow<CgmReading?> = activeSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeLatestReading(d.id)
    }

    /**
     * IOB/COB (§3.6-F) recomputed off-main on ANY trigger that can change it: a reading emit, a scalar
     * `sample` write (mood/steps), AND a logged dose/meal. Meal/dose events no longer project onto
     * `sample` (the carb/bolus/basal scalar columns were retired, §3.1), so they are picked up via
     * [T1dmRepository.logEvents] (bumped in `logMeal`/`logLoggedDose`, through which every log path funnels
     * — `logCarb`/`logBolus`/`logBasal`, `MealsController.logMeal`, `InsulinController.logDose`), so a
     * just-logged dose refreshes IOB/COB at once rather than 0 U/0 g until the next reading. `mapLatest`
     * cancels an in-flight compute on a newer trigger; collected on [appScope] (default dispatcher) and
     * the store reads hop to IO, so this never touches the main thread.
     *
     * All three arms are CHANGE SIGNALS — this flow reads nothing from them, it only recomputes. Two
     * of them used to arrive as whole tables: [dashboardReadings] is every reading the active source
     * has ever taken, and `observeSamples(0, MAX)` is the entire wide projection, both never pruned,
     * both re-queried and re-materialised on every reading, and both discarded here by `.map { }`.
     * [latestReading] and [T1dmRepository.observeSampleWrites] are the one-row and one-scalar
     * observations of the very same two tables; because Room invalidates per TABLE, they emit at
     * exactly the same instants the whole-table reads emitted at, so the recompute schedule — and
     * therefore every value the card shows — is unchanged.
     */
    val iobCob: StateFlow<IobCobReadout?> =
        merge(
            latestReading.map { },
            repository.observeSampleWrites().map { },
            // Meal/dose logs no longer project onto `sample` (the carb/bolus/basal scalars are retired),
            // so the sample signal no longer fires on a log — subscribe to the repository's log-write tick
            // so a just-logged dose/meal refreshes IOB/COB at once instead of waiting for the next reading.
            repository.logEvents.map { },
        )
            .onStart { emit(Unit) }
            .mapLatest { runCatching { iobCobNow() }.getOrNull() }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set once [com.t1dm.app.service.CgmScanService] is up, so the UI can reflect service state. */
    val serviceRunning = MutableStateFlow(false)

    /**
     * The deterministic alarm picture (§3.6-A), republished for the UI.
     *
     * [AlarmEngine] is owned by the FGS and its [com.t1dm.alerts.AlarmController] never leaves it, so
     * until now no composable could learn that an alarm was raised — the KDoc on that controller says
     * its state "is re-exposed for the UI to observe", and this is the plumbing that finally does it.
     * Pushed from the service's existing state collector (the same single-thread `default` slice the
     * engine runs on), exactly as [serviceRunning] is pushed on start-up; the sole consumer today is
     * the minigame's pause interlock, which is cosmetic and can never influence WHEN the engine fires.
     */
    val alarmState = MutableStateFlow(AlarmState.CLEAR)

    /**
     * Whether the model-PREDICTIVE urgent alert is showing — the second, independent writer to the
     * vibrator (`PredictiveAlertPresenter` builds its own `VibrationActuator` and is not routed through
     * `AndroidAlarmNotifier`), and therefore a second edge any actuator interlock has to watch.
     *
     * Pushed from the same refresh that decides whether to announce, so this is the GATED decision —
     * suppressed under a deterministic critical breach, cleared under DEATH — not the raw forecast.
     */
    val predictiveAlertRaised = MutableStateFlow(false)

    // ─── Hill-climb minigame (cosmetic; reads the record, writes nothing) ─────────────────────


    /** The chosen run's window: [fromMs] through to the newest reading. A one-shot read of the same
     *  source-scoped range query the panel observes — the game never subscribes to it, because a track
     *  is cut once and a reading arriving mid-run must not rebuild the ground under the car. */
    suspend fun gameReadings(fromMs: Long): List<CgmReading> {
        val source = repository.activeSourceId() ?: return emptyList()
        return repository.observeReadings(source, fromMs, Long.MAX_VALUE).first()
    }

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

    /** Whether the public build's first-run disclaimer has been acknowledged (false on a fresh
     *  install and after a full reset). Read only by the public flavor's `Disclaimer`. */
    val disclaimerAcknowledged: Flow<Boolean> get() = settingsStore.disclaimerAcknowledged
    suspend fun acknowledgeDisclaimer() = settingsStore.acknowledgeDisclaimer()

    /** I11 — the user-entered CGM sensor-lifetime expiry instant (epoch-ms), or null when unset. */
    val sensorExpiryMs: Flow<Long?> get() = settingsStore.sensorExpiryMs

    /** Set/renew the sensor lifetime from a user-entered remaining duration; stores the absolute
     *  expiry so the countdown survives restarts. */
    suspend fun setSensorLifetime(days: Int, hours: Int, minutes: Int) {
        val durationMs = ((days.toLong() * 24 + hours) * 60 + minutes) * 60_000L
        settingsStore.setSensorExpiryMs(System.currentTimeMillis() + durationMs)
    }

    suspend fun clearSensorLifetime() = settingsStore.clearSensorExpiry()

    /**
     * The instant the active sensor's warm-up ends (epoch-ms), or null whenever there is no such instant
     * to state. Anchored on the latest reading's `minFromStart` — the sensor's own minutes-since-
     * activation, the one age a passive advertisement carries — taken against that reading's RAW receive
     * time, plus the ACTIVE source's persisted warm-up window.
     *
     * `rxWallMs`, not `tsMs`: the grid stamp is the same wall clock rounded to the nearest 5 minutes,
     * while the chip counts this instant down against an unrounded `System.currentTimeMillis()`. Anchoring
     * on it would carry ±2.5 min of error, and — because `tsMs` holds still across a slot while
     * `minFromStart` ticks each minute — would walk the deadline backwards within a slot and jump it
     * forwards at the boundary. The raw receive time advances in lockstep with the sensor's age, so the
     * reconstructed activation instant stays put.
     *
     * Null unless warm-up is genuinely in progress: it requires a latest reading actually flagged
     * `WARMUP` (the pipeline's own verdict — on this branch [com.t1dm.cgm.ReadingClassifier]'s, which is
     * that same window applied to that same age), a sensor age to anchor on, and an active source whose
     * window is known. Anything missing fails closed to null and the BG panel keeps its two-state expiry
     * countdown rather than inventing an instant.
     *
     * Deliberately NOT derived from [sensorExpiryMs], which on this branch is a user-entered absolute
     * instant with no sensor age behind it: the two countdowns answer different questions and only this
     * one is sensor-sourced.
     */
    val sensorWarmupEndMs: Flow<Long?> by lazy {
        combine(latestReading, activeSource) { latest, active ->
            if (latest == null || latest.flag != ReadingFlag.WARMUP) return@combine null
            val mfs = latest.minFromStart ?: return@combine null
            val window = active?.warmupWindowMin ?: return@combine null
            latest.rxWallMs - mfs.toLong() * 60_000L + window.toLong() * 60_000L
        }
    }

    /**
     * CGM panel: retune the ACTIVE source's sensor warm-up window (minutes). Routed through the registry
     * rather than straight at the repository because the live [com.t1dm.cgm.AidexXSource] holds the
     * classifier the next advert is judged against; a write that reached only the column would leave the
     * pipeline applying the old window until the process restarted.
     *
     * Nothing to do with the INFERENCE warm-up (`setWarmupHours`): that is how much trailing history the
     * forecast waits for, a wholly separate concept that happens to share a word.
     */
    suspend fun setSensorWarmupMin(minutes: Int) {
        val id = repository.activeSourceId() ?: return
        registry.setWarmupWindowMin(id, minutes)
    }

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

    /**
     * Set the global glucose unit AND refresh the home-screen widget in-process. The widget re-reads
     * the unit itself in `provideGlance`, and the FGS only pushes an `updateAll` on a unit change while
     * it is alive — so a mmol↔mg/dL switch made while the FGS is down leaves the widget on its stale
     * composition (the "widget sometimes shows mmol when set to mg/dL" bug). Firing `updateAll` here,
     * right after the kv commit (which the StatsViewModel + BG panel + FGS also observe), closes that
     * window regardless of FGS liveness.
     */
    fun setUnitSpace(space: com.t1dm.core.model.UnitSpace) {
        appScope.launch {
            statsRepository.setUnitSpace(space)
            runCatching { com.t1dm.app.widget.GlucoseWidget().updateAll(appContext) }
        }
    }

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
                // Read the live @Volatile alarm config each glance so a Settings threshold edit reaches
                // the watch (it was frozen to the boot-time value before).
                thresholdsProvider = { alarmConfig.thresholds },
                lossMinProvider = { alarmConfig.lossMin },
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

        /** The glycaemic index the ISF/ICR probe announces its 10 g meal at — the mixed-meal default
         *  the bolus advisor also falls back to. */
        const val PROBE_GI = 55.0

        /** How long a probed ISF/ICR estimate stands before a displaying panel re-probes. */
        const val SENSITIVITY_TTL_MS = 30 * 60_000L

        /** How long a probe that WITHHELD a figure waits before trying again — one inference
         *  cycle's worth, so a recovering anchor is picked up promptly without the retry costing
         *  more than the cycle running beside it. */
        const val SENSITIVITY_RETRY_MS = 5 * 60_000L

        /** How old a probed estimate may get before it is dropped rather than shown. Past this the
         *  figures describe a context — circadian phase, insulin on board, meal state — that is no
         *  longer the patient's. */
        const val SENSITIVITY_LAPSE_MS = 2 * 60 * 60_000L
    }
}
