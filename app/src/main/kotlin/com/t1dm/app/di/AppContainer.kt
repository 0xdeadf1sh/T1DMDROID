package com.t1dm.app.di

import android.content.Context
import android.net.NetworkCapabilities
import android.content.Intent
import android.media.RingtoneManager
import com.t1dm.app.notify.GlanceReadings
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
import com.t1dm.app.inference.KvBaselineStore
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
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.cgm.AidexXPlugin
import com.t1dm.cgm.AidexXSourceRegistry
import com.t1dm.core.model.isRealMeasurement
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.StatsWindow
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.BandCalibration
import com.t1dm.core.model.BandCalibrationOutcome
import com.t1dm.core.model.BandFitRefusal
import com.t1dm.core.model.ErrorGridLattices
import com.t1dm.core.model.ZoneLattice
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CurveKind
import com.t1dm.core.model.MaskGeometry
import com.t1dm.core.model.ReconstructedBg
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.InsulinPresetSpec
import com.t1dm.core.model.CgEga
import com.t1dm.core.model.MetricsConfig
import com.t1dm.core.model.ModelMetrics
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.IobCobReadout
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
import com.t1dm.inference.backend.GraphTensors
import com.t1dm.core.nativecore.UniffiNativeCore
import com.t1dm.app.exercise.AppExerciseSource
import com.t1dm.app.service.ExerciseService
import com.t1dm.core.model.ActiveExercise
import com.t1dm.app.stats.AppStatsSource
import com.t1dm.core.model.SpanLinePreview
import com.t1dm.data.BgCut
import com.t1dm.data.T1dmRepository
import com.t1dm.ui.graph.StepsFrame
import com.t1dm.ui.graph.MaskControls
import com.t1dm.ui.graph.MaskSelection
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
import com.t1dm.data.curve.ExerciseChannelSource
import com.t1dm.data.curve.DoseStore
import com.t1dm.data.curve.MealCurveResolver
import com.t1dm.data.curve.RoomDoseStore
import com.t1dm.data.exercise.ExerciseController
import com.t1dm.data.meals.InsulinController
import com.t1dm.data.meals.MealsController
import com.t1dm.data.stats.StatsRepository
import com.t1dm.feature.exercise.ExerciseSource
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
import com.t1dm.app.lab.LabController
import com.t1dm.feature.models.LoraFitSpec
import com.t1dm.feature.models.LoraFitProgress
import com.t1dm.feature.models.LoraPanelState
import com.t1dm.inference.HeadCache
import com.t1dm.inference.ContextChannelSource
import com.t1dm.inference.LoraStore
import com.t1dm.inference.ModelChannels
import com.t1dm.inference.CurveEventSource
import com.t1dm.inference.FutureOverrideSource
import com.t1dm.inference.InferenceController
import com.t1dm.inference.InferenceControllerDefaults
import com.t1dm.inference.ProbeInsulinPort
import com.t1dm.inference.buildInferenceController
import com.t1dm.sync.CatchUpCoordinator
import com.t1dm.sync.DrainConfig
import com.t1dm.sync.HistoryReMirror
import com.t1dm.sync.TombstoneReplay
import com.t1dm.sync.toDoseTombstoneDto
import com.t1dm.sync.toMealTombstoneDto
import com.t1dm.data.curve.GiToGamma
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
import com.t1dm.sync.nightscout.NightscoutClient
import com.t1dm.sync.nightscout.NightscoutConfigStore
import com.t1dm.sync.nightscout.NightscoutEnqueuer
import com.t1dm.sync.nightscout.OkHttpNightscoutClient
import com.t1dm.sync.WebSocketStreamClient
import kotlinx.coroutines.flow.update
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
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

/**
 * How much history the BG panel loads before the user pans for more. Thirty days covers every window
 * the panel offers and a long scroll back through two or three sensors, while keeping a re-query to a
 * few thousand rows instead of a lifetime.
 */
private const val INITIAL_HISTORY_WINDOW_MS = 30L * 24 * 3_600_000L

/** How many logged meals/doses the Logs feed carries. Bounded at the QUERY, per table, because both
 *  stores are keep-forever: at a handful of entries a day this is months of scrolling, and the panel is
 *  a review surface rather than an export. The interleaved list is trimmed to the same bound, so the
 *  cut is by TIME rather than by whichever table happens to be busier. */
private const val LOG_FEED_LIMIT = 400

/** Per-model kv key for the forecast-backend switcher (issue 20 STEP 4): the BackendId enum name per
 *  model id, or absent = auto (the fp32 XNNPACK authority). */
private fun kvForecastBackend(modelId: String) = "inference.forecast_backend.$modelId"

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

/** How many BG-panel edits the undo stack holds. Each Cut entry carries every row it removed, so
 *  the stack is the only thing on the device that grows with how much was cut. */
private const val BG_EDIT_UNDO_MAX = 32

/** How much history the panel's edit mode reaches when no model is loaded to size it — a day of
 *  five-minute slots, which is enough to place a cut anywhere the panel opens on. */
private const val CUT_ONLY_CONTEXT_STEPS = 288

/** The four registry-wide inputs the CGM panel folds (combine's typed arity is 5, so they are gathered
 *  here before the final combine with the per-sensor map and the sensor-life setting). */

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
    private val cgmRepository by lazy {
        AppCgmRepository(repository, outboxEnqueuer)
    }

    val plugin: AidexXPlugin by lazy { AidexXPlugin(nativeCore, cgmRepository) }

    /**
     * The passive-advertisement CGM registry (the sole read path). It owns the one shared
     * `BleAdvertScanner`, recognises every advert through [plugin], and routes each to its own source;
     * the FGS ([com.t1dm.app.service.CgmScanService]) calls `registry.start()` and collects
     * `registry.readings()`, narrowing to the AUTHORITATIVE source before the reading bus the
     * model-free alarm and inference consume (§3.6 — the alarm path is staleness-driven and must see
     * one sensor's stream, not several interleaved).
     */
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

    /**
     * The forecast store, held rather than built inline because the stream reconnect has to reach it:
     * the contract obliges a client to re-send its most recent forecast on every (re)connect, and
     * the only thing holding one is this.
     */
    val roomPredictionStore: RoomPredictionStore by lazy {
        RoomPredictionStore(repository, streamClient, syncStatusStore)
    }

    /** The Phase-2 orchestrator: builds one shared context per cycle, fans out serially over the
     *  running set, decodes + guards in Rust, publishes + persists predictions tagged by model_id. */
    val inferenceController: InferenceController by lazy {
        buildInferenceController(
            native = nativeCore,
            dispatchers = dispatchers,
            modelsDir = modelsDir,
            history = RoomBgHistoryProvider(repository, registry),
            // The dedicated `prediction` table is the source of truth for every local reader; the
            // cycle additionally offers each model's forecast to the open stream, which stores none.
            predictionStore = roomPredictionStore,
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
            // The adapter guard's counterfactual stimulus: a real unit of rapid insulin, from the
            // preset the advisor searches against and the writer commits, so the guard's
            // mg/dL-per-unit is the quantity the sensitivity read-out reports and not an impulse
            // nobody receives.
            probeInsulin = ProbeInsulinPort { units, steps ->
                val curve = presetCurve(units, resolveRapidPreset(null))
                DoubleArray(steps) { i -> curve.getOrElse(i) { 0.0 } }
            },
            // Running-set cap: how many discovered models run (and push a prediction) each cycle,
            // read fresh each discovery so a Settings edit takes on the next refresh (mirrors warmup).
            maxRunningProvider = { maxRunningModels() },
            // Per-model forecast-backend preference, re-read fresh for every discovered id (issue 20).
            backendPrefProvider = { id -> forecastBackendPref(id) },
            // Phase 7C: durable cumulative per-model inference telemetry for the Models drill-down.
            telemetryStore = KvTelemetryStore(repository),
            // A fitted adapter reaches the LIVE forecast through here, re-read every cycle so
            // attaching or detaching one takes on the next tick. Deserialization failure ⇒ null ⇒
            // the frozen model, never a half-applied adapter.
            loraStore = LoraStore { modelId ->
                repository.attachedLora(modelId)?.let { row ->
                    nativeCore.loraDeserialize(row.blob)
                        ?: null.also { Timber.w("adapter %d for %s failed to load; running frozen", row.id, modelId) }
                }
            },
            // The classical baseline the neural models are measured against: its fitted weights and
            // band estimator as one kv blob, and the raw curve events it derives causal IOB/COB from
            // (the SAME ChannelBuilder the context channels come from, so both views of the patient's
            // logged doses are built from one set of records).
            baselineStore = KvBaselineStore(repository),
            curveEvents = CurveEventSource { fromMs, toMs -> channelBuilder.eventsIn(fromMs, toMs) },
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
            license = "MIT. Permission is granted, free of charge, to use, copy, modify, merge, publish, " +
                "distribute, sublicense and/or sell copies of this software, provided the copyright " +
                "notice and this permission notice are included. Provided \"as is\", without warranty " +
                "of any kind.",
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
     * The TWO zone lattices the drill-down's error grids paint their regions from — Clarke's and the
     * DTS grid's — both classified by the core. Data-independent: each is a picture of a zone
     * algebra, not of this patient, so one pair serves every model and it is built once, on first
     * open of the drill-down.
     *
     * They exist so no Kotlin has to know a zone boundary, and the two grids make that case
     * differently. Clarke's boundaries are a stack of inequalities in `t1dm-core::accuracy`; the DTS
     * grid's are level sets of a log-ratio, so a renderer outlining them would have to reimplement
     * the function rather than copy four comparisons. Either way the figure paints cells it was
     * handed rather than an outline it derived. Empty on a stub core, which the figures render as no
     * regions rather than as wrong ones.
     *
     * **Suspending, and off-main, like every other native reduction this screen makes.** Each build
     * is a 160-square lattice — 25 600 classifications inside the core, the same number lifted across
     * uniffi and re-mapped on this side, then three verification probes — so the pair is 51 200.
     * Read as a plain property it ran inside the composition that opened the drill-down, the one
     * expensive thing on that screen not moved off the frame, and the transition stuttered once per
     * process. The `lazy` still does the once-only work; this only decides which thread pays for it.
     */
    private val errorGridLatticesOnce: ErrorGridLattices by lazy {
        ErrorGridLattices(
            clarke = ZoneLattice.build(nativeCore::clarkeZoneGrid),
            dts = ZoneLattice.build(nativeCore::dtsZoneGrid),
        )
    }

    suspend fun errorGridLattices(): ErrorGridLattices =
        withContext(dispatchers.default) { errorGridLatticesOnce }

    /**
     * The Trend Accuracy Matrix's rate-bin edges, mg/dL per minute — the crate's, so an axis label
     * cannot come to disagree with the binning it captions. One read per process; empty on a stub
     * core, which the figure renders as unlabelled bins rather than as edges it invented.
     */
    val trendBinEdges: List<Double> by lazy { nativeCore.trendBinEdges() }

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
        val delta = eligibleDelta(calibrations, modelId, horizonSteps, nQuantiles) ?: return null
        return nativeCore.applyQuantileConformal(bandsMgdl, delta)
    }

    /**
     * [calibratedBands] for a whole sweep of ONE model's fans at once — the BG panel's hindsight
     * surface, which draws a day of stored forecasts and would otherwise cross the FFI ~288 times per
     * rebuild. [fansMgdl] is fan-major, `nFans · horizonSteps · nQuantiles`.
     *
     * Eligibility is decided once for the batch, on the same three rules [calibratedBands] applies —
     * so the sweep and the live fan beside it are drawn on the same basis, or neither is.
     */
    fun calibratedFanBatch(
        calibrations: Map<String, BandCalibration>,
        modelId: String,
        fansMgdl: () -> List<Double>,
        horizonSteps: Int,
        nQuantiles: Int,
    ): List<Double>? {
        // Eligibility BEFORE the batch is built: [fansMgdl] flattens a day of stored fans, and on a
        // model with no fitted correction — the common case — that work would be discarded.
        val delta = eligibleDelta(calibrations, modelId, horizonSteps, nQuantiles) ?: return null
        return nativeCore.applyQuantileConformalBatch(fansMgdl(), delta)
    }

    /**
     * [modelId]'s stored delta when it is entitled to be drawn, else null — the eligibility half of
     * the §8.4 apply, held in one place because both applies must answer it identically. A fan
     * corrected on one surface and raw on another is the defect this is factored to prevent.
     */
    private fun eligibleDelta(
        calibrations: Map<String, BandCalibration>,
        modelId: String,
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
        return cal.delta
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
    /**
     * Drop a model's stored §8.4 correction, from the model's own drill-down.
     *
     * By hand rather than by rule, because the case that needs it cannot be detected after the
     * fact: a correction fitted from windows that straddle a CGM source change measures the gap
     * between two sensors, and once the older forecasts have aged out there is nothing left to
     * infer that from. The raw fan is drawn until a refit — never a stale correction kept for
     * want of a better one.
     */
    suspend fun dropBandCalibration(modelId: String) = withContext(dispatchers.io) {
        runCatching { repository.deleteBandCalibration(modelId) }
        Unit
    }

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
        // The bridge's on/off state is read on the CGM hot path from a plain field, so it has to be
        // published once at startup — otherwise mirroring stays off until the settings screen is
        // opened, and a configured bridge would silently skip every reading until then.
        appScope.launch { refreshNightscoutEnabled() }
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

    // ─── Nightscout bridge ────────────────────────────────────────────────────────────────────
    //
    // A SECOND, one-way destination for BG, carbohydrate and bolus, for a third-party logbook that
    // speaks the Nightscout `/api/v1` subset. It shares the durable outbox with T1DMSERVER sync and
    // nothing else: its own base URL, its own `api-secret` credential in the Keystore, and its own
    // failure handling — a bridge that is off, unreachable or rejecting its secret must never stall
    // the patient's own sync. Neither direction of that arrangement is a shared contract, so nothing
    // here belongs in `SPEC/`.

    /** URL + on/off in `kv`, the api-secret in the Keystore beside the `rw` token. */
    val nightscoutConfigStore: NightscoutConfigStore by lazy {
        NightscoutConfigStore(
            getKv = repository::getKv,
            putKv = repository::putKv,
            tokens = tokenStore,
        )
    }

    val nightscoutClient: NightscoutClient by lazy {
        OkHttpNightscoutClient(
            config = { nightscoutConfigStore.current() },
            dispatchers = dispatchers,
        )
    }

    val nightscoutEnqueuer: NightscoutEnqueuer by lazy { NightscoutEnqueuer(repository) }

    /**
     * Publish the bridge's on/off state to the repository, which consults it on the CGM hot path to
     * decide whether an authoritative reading also queues a bridged row. Called at composition and
     * again after every save, so switching the bridge on starts mirroring at the next reading rather
     * than at the next launch.
     */
    suspend fun refreshNightscoutEnabled() {
        repository.nightscoutBridgeEnabled = nightscoutConfigStore.current() != null
    }

    /** Save the bridge configuration and report what the host said to a probe. */
    suspend fun saveNightscoutBridge(url: String, secret: String, enabled: Boolean): String {
        nightscoutConfigStore.save(url, secret, enabled, System.currentTimeMillis())
        refreshNightscoutEnabled()
        return if (enabled) nightscoutClient.probe() else "off"
    }

    suspend fun probeNightscout(): String = nightscoutClient.probe()

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
            nightscout = nightscoutClient,
            trendAt = repository::authoritativeTrendAt,
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
            tombstones = TombstoneReplay { replayTombstones() },
            // NOTE: `replayTombstones` is suspend; TombstoneReplay's method is too.
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
            // Server-bound rows ONLY. The walk infers delivery from the absence of a row as old as
            // its stamp, and a bridge row proves nothing about it — counting one would let an
            // unreachable third party hold the walk open forever, re-enqueuing the whole meal/dose
            // history on every reconnect and never banking the epoch.
            oldestQueuedAtMs = repository::oldestServerBoundOutboxCreatedAt,
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
            resendForecast = { roomPredictionStore.resendLatest() },
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
     * stopping the foreground service or killing the process, so the held GATT session keeps the sensor
     * CONNECTED across the reset (the user's requirement) and the sensor's new readings immediately
     * repopulate the just-wiped `cgm_reading` table. Consequences of keeping the process alive: (a) the
     * `cgm_source` rows are PRESERVED so the active-source binding survives; (b) the in-memory,
     * process-scoped caches that Room-backed flows don't self-heal (the alarm config, snooze state, the
     * inference warmup latch, GMI, the ephemeral bolus/roll state, the published forecast) are returned
     * to first-run here. It (1) drops the in-memory watch session before the wipe; (2) row-wipes every
     * user/runtime table at the current schema version except `cgm_source`, keeping the shipped model
     * artifacts + seed dictionaries (see [T1dmRepository.wipeAllData]) — this also clears the watch
     * pairing/epoch/nonce-ceiling kv rows;
     * (3) burn the secrets that live OUTSIDE Room — the Keystore-wrapped server token(s) and the watch
     * key-wrapping alias (the watch key material itself was a kv blob, already gone in step 2). The CGM
     * sensor-secret alias is deliberately NOT among them, and neither are the `cgm_sensor_secret` rows:
     * this reset keeps the sensor CONNECTED by design, so it is still worn, and for one sensor family
     * those bytes are the only password that could ever unbind it. Destroying them would retire the
     * hardware rather than erase data — releasing a sensor is a per-sensor action, not a side effect of a
     * global reset; (4) reset
     * the process-scoped in-memory caches to first-run. The caller then relaunches the UI IN-PROCESS via
     * [restartApp] (a fresh Activity task, NOT a process kill) so the app-lifetime StateFlows re-emit the
     * empty store while the FGS + sensor connection live on. Off-main.
     */
    suspend fun resetAllData() = withContext(dispatchers.io) {
        // KEEP THE SENSOR CONNECTED across the reset. The connected-GATT session that holds the live
        // sensor is process-scoped (the FGS in this same process owns it), so — unlike the old
        // stop-service + kill-process reset — we deliberately DO NOT stop CgmScanService, do NOT stop the
        // registry, and do NOT kill the process. The sensor stays connected and its new readings
        // repopulate the just-wiped cgm_reading table (the user's explicit requirement). We therefore
        // also PRESERVE the cgm_source rows so the active-source binding survives the wipe.
        //
        // Drop the in-memory watch session + disable the link BEFORE the wipe so no late 5-min push can
        // re-persist key material or a nonce ceiling into the kv rows we are about to clear (which would
        // resurrect the pairing the reset is erasing). Only touch it if the watch was ever wired up.
        runCatching { watchLink.stopForReset() }
        repository.wipeAllData(preserveCgmSources = true)
        runCatching { tokenStore.clearAll() }
        com.t1dm.app.watch.WatchKeyCipher.deleteKey()
        // Return the in-memory, process-scoped caches to first-run WITHOUT a process kill (which would
        // drop the GATT link). The Room-backed StateFlows self-heal from the wiped store; these are the
        // caches that would otherwise show stale data after the in-place relaunch.
        refreshAlarmConfig()          // thresholds → coded defaults, pushed live into the running engine
        runCatching { clearSnooze() }
        runCatching { clearBolusAdvice() }
        runCatching { clearRoll() }
        gmiSnapshot = null
        // The bridge flag is read on the CGM hot path from a plain field, and the reset deliberately
        // keeps the process alive — so without this it stays true after its kv rows and its Keystore
        // secret are gone, and every reading queues a row that can only ever be dropped.
        runCatching { refreshNightscoutEnabled() }
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
     * WITHOUT killing the process, so the foreground service (and the held GATT session that keeps the
     * sensor connected) survive the reset. The app-scoped [AppContainer] is reused; [resetAllData] has
     * already returned its caches to first-run, and every Room-backed StateFlow re-emits the wiped
     * store, so the rebuilt Activity opens at the empty home. (Contrast the old `Runtime.exit(0)`, which
     * dropped the connection.)
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
            // The id is unchanged but the model is not. Everything fitted against the previous
            // artifact goes with it: the adapters (fitted on a head that no longer exists), the
            // fills (that model's own reconstruction), and the cached head itself — a head kept
            // across a replace would be served against the new graph, and the parity check that
            // exists to catch exactly that is memoized per model id.
            runCatching { repository.deleteLorasForModel(modelId) }
            runCatching { repository.clearInfillForModel(modelId) }
            inferenceController.evictHead(modelId)
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
    // ── The Lab: experiments, adapters, gap repair ──────────────────────────────────

    /** Where an exported adapter lands: beside the models, so `adb pull` reaches it. */
    private val adaptersDir: File
        get() = File(appContext.getExternalFilesDir(null), "adapters")

    /** One holder for the three surfaces that share a window of history and a loaded model. */
    val labController: LabController by lazy {
        LabController(
            native = nativeCore,
            controller = inferenceController,
            repository = repository,
            history = RoomBgHistoryProvider(repository, registry),
            channels = { gridStartMs, nSteps -> dashboardCurveChannels(gridStartMs, nSteps) },
            adaptersDir = { adaptersDir },
        )
    }

    private val _loraPanel = MutableStateFlow(LoraPanelState(modelId = ""))
    val loraPanel: StateFlow<LoraPanelState> = _loraPanel.asStateFlow()

    // ─── Reconstruction: the panel's mask, and the Lab's promotion ──────────────────────────

    private val _panelMaskNote = MutableStateFlow<String?>(null)

    /** What the last mask run or promotion said. A refusal has several ordinary causes and a silent
     *  one reads as a control that does nothing. */
    val panelMaskNote: StateFlow<String?> = _panelMaskNote.asStateFlow()

    /**
     * What the SELECTED model's descriptor permits a mask to be, or null when no model with a
     * descriptor is selected — which is what hides the control.
     *
     * Built only from the descriptor. `PROJECTS/T1DMDROID.md`'s rule is that nothing in the app
     * holds a geometry of its own, and every number here would otherwise be a second copy free to
     * disagree with the artifact it describes.
     */
    suspend fun maskControls(): MaskControls? {
        // The SELECTED model, not the Lab's pick. The panel draws the selected model's fan and
        // reconstructs through its geometry, so taking the Lab's would let a span be masked under
        // one descriptor's patch size while the trace beside it came from another's — and, because
        // the Lab's pick is null until the Lab has been opened at least once, the whole affordance
        // simply never appeared on a fresh launch.
        val desc = inferenceController.selectedModelInfo()?.takeIf { it.real }?.descriptor
        val src = repository.authoritativeSourceId() ?: return null
        // A day of slots when there is no model to size the window: enough to place a cut anywhere
        // the panel opens on, and the descriptor's own context length when there is one.
        val span = desc?.let { it.minContextPatches * it.patchSize } ?: CUT_ONLY_CONTEXT_STEPS
        val rows = repository.recentReadings(src, span)
        val newestMeasured = rows.firstOrNull {
            isRealMeasurement(it.provenance, it.flag) && it.bgMgdl != null
        }?.tsMs ?: return null
        // No model, no patch geometry: the selection falls back to the five-minute grid the store
        // keys, which is the shape a cut has anyway. Reconstruction is refused separately, by
        // `fromDescriptor`, rather than by the whole edit mode being absent.
        if (desc == null) {
            return MaskControls(
                patchMs = 300_000L,
                maxSpans = 1,
                maxSpanPatches = CUT_ONLY_CONTEXT_STEPS,
                maxMaskedPatches = CUT_ONLY_CONTEXT_STEPS,
                forecastPatches = 0,
                newestMeasuredMs = newestMeasured,
                contextFloorMs = rows.minOfOrNull { it.tsMs } ?: newestMeasured,
                fromDescriptor = false,
            )
        }
        val patchMs = desc.patchSize.toLong() * 300_000L
        return MaskControls(
            patchMs = patchMs,
            maxSpans = desc.maskMaxSpans,
            maxSpanPatches = desc.maskSpanMax,
            maxMaskedPatches = desc.maxMaskedPatches,
            forecastPatches = desc.predictionHorizonHours * 12 / desc.patchSize,
            newestMeasuredMs = newestMeasured,
            contextFloorMs = rows.minOfOrNull { it.tsMs } ?: newestMeasured,
        )
    }

    /** Reconstructions over the window the panel has loaded. */
    fun panelReconstructed(fromMs: Long, toMs: Long): Flow<List<ReconstructedBg>> =
        repository.observeReconstructed(fromMs, toMs)

    /**
     * Reconstruct the selected stretch. The geometry came from where it sits, never from a control.
     *
     * [selection] is the stretch the edit bar is aimed at, and the geometry the caller derived from
     * where it sits. One selection at a time, deliberately: a second highlight that stayed on screen
     * while a tool fired at the first is a control aimed at something other than what it shows.
     */
    fun runPanelMask(selection: MaskSelection, geometry: MaskGeometry) {
        // The SAME model [maskControls] took the geometry from — the selected one. Reconstructing
        // through the Lab's pick would decode the span under a descriptor the drag was never
        // bounded by, and on a fresh launch there is no Lab pick at all.
        val modelId = inferenceController.selectedModelInfo()?.takeIf { it.real }?.id ?: run {
            _panelMaskNote.value = "No model selected"
            return
        }
        appScope.launch {
            _panelMaskNote.value = "Reconstructing…"
            val note = runCatching {
                labController.runSpan(modelId, selection.startMs, selection.endMs, geometry)
            }.getOrElse { it.message ?: "Reconstruction failed" }
            // Undoable only when a span actually landed. A forecast writes nothing, and a refusal
            // that pushed an undo entry would put a button on screen that took back somebody else's
            // edit — the one thing an undo stack must never do.
            if (geometry != MaskGeometry.FORECAST &&
                repository.reconstructedSpanSize(selection.startMs) > 0
            ) {
                pushBgEdit(BgEdit.Fill(selection.startMs))
            }
            _panelMaskNote.value = note
        }
    }

    // ─── The BG panel's edit stack ──────────────────────────────────────────────────────────
    //
    // Held HERE rather than in the composable, for the reason the fit's progress is: an edit
    // outlives the screen that made it, and an undo that vanished on a navigation would be a trap
    // rather than a convenience. In memory and session-scoped on purpose — a cut is pushed to the
    // server as it is made, and a stack that survived a process death would offer to undo something
    // the record has long since agreed about.

    private sealed interface BgEdit {
        /** The rows a cut took, everything an undo needs to put them back. */
        data class Cut(val rows: List<BgCut>) : BgEdit

        /** A span a fill drew. Undoing it discards the span; a promoted one refuses. */
        data class Fill(val spanStartMs: Long) : BgEdit
    }

    private val bgEdits = ArrayDeque<BgEdit>()
    private val _bgEditDepth = MutableStateFlow(0)

    /** How many panel edits can still be taken back. */
    val bgEditDepth: StateFlow<Int> = _bgEditDepth.asStateFlow()

    private fun pushBgEdit(edit: BgEdit) {
        bgEdits.addLast(edit)
        // Bounded: the stack holds the geometry of every cut it can undo, and an unbounded one on a
        // panel where a drag is cheap is a slow leak with no ceiling.
        while (bgEdits.size > BG_EDIT_UNDO_MAX) bgEdits.removeFirst()
        _bgEditDepth.value = bgEdits.size
    }

    /**
     * Erase every BG on the grid in `[fromMs, toMs]` — the patient's own correction of a compression
     * low or a stretch of nonsense, and the only route by which stored physiologic values leave the
     * record.
     *
     * Both ends are snapped here rather than trusted: the panel's selection is a pixel range
     * resolved to instants, and the repository requires grid slots because that is what the server
     * keys reconstruction on.
     */
    fun cutBgRange(fromMs: Long, toMs: Long) {
        appScope.launch {
            val from = T1dmRepository.snapToGrid(fromMs)
            val to = T1dmRepository.snapToGrid(toMs)
            if (to < from) return@launch
            val cut = runCatching { repository.cutBgRange(from, to, System.currentTimeMillis()) }
                .getOrElse {
                    Timber.tag("AppContainer").w(it, "BG cut failed")
                    _panelMaskNote.value = it.message ?: "Cut failed"
                    return@launch
                }
            if (cut.isEmpty()) {
                _panelMaskNote.value = "Nothing stored there"
                return@launch
            }
            pushBgEdit(BgEdit.Cut(cut))
            _panelMaskNote.value = "Cut ${cut.size} readings"
        }
    }

    /** Take back the last cut or fill. */
    fun undoBgEdit() {
        appScope.launch {
            val edit = bgEdits.removeLastOrNull() ?: return@launch
            _bgEditDepth.value = bgEdits.size
            _panelMaskNote.value = when (edit) {
                is BgEdit.Cut -> runCatching {
                    repository.restoreBgCut(edit.rows, System.currentTimeMillis())
                    "Restored ${edit.rows.size} readings"
                }.getOrElse { it.message ?: "Undo failed" }

                is BgEdit.Fill -> runCatching {
                    when {
                        repository.discardInfillSpan(edit.spanStartMs) -> "Fill removed"
                        // Two ways an entry outlives what it points at, and they need different
                        // words: a promotion is reversible by demoting, and a span a later cut
                        // already dropped is simply not there. A single message for both sends the
                        // user looking for a Demote button on a span that no longer exists.
                        repository.infillSpan(edit.spanStartMs).isEmpty() -> "That fill is already gone"
                        else -> "Fill was promoted — demote it first"
                    }
                }.getOrElse { it.message ?: "Undo failed" }
            }
        }
    }

    /** Throw a drawn span away. */
    fun discardSpan(spanStartMs: Long) {
        appScope.launch {
            _panelMaskNote.value = runCatching { labController.discard(spanStartMs) }
                .getOrElse { it.message ?: "Discard failed" }
        }
    }

    /** Move a drawn span's line to the fan's τ-th quantile, and store the level it landed on. */
    fun retauSpan(spanStartMs: Long, tau: Double) {
        appScope.launch {
            _panelMaskNote.value = runCatching { labController.retau(spanStartMs, tau) }
                .getOrElse { it.message ?: "Could not move the line" }
            // The stored line and the preview now agree, so the preview has nothing left to say.
            _tauPreview.value = null
        }
    }

    private val _tauPreview = MutableStateFlow<SpanLinePreview?>(null)

    /** The line the τ slider is currently over, before it is committed. Drawing only. */
    val tauPreview: StateFlow<SpanLinePreview?> = _tauPreview.asStateFlow()

    /**
     * Read a span's fan at [tau] and publish the line WITHOUT storing it.
     *
     * Its own job, cancelled by the next tick: a slider emits faster than a fan can be read and
     * decoded, and letting them queue would run the thumb's whole travel one position behind.
     */
    private var tauPreviewJob: Job? = null

    fun previewTau(spanStartMs: Long, tau: Double) {
        tauPreviewJob?.cancel()
        tauPreviewJob = appScope.launch {
            val preview = runCatching { labController.previewTau(spanStartMs, tau) }.getOrNull()
            if (preview != null) _tauPreview.value = preview
        }
    }

    fun clearPanelMask() {
        _panelMaskNote.value = null
    }

    fun promoteSpan(spanStartMs: Long) {
        appScope.launch {
            _panelMaskNote.value =
                runCatching { labController.promote(spanStartMs) }
                    .getOrElse { it.message ?: "Promotion failed" }
        }
    }

    fun demoteSpan(spanStartMs: Long) {
        appScope.launch {
            _panelMaskNote.value =
                runCatching { labController.demote(spanStartMs) }
                    .getOrElse { it.message ?: "Demotion failed" }
        }
    }

    suspend fun refreshLoraPanel(modelId: String) {
        val desc = inferenceController.descriptorOf(modelId)
        val unavailable = when {
            desc == null -> "Model not loaded"
            desc.head == null -> "This model ships no head file — nothing to adapt"
            else -> (inferenceController.headState(modelId) as? HeadCache.State.Unusable)?.why
        }
        // The panel lists THIS model's adapters, read here rather than borrowed from the Lab: the
        // Lab holds whichever model it has picked, and opening the panel for another one would
        // otherwise show an empty list.
        val adapters = runCatching { labController.adaptersOf(modelId) }.getOrElse { emptyList() }
        _loraPanel.value = LoraPanelState(modelId = modelId, unavailable = unavailable, adapters = adapters)
    }

    /**
     * Fit an adapter. Runs in [appScope], NOT the caller's: a fit is a few hundred forwards plus
     * the training loop, and hanging it off a screen's own scope cancels it the moment the user
     * navigates away — half-way through, with nothing stored and nothing said.
     */
    fun fitAdapter(modelId: String, spec: LoraFitSpec) {
        if (_loraPanel.value.busy) return
        // The progress lives in the panel's own StateFlow, not in the screen: the fit runs in
        // appScope and survives navigation, so leaving and coming back re-attaches to the running
        // fit instead of showing an idle panel.
        _loraPanel.update {
            it.copy(
                progress = LoraFitProgress(LoraFitProgress.Phase.Replay, 0, 0),
                error = null,
                lastReport = null,
            )
        }
        appScope.launch {
            val note = runCatching {
                labController.fit(
                    modelId,
                    spec,
                    onReplay = { done, total ->
                        _loraPanel.update {
                            it.copy(progress = LoraFitProgress(LoraFitProgress.Phase.Replay, done, total))
                        }
                    },
                    onEpoch = { epoch, epochs ->
                        _loraPanel.update {
                            it.copy(progress = LoraFitProgress(LoraFitProgress.Phase.Train, epoch, epochs))
                        }
                    },
                )
            }.getOrElse {
                Timber.w(it, "adapter fit failed for %s", modelId)
                _loraPanel.update { s -> s.copy(progress = null, error = it.message ?: "Fit failed") }
                return@launch
            }
            val adapters = runCatching { labController.adaptersOf(modelId) }.getOrElse { emptyList() }
            _loraPanel.update { it.copy(progress = null, lastReport = note, adapters = adapters) }
        }
    }

    /**
     * Attaching or detaching an adapter changes what the model IS, so the correction and the stored
     * forecasts fitted against the previous one go with it, and the next cycle runs immediately —
     * the standing forecast on screen was made by the forecaster that just stopped existing.
     */
    suspend fun attachAdapter(modelId: String, adapterId: Long) {
        // A refusal is a RESULT, not an exception: the guard blocking an adapter is an ordinary
        // outcome the panel has to state, and the reason it gives is the guard's own numbers.
        runCatching { labController.attach(modelId, adapterId) }
            .onSuccess { refusal ->
                if (refusal != null) {
                    _loraPanel.update { s -> s.copy(error = refusal) }
                } else {
                    // Only when something actually changed. A refused attach leaves the model exactly
                    // as it was, and forcing a cycle for it spends a full forward and republishes the
                    // fan the panel is already showing.
                    reevaluateInferenceNow()
                }
            }
            .onFailure { _loraPanel.update { s -> s.copy(error = it.message ?: "Attach failed") } }
        refreshLoraPanel(modelId)
    }

    /** The deliberate second action that clears a guard refusal for one adapter. The typed name is
     *  compared by the controller, not by the dialog that collected it. */
    suspend fun overrideAdapterGuard(modelId: String, adapterId: Long, typedName: String) {
        runCatching { labController.overrideGuard(modelId, adapterId, typedName) }
            .onSuccess { refusal ->
                if (refusal != null) _loraPanel.update { s -> s.copy(error = refusal) }
            }
            .onFailure { _loraPanel.update { s -> s.copy(error = it.message ?: "Override failed") } }
        refreshLoraPanel(modelId)
    }

    /**
     * Measure a stored adapter against the model's dose response.
     *
     * In [appScope] for the reason a fit is: the replay is a few hundred forwards and a screen's
     * own scope cancels it the moment the user navigates away.
     */
    fun probeAdapter(modelId: String, adapterId: Long) {
        if (_loraPanel.value.busy) return
        _loraPanel.update {
            it.copy(
                progress = LoraFitProgress(LoraFitProgress.Phase.Replay, 0, 0),
                error = null,
                lastReport = null,
            )
        }
        appScope.launch {
            val note = runCatching {
                labController.probe(modelId, adapterId) { done, total ->
                    _loraPanel.update {
                        it.copy(progress = LoraFitProgress(LoraFitProgress.Phase.Replay, done, total))
                    }
                }
            }.getOrElse { it.message ?: "Probe failed" }
            _loraPanel.update { it.copy(progress = null, lastReport = note) }
            refreshLoraPanel(modelId)
        }
    }

    suspend fun detachAdapter(modelId: String) {
        runCatching { labController.detach(modelId) }
            .onFailure { _loraPanel.update { s -> s.copy(error = it.message ?: "Detach failed") } }
        refreshLoraPanel(modelId)
        reevaluateInferenceNow()
    }

    suspend fun exportAdapter(adapterId: Long) {
        val note = runCatching { labController.export(adapterId) }.getOrElse { it.message ?: "Export failed" }
        _loraPanel.update { it.copy(lastReport = note) }
    }

    suspend fun importAdapters(modelId: String) {
        val note = runCatching { labController.import(modelId) }.getOrElse { it.message ?: "Import failed" }
        val adapters = runCatching { labController.adaptersOf(modelId) }.getOrElse { emptyList() }
        _loraPanel.update { it.copy(lastReport = note, adapters = adapters) }
    }

    suspend fun removeModel(modelId: String) {
        runCatching { inferenceController.deleteModel(modelId) }
        withContext(dispatchers.io) {
            runCatching { repository.deletePredictionsForModel(modelId) }
            // The correction was fitted on THIS model's forecasts and means nothing without them.
            runCatching { repository.deleteBandCalibration(modelId) }
            // An adapter outlives nothing it was fitted on, and a fill is that model's own guess.
            runCatching { repository.deleteLorasForModel(modelId) }
            runCatching { repository.clearInfillForModel(modelId) }
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
    val channelBuilder: ChannelBuilder by lazy {
        ChannelBuilder(curveEngine, doseStore, ExerciseChannelSource(::exerciseChannel))
    }

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

    /** Seed the bundled glycemic dictionary + the three insulin presets once, off-main (idempotent),
     *  and settle any exercise bout the last process died in the middle of. */
    fun startBuilders() {
        appScope.launch {
            mealsController.seedIfEmpty()
            insulinController.seedBuiltinsIfEmpty()
            exerciseController.reconcileOpenSessions(System.currentTimeMillis())
        }
    }

    // ─── Exercise ─────────────────────────────────────────────────────────────────────────────
    //
    // A logged bout is not a new physiologic concept: its per-5-minute magnitude belongs in the wide
    // sample's existing `exercise` scalar, beside bg/hr/steps/sleep/mood, on the ingest row that
    // already syncs. The bout row and its GPS track are phone-local and cross no wire.

    /** The bout store: the sessions Flow, the start/stop writers, the track, and the launch-time
     *  reconcile that closes a bout the process died inside. */
    val exerciseController: ExerciseController by lazy {
        ExerciseController(
            repository,
            dispatchers,
            curveEngine,
            carbEquivPerMin = { settingsStore.currentCarbEquivPerMin() },
        )
    }

    /** The bout being recorded now, published by [com.t1dm.sensors.ExerciseRecorder] inside
     *  [com.t1dm.app.service.ExerciseService] and read by the panel. Null whenever none is running. */
    val activeExercise = MutableStateFlow<ActiveExercise?>(null)

    /** Why no bout could START — a location permission the user declined. Set by the service on the
     *  path where it stops itself, so the panel can say why nothing happened; a bout that IS running
     *  carries its own reason on [ActiveExercise.degraded] instead. */
    val exerciseRefusal = MutableStateFlow<String?>(null)

    /**
     * The one degraded-exercise reason to render, whichever half produced it: a bout's own
     * [ActiveExercise.degraded] while one is recording, the refusal that stopped the service
     * otherwise. Derived rather than stored, so the two can never disagree about which is current.
     *
     * `container.serviceRunning` has no consumer and `MainActivity`'s permission callback writes only
     * a Timber line — copying that silence here would give this feature its worst failure mode: a
     * bout that records nothing and reads as a walk that went nowhere.
     */
    val exerciseDegraded: StateFlow<String?> =
        combine(activeExercise, exerciseRefusal) { active, refusal -> active?.degraded ?: refusal }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)

    private val exerciseSource by lazy {
        AppExerciseSource(
            controller = exerciseController,
            settings = settingsStore,
            active = activeExercise,
            onStart = { kind -> ExerciseService.start(appContext, kind) },
            onStop = { ExerciseService.stop(appContext) },
        )
    }

    /** The `:feature:exercise` port: recorded bouts, the running one, and the body mass its energy
     *  figure cannot be computed without. */
    val exercise: ExerciseSource get() = exerciseSource

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
    suspend fun dashboardCurveChannels(gridStartMs: Long, nSteps: Int): ModelChannels {
        val ch = channelBuilder.contextChannels(gridStartMs, nSteps)
        return ModelChannels(ch.carb, ch.insulin, ch.exercise)
    }

    /**
     * The model's exercise channel over a grid window: grams of carbohydrate equivalent disposed
     * per bucket, read straight from the `sample` table's own column. Bound into the
     * [ChannelBuilder] so every consumer of the model's channels gets it from one place.
     *
     * It is NOT reconstructed from the bout records here, and must not be. The disposal curve was
     * resolved once when the bout was recorded — against the patient's carbohydrate-equivalent rate
     * AS IT THEN STOOD (Settings → Curves) — and laid on the grid from that moment forward. Rebuilding
     * it now would silently re-rate every past bout at today's setting, so what the model reads for
     * last week would change every time the slider moved.
     */
    suspend fun exerciseChannel(gridStartMs: Long, nSteps: Int): DoubleArray {
        val out = DoubleArray(nSteps)
        if (nSteps <= 0) return out
        val endMs = gridStartMs + (nSteps - 1).toLong() * CurveEngine.STEP_MS
        val rows = runCatching { repository.samplesInRange(gridStartMs, endMs) }.getOrElse {
            Timber.w(it, "exercise channel read failed; the model sees no disposal")
            return out
        }
        for (r in rows) {
            val g = r.exercise ?: continue
            val i = ((r.ts - gridStartMs) / CurveEngine.STEP_MS).toInt()
            if (i in 0 until nSteps && g.isFinite() && g > 0.0) out[i] = g
        }
        return out
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
     * curve channels. And the read is sparse: only buckets the pedometer actually recorded have rows
     * at all, so scattering a short result into a pre-filled array costs less than making SQL emit a
     * value per bucket, and the panel gets the dense array its frame builder wants either way.
     */
    suspend fun dashboardStepSeries(gridStartMs: Long, nSteps: Int): IntArray {
        if (nSteps <= 0) return IntArray(0)
        val step = T1dmRepository.GRID_MS
        // Pre-filled with the NOT-MEASURED sentinel rather than with zeros. A bucket the query
        // returns no row for was never watched — no step sensor, no permission, or the service was
        // down — and zero-filling it would have the read-out report that the patient was still
        // through it. Only buckets a row came back for are overwritten, so a recorded 0 stays one.
        val out = IntArray(nSteps) { StepsFrame.NO_DATA }
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
    suspend fun dashboardFutureChannels(rollStartMs: Long, nFutureSteps: Int): ModelChannels {
        val fc = channelBuilder.futureOverrides(rollStartMs, nFutureSteps, announced = emptyList(), candidate = null)
        // A bout that ended minutes ago is still disposing glucose across the horizon, and the
        // writer already laid those slots down when it recorded the curve — so the committed
        // future is a read of the same column, not a projection.
        return ModelChannels(fc.carb, fc.insulin, fc.exercise)
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
    /**
     * Fit the classical baseline, held to the SAME calibration threshold as the neural §8.4
     * correction — one policy constant, not two. If anything, this band deserves the stricter of the
     * two: the neural delta only tints the graph, while this one IS the baseline's interval and
     * decides whether its forecast clears the collapsed-band guard at all.
     */
    suspend fun fitBaseline() =
        inferenceController.fitBaseline(System.currentTimeMillis(), CONFORMAL_MIN_CAL_WINDOWS)

    private val selectedModelProvider = SelectedModelProvider {
        val info = inferenceController.authorityModelInfo()
        if (info == null || !info.real) null
        else object : SelectedModelHandle {
            override val descriptor = info.descriptor
            override val backendInfo = calcBackendInfo(info)
            override suspend fun run(input: GraphTensors): com.t1dm.inference.backend.GraphOutput =
                inferenceController.runSelectedAuthority(input)

            override suspend fun adapt(
                out: com.t1dm.inference.backend.GraphOutput,
                mSlots: Int,
            ): List<Double>? = inferenceController.adaptedHeadRawFor(info.id, out, mSlots)
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
        SensitivityProbe(
            rollingForecaster,
            bolusResolver,
            probeCarbResolver,
            selectedModelId = { inferenceController.authorityModelInfo()?.takeIf { it.real }?.id },
        )
    }

    /** §3.6-D anchor facts from the authoritative source's recent grid readings (fail-closed: null ⇒ no signal). */
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
        /** A finished recommendation. */
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
            .getOrElse { AdviceResult.Refused(listOf("Calculator error — ${it.message ?: it::class.simpleName}")) }
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
        val srcId = repository.authoritativeSourceId() ?: return null
        val recent = repository.recentReadings(srcId, 36) // ~3 h of 5-min grid context
        if (recent.isEmpty()) return null
        val lastMeasured = recent
            .filter { isRealMeasurement(it.provenance, it.flag) && it.bgMgdl != null }
            .maxByOrNull { it.tsMs }
        // `newest` stays the newest row OUTRIGHT, and that is deliberate. Filtering it through
        // `isRealMeasurement` would make `warmup` constant-false whenever the window holds one
        // NORMAL measured row, and the card would report a warming-up sensor as a settled one.
        val newest = recent.maxByOrNull { it.tsMs }!!
        // A promoted RECONSTRUCTION counts as fabricated context, like an interpolation and a
        // warm-up row. Nothing gates on the fraction — these three fields are the §3.6-F card's
        // anchor disclosure and only that.
        val fabricated = recent.count {
            it.provenance == ReadingProvenance.INTERPOLATED ||
                it.provenance == ReadingProvenance.RECONSTRUCTED ||
                it.flag == ReadingFlag.WARMUP
        }
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
        mirrorToNightscout { nightscoutEnqueuer.enqueueMeal(meal, now, holdMs = pushHoldMs()) }
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
        mirrorToNightscout { nightscoutEnqueuer.enqueueMeal(meal, now, holdMs = pushHoldMs()) }
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
        mirrorToNightscout { nightscoutEnqueuer.enqueueDose(dose, now, holdMs = pushHoldMs()) }
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
        mirrorToNightscout { nightscoutEnqueuer.enqueueDose(dose, now, holdMs = pushHoldMs()) }
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
        mirrorToNightscout { nightscoutEnqueuer.enqueueDose(dose, now, holdMs = pushHoldMs()) }
        // The re-run is owed to the ROW, not the push: the forecast reads the `logged_dose` through
        // ChannelBuilder and never the queue, so this path would need it even if it enqueued nothing.
        reforecastAfterCurveWrite()
        val kind = if (type.kind == InsulinKind.BOLUS) "bolus" else "basal"
        return dose.handle(outboxId, "${fmtAmount(units)} U $kind · ${type.name}")
    }

    /**
     * Take back a just-logged meal/dose: one repository transaction writes the tombstone and removes
     * the event, and `T1dmRepository.logEvents` — the only trigger an event delete can fire, since
     * it touches neither `cgm_reading` nor `sample` — is bumped, which is what repaints [iobCob]
     * and, through it, the dashboard's curve overlay and the §3.6-F IOB provenance line.
     *
     * There is nothing left to report. The deletion travels as a tombstone on the same upsert the
     * create rode, ordered against it by `updated_at`, so it lands whatever the push had already
     * done — the old "the server has it and cannot be told otherwise" outcome no longer exists.
     *
     * The carb/insulin channel has moved either way, so the forecast is re-run.
     */
    suspend fun undoLog(handle: LogHandle) {
        when (handle.kind) {
            LoggedEventKind.MEAL -> tombstoneAndPushMeal(handle.rowId)
            LoggedEventKind.DOSE -> tombstoneAndPushDose(handle.rowId)
        }
        reforecastAfterCurveWrite()
    }

    /**
     * Delete a logged meal and file its deletion: one transaction writes the tombstone and removes
     * the event, then the push goes out under the SAME dedup key the create used.
     *
     * The tombstone is marked pushed only after the enqueue returns, so a process death between the
     * two leaves it for [replayTombstones] to find on the next connect.
     */
    private suspend fun tombstoneAndPushMeal(rowId: Long) {
        val now = System.currentTimeMillis()
        val tomb = repository.tombstoneLoggedMeal(rowId, now) ?: return
        outboxEnqueuer.enqueueMealTombstone(tomb.toMealTombstoneDto(), now)
        repository.markTombstonePushed(tomb.clientId, now)
    }

    /** The dose twin of [tombstoneAndPushMeal]. */
    private suspend fun tombstoneAndPushDose(rowId: Long) {
        val now = System.currentTimeMillis()
        val tomb = repository.tombstoneLoggedDose(rowId, now) ?: return
        outboxEnqueuer.enqueueDoseTombstone(tomb.toDoseTombstoneDto(), now)
        repository.markTombstonePushed(tomb.clientId, now)
    }

    /**
     * Re-file the push for every deletion that has not got one — the connect-time replay.
     *
     * Covers a process death between the delete transaction and the enqueue, and a tombstone the
     * queue's size cap evicted. Returns how many were re-filed.
     */
    private suspend fun replayTombstones(): Int {
        val now = System.currentTimeMillis()
        var n = 0
        for (tomb in repository.unpushedTombstones()) {
            when (tomb.kind) {
                CurveKind.CARB -> outboxEnqueuer.enqueueMealTombstone(tomb.toMealTombstoneDto(), now)
                CurveKind.INSULIN -> outboxEnqueuer.enqueueDoseTombstone(tomb.toDoseTombstoneDto(), now)
            }
            repository.markTombstonePushed(tomb.clientId, now)
            n++
        }
        return n
    }

    /**
     * Queue a bridged mirror of an event just logged, if the bridge is on.
     *
     * Gated and swallowing, deliberately. By the time this runs the clinical record is committed and
     * its own push is queued; a third-party mirror is the least important thing in the sequence, and
     * nothing about it may propagate into the receipt the caller is about to hand the user.
     */
    private suspend fun mirrorToNightscout(enqueue: suspend () -> Long) {
        if (!repository.nightscoutBridgeEnabled) return
        runCatching { enqueue() }
            .onFailure { Timber.tag("Nightscout").w(it, "mirror enqueue failed") }
    }

    /**
     * Re-mirror an edited meal or dose, and ONLY where the original mirror can still be recalled.
     *
     * The server push supersedes under its own key, so `T1DMSERVER` always ends up with the corrected
     * event. The bridge has no such mechanism: a treatment carries its amount frozen into the queued
     * payload, `/api/v1` offers no update for one that has landed, and the bridged `created_at`
     * derives from `updatedAt` — which an edit bumps. Re-sending a landed treatment would therefore
     * file a SECOND one beside it and double-count the insulin in someone's logbook.
     *
     * So the withdrawal is the gate, exactly as it is on the delete path. Withdrawn: the host never
     * saw the old amount and gets the new one. Not withdrawn: it has gone or is going, the old amount
     * stands, and that is said out loud rather than papered over with a duplicate.
     *
     * The withdrawal runs whether or not the bridge is currently on. A queued mirror of an event that
     * has since been edited is wrong whatever happens to it next.
     */
    private suspend fun remirrorEditedTreatment(clientId: String, enqueue: suspend () -> Long) {
        val withdrawn = runCatching { repository.withdrawEditedBridgedTreatment(clientId) }
            .onFailure { Timber.tag("Nightscout").w(it, "withdrawal of an edited mirror failed") }
            .getOrDefault(false)
        if (!repository.nightscoutBridgeEnabled) return
        if (!withdrawn) {
            Timber.tag("Nightscout").w("an edited event's mirror was not recallable; the host keeps what it has")
            return
        }
        runCatching { enqueue() }
            .onFailure { Timber.tag("Nightscout").w(it, "mirror re-enqueue failed") }
    }

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
     * **There is no queue join any more.** The feed used to be held against the outbox to say
     * whether a row had reached the server, and that verdict decided whether Delete was offered.
     * Neither survives: a deletion is now ordered against the create it removes, so it lands
     * whatever the queue has done, and the claim itself was never provable — the outbox has no SENT
     * state, so an absent row means "sent", "rejected" and "evicted" alike. What each row does carry
     * is whether it has been EDITED, which is a fact rather than an inference.
     */
    val loggedEntries: Flow<List<LoggedEntry>> = combine(
        repository.observeRecentLoggedMeals(LOG_FEED_LIMIT),
        repository.observeRecentLoggedDoses(LOG_FEED_LIMIT),
    ) { meals, doses ->
        val rows = meals.map { it.toLoggedEntry() } + doses.map { it.toLoggedEntry() }
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
     * Remove a logged entry the user has decided against. Unconditional: the same tombstone path the
     * undo takes, with no refusal branch left to have.
     */
    suspend fun deleteLoggedEntry(entry: LoggedEntry) {
        when (entry.kind) {
            CurveKind.CARB -> tombstoneAndPushMeal(entry.rowId)
            CurveKind.INSULIN -> tombstoneAndPushDose(entry.rowId)
        }
        reforecastAfterCurveWrite()
    }

    /**
     * Rewrite a logged meal, keeping its identity, and re-push it under the same key.
     *
     * The shape params are re-resolved from the edited GI exactly as the logging path resolves them,
     * and the stored appearance curve is rescaled to the edited grams by the repository writer, so
     * an edited meal reconstructs through the shape a freshly logged one would. The push supersedes
     * whatever is queued under that key — a create still pending, or an earlier edit.
     */
    suspend fun editLoggedMeal(entry: LoggedEntry, grams: Double, gi: Double?, tsMs: Long) {
        val now = System.currentTimeMillis()
        val old = repository.loggedMealById(entry.rowId) ?: return
        val shape = gi?.let { GiToGamma.paramsForGi(it) }
        val edited = repository.editLoggedMeal(
            old.copy(
                tsMs = tsMs,
                grams = grams,
                gi = gi,
                k = shape?.k ?: old.k,
                theta = shape?.theta ?: old.theta,
                durationMin = shape?.durationMin ?: old.durationMin,
            ),
            now,
        ) ?: return
        outboxEnqueuer.enqueueMeal(edited.toMealEventDto(), now)
        remirrorEditedTreatment(edited.clientId) {
            nightscoutEnqueuer.enqueueMeal(edited, now, holdMs = pushHoldMs())
        }
        reforecastAfterCurveWrite()
    }

    /**
     * The dose twin of [editLoggedMeal].
     *
     * [requireLoggableDose] guards the edit as it guards a write: the action curve scales linearly
     * with the amount, so a non-finite one enters IOB as NaN, where it defeats the ceiling rail
     * outright — every comparison against NaN being false.
     */
    suspend fun editLoggedDose(entry: LoggedEntry, units: Double, tsMs: Long) {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val old = repository.loggedDoseById(entry.rowId) ?: return
        val edited = repository.editLoggedDose(old.copy(tsMs = tsMs, units = units), now) ?: return
        outboxEnqueuer.enqueueDose(edited.toDoseEventDto(), now)
        remirrorEditedTreatment(edited.clientId) {
            nightscoutEnqueuer.enqueueDose(edited, now, holdMs = pushHoldMs())
        }
        reforecastAfterCurveWrite()
    }

    /** The withdrawal window, in minutes, and its writer — the Logs panel's own knob. */
    val pushHoldMin: Flow<Int> get() = settingsStore.pushHoldMin

    suspend fun setPushHoldMin(minutes: Int) = settingsStore.setPushHoldMin(minutes)

    private fun LoggedMealEntity.toLoggedEntry() = LoggedEntry(
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
        updatedAtMs = updatedAt,
        mutatedAtMs = mutatedAtMs,
    )

    private fun LoggedDoseEntity.toLoggedEntry() = LoggedEntry(
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
        updatedAtMs = updatedAt,
        mutatedAtMs = mutatedAtMs,
    )

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

    val authoritativeSource: Flow<CgmSourceDescriptor?> = repository.observeAuthoritativeSource()

    val allSources: Flow<List<CgmSourceDescriptor>> = repository.observeSources()

    /**
     * The sensor the BG panel is LOOKING at, when the user has stepped off the authoritative one.
     * Null means "whichever is authoritative", which is why the cycle passes through it rather than
     * around it.
     *
     * In memory and deliberately not persisted: looking at a non-authoritative sensor withholds the
     * forecast and marks the panel view-only, and a mode that withholds the forecast must not survive
     * a restart silently. A cold start always looks at the sensor being believed.
     */
    private val viewedSourceId = MutableStateFlow<com.t1dm.core.model.CgmSourceId?>(null)

    /**
     * The descriptor the BG panel draws: the viewed source when one is chosen and still active, else
     * the authoritative one.
     *
     * The `active` re-check is what makes a stale choice self-correcting — a sensor deactivated or
     * removed while being looked at falls back to the authoritative one on the next emission rather
     * than leaving the panel on a source nothing is reading.
     */
    val viewedSource: Flow<CgmSourceDescriptor?> =
        combine(viewedSourceId, authoritativeSource, repository.observeActiveSources()) { viewed, auth, active ->
            viewed?.let { id -> active.firstOrNull { it.id == id } } ?: auth
        }.distinctUntilChanged()

    /**
     * The viewed sensor's id, bare — the key the BG panel's chart dissolves across when the bottom bar
     * steps to another sensor.
     *
     * The id rather than the descriptor: a descriptor re-emits whenever any of its fields is rewritten
     * (a retuned warm-up window, a hide), and each of those would dissolve a chart that is still
     * drawing the same sensor.
     */
    val viewedSourceKey: Flow<String?> =
        viewedSource.map { it?.id?.value }.distinctUntilChanged()

    /**
     * True while the panel is looking at a sensor that is not the one being believed. The forecast
     * overlay, the hindsight sweep and the rolled fan are all withheld then — none of them describes
     * this sensor, because none of them was computed from it.
     *
     * Derived from [viewedSource] rather than from [viewedSourceId], so it inherits that flow's
     * fall-back to the authoritative source. Read off the raw id it would stay true forever once the
     * viewed sensor was deactivated or removed: the id lingers, the panel correctly falls back to
     * drawing the authoritative trace, and the forecast would be withheld from it with nothing on
     * screen explaining why and no way to clear it short of restarting the app.
     */
    val viewingNonAuthoritative: Flow<Boolean> =
        combine(viewedSource, authoritativeSource) { viewed, auth ->
            viewed != null && auth != null && viewed.id != auth.id
        }.distinctUntilChanged()

    /**
     * What to call the VIEWED sensor in the bottom bar, with the sensor-name privacy setting applied.
     *
     * Resolved here rather than in the composable, deliberately. That chip sits in the chrome of every
     * screen in the app and recomposes on every reading and every clock tick, and `shortName` does string
     * work per call — so the label is computed when the sensor or the setting changes and at no other time.
     *
     * With names hidden this is the sensor's persisted ordinal and nothing else. One family builds its
     * advertised name out of the number printed on the sensor, so a photograph of any screen carrying that
     * name carries the serial with it.
     */
    val viewedSourceLabel: Flow<String?> =
        combine(viewedSource, settingsStore.showSensorNames) { d, showNames ->
            d?.incidentalName(showNames)
        }.distinctUntilChanged()

    /** The same, for the believed sensor, where the CGM settings read-out names it. */
    val authoritativeSourceLabel: Flow<String?> =
        combine(authoritativeSource, settingsStore.showSensorNames) { d, showNames ->
            d?.incidentalName(showNames)
        }.distinctUntilChanged()

    /**
     * Bottom-bar tap: step to the next ACTIVE sensor, in the order the phone met them, wrapping
     * through the authoritative one.
     *
     * Read straight off the registry's own StateFlows rather than collected: this runs on a tap, needs
     * the set as it is at that instant, and a suspend point here would put a frame between the tap and
     * the trace changing. Fewer than two active sensors means there is nothing to step to, and the
     * view resets rather than no-ops — that is the state where a stale choice would otherwise strand
     * the panel off the authoritative source with no way back.
     */
    fun cycleViewedSource() {
        val order = registry.sources.value.map { it.id }.filter { it in registry.activeIds.value }
        if (order.size < 2) {
            viewedSourceId.value = null
            return
        }
        val authoritativeId = registry.authoritative.value
        val current = viewedSourceId.value ?: authoritativeId
        val next = order[(order.indexOf(current) + 1).mod(order.size)]
        // Null rather than the id itself when the step lands back on the believed sensor, so the two
        // ways of saying "looking at the authoritative one" never both exist.
        viewedSourceId.value = if (next == authoritativeId) null else next
    }

    /**
     * How far back the BG panel has loaded, as an absolute instant. Moves BACKWARDS only, and only
     * when the user pans near the edge of what is loaded ([extendHistoryBackTo]).
     *
     * The trace is windowed and the pannable floor is read separately ([historyFloorMs]) because the
     * two have wildly different costs. This flow re-runs on every `cgm_reading` write — once a minute
     * while a sensor is live — and a class accumulates every sensor ever worn over a store that is
     * never pruned, so loading all of it cost 114 ms of query and ~20 MB of transient allocation per
     * emission at one year and twenty-four retired sensors, growing without bound. A window costs the
     * same on day one as on day four hundred.
     */
    private val historyLoadedFromMs = MutableStateFlow(
        System.currentTimeMillis() - INITIAL_HISTORY_WINDOW_MS,
    )

    /**
     * Load further back, because the viewport has approached what is loaded. Clamped forward to now
     * so a wild value cannot widen the window to the whole store, and monotone backwards so panning
     * out and back does not re-query.
     */
    fun extendHistoryBackTo(fromMs: Long) {
        val target = fromMs.coerceAtMost(System.currentTimeMillis())
        historyLoadedFromMs.update { current -> if (target < current) target else current }
    }

    /**
     * The panel's window over the VIEWED source's MODEL CLASS — one continuous history across every
     * sensor of that model, collapsed to one reading per grid slot, a real measurement outranking a
     * warm-up or interpolated one and the viewed source breaking the tie. Server-synced history is
     * gap-filled into `cgm_reading` by the catch-up merge (T1dmRepository.mergeServerSample), so it
     * flows through here to the graph — and through recentBgSeries to the model — automatically.
     *
     * **Class-scoped, where everything downstream of a reading is still source-scoped.** A
     * `sourceId` names one physical sensor and retires with it, so while this was source-scoped a
     * sensor change emptied the panel: no earlier readings, and — because the pannable domain was
     * floored at the first reading on screen — no earlier logged meal or dose reachable either,
     * though neither had ever been stored per source. Widening the DRAWN history fixes both without
     * touching what is believed: [latestReading], the alarm engine, the `sample` projection and the
     * model's own context all still read the one authoritative source (§3.1).
     *
     * **Scoped to the VIEWED source, which is usually the authoritative one.** Stepping the bottom bar
     * to another active sensor re-points the trace at it: still its whole model class, with the viewed
     * source breaking a contested grid slot, so where both sensors hold a reading you see the one you
     * asked for and where only the other does it backfills. Nothing about authority moves with it.
     */
    val dashboardReadings: Flow<List<CgmReading>> =
        combine(viewedSource, historyLoadedFromMs) { d, from -> d to from }
            .flatMapLatest { (d, from) ->
                if (d == null) flowOf(emptyList())
                else repository.observeReadingsForSensorModel(d.sensorModelId, d.id, from, Long.MAX_VALUE)
            }

    /**
     * Where the record actually begins for the viewed source's class, or null while it holds nothing
     * — the floor the graph's pannable domain uses, INSTEAD of the first reading it happens to hold.
     *
     * Without this the window above would re-create the defect it was written to fix: the domain
     * would be floored at the newest loaded reading, and a meal logged before that would be
     * unreachable again — for a different reason, but just as unreachable. One aggregate, so knowing
     * the record goes back a year costs nothing like carrying a year.
     */
    val historyFloorMs: Flow<Long?> = viewedSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeOldestTsForSensorModel(d.sensorModelId)
    }

    /** The active source's trailing [SMOOTHING_PREVIEW_HOURS] of mg/dL, oldest→newest — the sample the
     *  Graph-settings BG-input-filter miniature redraws at each detent. Bounded at the QUERY rather
     *  than by tailing [dashboardReadings]: a settings screen has no business scanning the whole store. */
    val smoothingPreviewMgdl: Flow<DoubleArray> = authoritativeSource.flatMapLatest { d ->
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

    val latestReading: Flow<CgmReading?> = authoritativeSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeLatestReading(d.id)
    }

    /**
     * The two readings every glance surface needs, as one value.
     *
     * The pair is the guard, not a convenience: [latestReading] is the newest row whatever its
     * provenance, and a promoted reconstruction is a row like any other. Passing it in as the
     * current BG is the mistake that puts a model's number on the always-on notification, the
     * predictive alert and the home and lock widgets, and it is a mistake a single value cannot
     * make.
     */
    val glanceReadings: Flow<GlanceReadings> = authoritativeSource.flatMapLatest { d ->
        if (d == null) {
            flowOf(GlanceReadings.EMPTY)
        } else {
            combine(
                repository.observeLatestReading(d.id),
                repository.observeLastMeasuredReading(d.id),
            ) { latest, measured -> GlanceReadings.of(latest, measured) }
        }
    }

    /**
     * The VIEWED source's newest reading — for the bottom bar's sensor chip, which names a sensor and
     * shows its link quality and so must describe ONE of them. [latestReading] stays authoritative and
     * still drives the BG value, the trend arrow and the staleness verdict beside it: those answer
     * "what is my glucose", which the believed sensor alone gets to say.
     *
     * Equal to [latestReading] whenever the panel is on the authoritative source, which is nearly
     * always, so the second observer costs nothing in the ordinary case.
     */
    val viewedReading: Flow<CgmReading?> = viewedSource.flatMapLatest { d ->
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
     * of them used to arrive as whole tables: [dashboardReadings] is every reading the viewed source
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
     *  CLASS-scoped range query the panel observes — the terrain IS the panel's trace, so a track cut
     *  across a sensor change must not fall through the hole the old scoping left. The game never
     *  subscribes, because a track is cut once and a reading arriving mid-run must not rebuild the
     *  ground under the car. */
    suspend fun gameReadings(fromMs: Long): List<CgmReading> {
        val source = repository.observeAuthoritativeSource().first() ?: return emptyList()
        return repository.observeReadingsForSensorModel(source.sensorModelId, source.id, fromMs, Long.MAX_VALUE).first()
    }

    /** An exercise bout's review window, `[fromMs, toMs]`. [gameReadings]'s shape, and bounded at BOTH
     *  ends because a review is a fixed picture of a finished bout: one shot, class-scoped, and never
     *  subscribed, so a reading landing mid-scrub cannot rebuild the chart under the thumb. */
    suspend fun sessionReadings(fromMs: Long, toMs: Long): List<CgmReading> {
        val source = repository.observeAuthoritativeSource().first() ?: return emptyList()
        return repository.observeReadingsForSensorModel(source.sensorModelId, source.id, fromMs, toMs).first()
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

    /** BLE signal strengths (item 20): CGM RSSI from the authoritative source's last advert, and the watch
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
                sync.forecastStream.values.sumOf { it.sent }
            BgPulses(
                server = serverToken,
                cgm = latest?.tsMs ?: 0L,
                watch = watch.lastPushMs ?: 0L,
            )
        }
    }

    /** The user-configured TOTAL sensor service life (days, 3–30, default 15), for the CGM-panel slider.
     *  Only the total is a setting now — the elapsed part comes from the sensor (see [sensorExpiryMs]). */
    val sensorLifeDays: Flow<Int> get() = settingsStore.sensorLifeDays

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

    /** Set/renew the sensor lifetime from a user-entered remaining duration; stores the absolute
     *  expiry so the countdown survives restarts. */
    suspend fun setSensorLifetime(days: Int, hours: Int, minutes: Int) {
        val durationMs = ((days.toLong() * 24 + hours) * 60 + minutes) * 60_000L
        settingsStore.setSensorExpiryMs(System.currentTimeMillis() + durationMs)
    }

    suspend fun clearSensorLifetime() = settingsStore.clearSensorExpiry()

    /** Whether the public build's first-run disclaimer has been acknowledged (false on a fresh
     *  install and after a full reset). Read only by the public flavor's `Disclaimer`. */
    val disclaimerAcknowledged: Flow<Boolean> get() = settingsStore.disclaimerAcknowledged

    suspend fun acknowledgeDisclaimer() = settingsStore.acknowledgeDisclaimer()
    suspend fun setSensorLifeDays(days: Int) = settingsStore.setSensorLifeDays(days)

    /**
     * The sensor's derived expiry instant (epoch-ms): the sensor's own start — anchored off the latest
     * reading's `minFromStart` (sensor minutes-since-activation) — plus the configured total service
     * life. Null until a reading carrying a sensor age arrives. The BG panel + CGM panel count it down.
     * Sensor-sourced (the elapsed part is read from the connected session); only the total life is a
     * user setting (replaces the old user-entered absolute-expiry knob).
     */
    val sensorExpiryMs: Flow<Long?> by lazy {
        combine(latestReading, sensorLifeDays) { latest, lifeDays ->
            val mfs = latest?.minFromStart ?: return@combine null
            val startMs = latest.tsMs - mfs.toLong() * 60_000L
            startMs + lifeDays.toLong() * 86_400_000L
        }
    }

    /**
     * The instant the active sensor's warm-up ends (epoch-ms), or null whenever there is no such instant
     * to state. Derived off the latest reading's `minFromStart` pinned to its RAW RECEIVE time, plus the
     * ACTIVE source's persisted warm-up window.
     *
     * The anchor is `rxWallMs`, not the grid stamp. `tsMs` holds still across a five-minute slot while
     * `minFromStart` ticks every minute, so subtracting one from the other makes the reconstructed
     * activation instant recede a minute per minute: the countdown runs visibly backwards within a slot
     * and jumps forward at each boundary, and the ±2.5 min snap alone can put the deadline behind `now`
     * on arrival. `rxWallMs` advances in lockstep with the sensor's own age, so the reconstruction holds
     * still — which is what makes it an anchor.
     *
     * Null unless warm-up is genuinely in progress: it requires a latest reading actually flagged
     * `WARMUP` (the pipeline's own verdict — see `ReadingClassifier`), a sensor age to anchor on, and
     * an active source whose window is known. Anything missing fails closed to null and the BG panel keeps its two-state expiry
     * countdown rather than inventing an instant.
     */
    val sensorWarmupEndMs: Flow<Long?> by lazy {
        combine(latestReading, authoritativeSource) { latest, active ->
            if (latest == null || latest.flag != ReadingFlag.WARMUP) return@combine null
            val mfs = latest.minFromStart ?: return@combine null
            val window = active?.warmupWindowMin ?: return@combine null
            latest.rxWallMs - mfs.toLong() * 60_000L + window.toLong() * 60_000L
        }
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

    /** The authoritative sensor changed: drop the forecast, which was conditioned on the outgoing
     *  sensor's history and describes nothing beside the incoming sensor's glucose. */
    fun invalidateInferenceOnSourceChange() = inferenceController.onCgmSourceChanged()

    fun makeAuthoritativeCgm(id: String) =
        registry.setAuthoritative(com.t1dm.core.model.CgmSourceId(id))

    /** CGM panel: start reading a sensor. Additive — nothing else stops. */
    fun activateCgm(id: String) = registry.activate(com.t1dm.core.model.CgmSourceId(id))

    /** CGM panel: stop reading a sensor. Refused for the authoritative one. */
    fun deactivateCgm(id: String) = registry.deactivate(com.t1dm.core.model.CgmSourceId(id))

    /** CGM panel "Remove" — take a retired sensor off the list. A display flag: the source stays on
     *  record, so its readings stay in the BG panel's model-wide history. */
    fun hideCgm(id: String) = registry.hide(com.t1dm.core.model.CgmSourceId(id))

    /**
     * Settings → CGM: retune the ACTIVE source's sensor warm-up window (minutes). Routed through the registry
     * rather than straight at the repository so its in-memory descriptor set moves with the column — a
     * later re-sighting re-upserts that set, and a stale copy there would overwrite the edit.
     *
     * Nothing to do with the INFERENCE warm-up (`setWarmupHours`): that is how much trailing history the
     * forecast waits for, a wholly separate concept that happens to share a word.
     */
    suspend fun setSensorWarmupMin(minutes: Int) {
        val id = repository.authoritativeSourceId() ?: return
        registry.setWarmupWindowMin(id, minutes)
    }

    /** The FGS 5-min grid tick calls this to seal + push one glance (suspends in low-power mode). */
    suspend fun pushToWatch(nowMs: Long) = watchLink.pushNow(nowMs)

    companion object {
        /** The adoption document's name in the app's external files directory — see [ct5ImportSource]. */

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
