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

private const val KV_WARMUP_HOURS = "inference.warmup_hours"
private const val WARMUP_HOURS_MAX = 72

/** Long enough for a real excursion; a 25-sample filter still fits inside it. */
private const val SMOOTHING_PREVIEW_HOURS = 3L

/** 30 days: every window the panel offers, at a re-query of a few thousand rows, not a lifetime. */
private const val INITIAL_HISTORY_WINDOW_MS = 30L * 24 * 3_600_000L

/** Bounded at the QUERY, per table; the interleaved list is trimmed to the same bound, so the cut
 *  is by TIME rather than by whichever table is busier. */
private const val LOG_FEED_LIMIT = 400

/** The BackendId enum name per model id; absent = auto (the fp32 XNNPACK authority). */
private fun kvForecastBackend(modelId: String) = "inference.forecast_backend.$modelId"

/** Minutes. The longest also fixes the window the suite scores — `SPEC/invariants.md` §6.2, §6.3. */
private val ACCURACY_HORIZONS_MIN = listOf(30, 60, 120)

/** The scored window, and the band-recalibration fit window. Deliberately one number. */
private const val ACCURACY_WINDOW_DAYS = 14

/** `SPEC/inference.md` §8.4. A floor on the CALIBRATION SPLIT (`CAL_FRACTION` = 0.7 of the set),
 *  not on the window set: 144 needs 206 matured windows. Unrelated to `MetricsConfig.minSamples`. */
private const val CONFORMAL_MIN_CAL_WINDOWS = 144

/** mg/dL. Forgives a near-boundary FALSE ALARM; recall stays strict. A second copy of `T1DMAI`'s
 *  `EXCURSION_PRECISION_TOLERANCE_MGDL` — `SPEC/invariants.md` §6.1 does not carry it. */
private const val EXCURSION_PRECISION_TOLERANCE_MGDL = 10.0

/** H7 re-mirror: `sample` rows enqueued per resumable page. Work per round trip, not a safety bound. */
private const val REMIRROR_SCALAR_PAGE = 500

/** H7 re-mirror: covers one page several times over; the queue's own backoff handles a slow server. */
private const val REMIRROR_MAX_DRAIN_PASSES = 12

/** H7 re-mirror: bounds one pass; the persisted cursor makes the next connect resume, not restart. */
private const val REMIRROR_MAX_PAGES_PER_PASS = 20

/** Bounded: each Cut entry carries every row it removed. */
private const val BG_EDIT_UNDO_MAX = 32

/** A day of five-minute slots — the window when no model is loaded to size it. */
private const val CUT_ONLY_CONTEXT_STEPS = 288

/** Built once in [com.t1dm.app.T1dmApplication]; nothing else constructs a database, dispatchers or
 *  native core. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val dispatchers: T1dmDispatchers = DefaultT1dmDispatchers()

    val nativeCore: NativeCore = UniffiNativeCore()

    /** Application-lifetime; survives Activity churn. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val repository: T1dmRepository by lazy { T1dmRepository(database, dispatchers) }

    val backupManager: BackupManager by lazy {
        BackupManager(appContext, repository, settingsStore, dispatchers, BuildConfig.VERSION_NAME)
    }

    val pubsRepository: PubsRepository by lazy { PubsRepository(BlueskyClient(dispatchers), dispatchers) }

    private val cgmRepository by lazy {
        AppCgmRepository(repository, outboxEnqueuer)
    }

    val plugin: AidexXPlugin by lazy { AidexXPlugin(nativeCore, cgmRepository) }

    /**
     * The passive-advertisement CGM registry, the sole read path. The FGS narrows to the
     * AUTHORITATIVE source before the reading bus: the staleness-driven alarm path must see one
     * sensor's stream, not several interleaved (§3.6).
     */
    val registry: AidexXSourceRegistry by lazy {
        AidexXSourceRegistry(
            plugin = plugin,
            repository = cgmRepository,
            scope = appScope,
        )
    }

    val settingsStore: SettingsStore by lazy { SettingsStore(repository) }

    /**
     * §3.6-A. A `@Volatile var` so a Settings edit reaches the live property readers;
     * [refreshAlarmConfig] also pushes it into the running [AlarmEngine].
     */
    @Volatile
    var alarmConfig: AlarmConfig = AlarmConfig.DEFAULT
        private set

    /** The same value for Compose readers: a `@Volatile` read never invalidates a composition. */
    private val _alarmConfigFlow = MutableStateFlow(AlarmConfig.DEFAULT)
    val alarmConfigFlow: StateFlow<AlarmConfig> = _alarmConfigFlow.asStateFlow()

    /**
     * False while [alarmConfig] still holds the coded defaults. A reader that PERSISTS what it reads
     * must check it: the widget bakes the config into its Glance state.
     */
    @Volatile
    var alarmConfigHydrated: Boolean = false
        private set

    /**
     * Pushes a config into the running engine on the engine's own single-thread dispatcher. Null
     * while the FGS is down. Thresholds and presentation only — never re-arms or clears a latched
     * breach (§3.6-A).
     */
    @Volatile
    private var liveAlarmConfigSink: ((AlarmConfig) -> Unit)? = null

    fun setAlarmConfigSink(sink: ((AlarmConfig) -> Unit)?) {
        liveAlarmConfigSink = sink
    }

    suspend fun refreshAlarmConfig() {
        alarmConfig = runCatching { settingsStore.currentAlarmConfig() }.getOrDefault(AlarmConfig.DEFAULT)
        alarmConfigHydrated = true
        _alarmConfigFlow.value = alarmConfig
        liveAlarmConfigSink?.invoke(alarmConfig)
    }

    // The notification presenters run outside Compose and cannot read `LocalT1dmSemantics`; this
    // @Volatile lets them resolve the glyph geometry and accent synchronously.
    @Volatile
    var themeIdSnapshot: String = com.t1dm.core.design.ThemeIds.TRON
        private set

    @Volatile
    var customThemeJsonSnapshot: String? = null
        private set

    val iconStyle: com.t1dm.core.design.IconStyle
        get() = com.t1dm.core.design.iconStyleForTheme(themeIdSnapshot)

    /** ARGB, for `Notification.Builder.setColor`. */
    val notificationAccentArgb: Int
        get() = com.t1dm.app.notify.NotificationIcons.accentArgb(themeIdSnapshot, customThemeJsonSnapshot)

    // DEATH mode (total silence). Read synchronously off the @Volatile by the FGS alarm and the
    // predictive gates. The persisted flag is deliberately never exported.
    @Volatile
    var deathModeSnapshot: Boolean = false
        private set

    val deathMode: Flow<Boolean> get() = settingsStore.deathMode
    suspend fun setDeathMode(on: Boolean) = settingsStore.setDeathMode(on)

    // Time-bounded presentation-layer silence (§3.6 C1–C5). Never touches the engine, which keeps
    // firing. Process-scoped deliberately: a restart forgets snoozes and the engine re-fires.
    @Volatile
    var snoozeSnapshot: SnoozeState = SnoozeState.NONE
        private set

    /** [dismiss] silences until the breach clears, otherwise until [untilMs]. */
    @Synchronized
    fun snoozeAlarm(alarm: ActiveAlarm, untilMs: Long, dismiss: Boolean) {
        snoozeSnapshot = if (dismiss) snoozeSnapshot.dismiss(alarm) else snoozeSnapshot.snooze(alarm, untilMs)
    }

    /** §3.6 C1/C3. Called by the FGS on every engine-state change. */
    @Synchronized
    fun pruneSnooze(state: AlarmState) {
        val pruned = snoozeSnapshot.pruned(state, System.currentTimeMillis())
        if (pruned !== snoozeSnapshot) snoozeSnapshot = pruned
    }

    /** No stale silence may outlive the alarm it covered. */
    @Synchronized
    fun clearSnooze() {
        snoozeSnapshot = SnoozeState.NONE
    }

    /** Whole minutes. */
    @Volatile
    var snoozeMinSnapshot: Int = SettingsStore.DEFAULT_SNOOZE_MIN
        private set

    val snoozeMin: Flow<Int> get() = settingsStore.snoozeMin
    suspend fun currentSnoozeMin(): Int = settingsStore.currentSnoozeMin()
    suspend fun setSnoozeMin(min: Int) = settingsStore.setSnoozeMin(min)

    /** Estimated HbA1c, %, over 30 days. Null until first computed, or with too little data. */
    @Volatile
    var gmiSnapshot: Double? = null
        private set

    /** Local midnight → now. Summed in SQL: this runs on every widget push. */
    suspend fun stepsToday(): Int {
        val zone = java.time.ZoneId.systemDefault()
        val midnight = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return repository.stepsInRange(midnight, System.currentTimeMillis())
    }

    // Read synchronously by the FGS forecast driver on every reading tick.
    @Volatile
    var forecastModeSnapshot: String = SettingsStore.FORECAST_MODE_ADAPTIVE
        private set

    /** Whole minutes, read fresh per timed tick. */
    suspend fun forecastPeriodMin(): Int = settingsStore.currentForecastPeriodMin()

    /**
     * Sound is the system ALARM-usage tone, so an urgent-low plays through DND. Additive: a choice
     * here can never change WHEN an alarm fires, only how it is announced (§3.6-A).
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

    /** Synchronous, for the notifier and the presenter: both run outside Compose and cannot suspend. */
    @Volatile
    var alertActuatorSnapshot: AlertActuatorConfig = AlertActuatorConfig.SILENT
        private set

    suspend fun refreshAlertActuatorConfig() {
        alertActuatorSnapshot =
            runCatching { alertActuatorConfig() }.getOrDefault(AlertActuatorConfig.SILENT)
    }

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

    private val vibrationActuator by lazy { com.t1dm.alerts.VibrationActuator(appContext) }

    /** Unknown names are ignored. Preview only — never touches the alarm path (§3.6-A). */
    fun previewVibration(name: String) {
        val preset = runCatching { com.t1dm.alerts.VibrationPreset.valueOf(name) }.getOrNull() ?: return
        vibrationActuator.buzz(preset)
    }

    suspend fun saveAlarmThresholds(urgentLow: Int, low: Int, high: Int, urgentHigh: Int) {
        settingsStore.setAlarmThresholds(urgentLow, low, high, urgentHigh)
        refreshAlarmConfig()
    }

    suspend fun saveLossWindows(lossMin: Int, lossEscalatedMin: Int) {
        settingsStore.setLossWindows(lossMin, lossEscalatedMin)
        refreshAlarmConfig()
    }

    /** A signal-QUALITY alert, distinct from loss-of-signal (§3.6-A). */
    suspend fun saveWeakSignal(enabled: Boolean, dbm: Int, sustainMin: Int) {
        settingsStore.setWeakSignal(enabled, dbm, sustainMin)
        refreshAlarmConfig()
    }

    suspend fun saveRepeatCadence(min: Int) {
        settingsStore.setRepeatCadence(min)
        refreshAlarmConfig()
    }

    suspend fun saveMinActuationMin(min: Int) {
        settingsStore.setMinActuationMin(min)
        refreshAlarmConfig()
    }

    /** The over-temp alarm is EXEMPT from DEATH's global suppression (D4). */
    suspend fun saveOverTempConfig(enabled: Boolean, alertC: Double, clearC: Double, critical: Boolean) {
        settingsStore.setOverTempConfig(enabled, alertC, clearC, critical)
        refreshAlarmConfig()
    }

    /** Accepts the wrapped shape and the legacy flat settings-only file. Throws on a malformed or
     *  foreign one. Off-main. */
    suspend fun importConfigJson(text: String): ImportResult = withContext(dispatchers.io) {
        val parsed = ConfigBackup.parse(text)
        // Null only for a drawings-only backup; a foreign file is refused by `importJson`'s format
        // tag. The drawings are applied whatever the settings do.
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
            // De-duplicated on the authoring instant; two distinct strokes cannot share a millisecond.
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

    class ImportResult(
        val keys: Int,
        val paintingsAdded: Int,
        val paintingsSkipped: Int,
        val settingsError: String? = null,
    )

    /** The settings half is applied here: only the composition root can re-hydrate the alarm and
     *  actuator policies afterwards. */
    class RestoreResult(
        val archive: ArchiveResult,
        val settingsKeys: Int,
        val settingsError: String?,
    )

    /**
     * [open] is a FACTORY, not a stream: the legacy fallback must read the file from the beginning a
     * second time, and the archive attempt has already consumed its header.
     */
    suspend fun restoreArchive(open: suspend () -> java.io.InputStream): RestoreResult =
        withContext(dispatchers.io) {
            val result = try {
                open().use { repository.readArchive(it) }
            } catch (e: NotAnArchiveException) {
                // Not an archive. The legacy document's own format tag still refuses a foreign JSON.
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

            // Settings applied separately: a configuration that will not import must not cost the
            // history that already landed.
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

    /** Ceiling on a legacy restore read wholly into memory. The archive path is streamed. */
    private val MAX_LEGACY_BACKUP_BYTES = 32 * 1024 * 1024

    /** Dev-time models dir on external files; the `.pte` is not bundled. */
    val modelsDir: File = File(appContext.getExternalFilesDir(null), "models").apply { mkdirs() }

    /** Held rather than built inline: the contract obliges a re-send of the latest forecast on every
     *  reconnect, and this is the only thing holding one. */
    val roomPredictionStore: RoomPredictionStore by lazy {
        RoomPredictionStore(repository, streamClient, syncStatusStore)
    }

    val inferenceController: InferenceController by lazy {
        buildInferenceController(
            native = nativeCore,
            dispatchers = dispatchers,
            modelsDir = modelsDir,
            history = RoomBgHistoryProvider(repository, registry),
            // The `prediction` table is the source of truth; the stream stores none.
            predictionStore = roomPredictionStore,
            // feat 1 / feat 2 — the reconstructed carb-appearance and insulin-action channels (SPEC §3.3).
            contextChannels = ContextChannelSource { gridStartMs, nSteps ->
                dashboardCurveChannels(gridStartMs, nSteps)
            },
            // The prediction zone, on the committed dose tails past the now-boundary, via the SAME
            // curve engine the calculator uses (SPEC §3.3).
            futureOverrides = FutureOverrideSource { rollStartMs, nFutureSteps ->
                dashboardFutureChannels(rollStartMs, nFutureSteps)
            },
            // Read fresh each cycle.
            warmupHoursProvider = { warmupHours() },
            // INFERENCE.md §7.1. The same window the calculator's roll and the dashboard overlay read.
            smoothingWindowProvider = { smoothingWindow() },
            // A real unit of rapid insulin, so the guard's mg/dL-per-unit is the quantity the
            // sensitivity read-out reports and not an impulse nobody receives.
            probeInsulin = ProbeInsulinPort { units, steps ->
                val curve = presetCurve(units, resolveRapidPreset(null))
                DoubleArray(steps) { i -> curve.getOrElse(i) { 0.0 } }
            },
            // Read fresh each discovery.
            maxRunningProvider = { maxRunningModels() },
            // Re-read for every discovered id.
            backendPrefProvider = { id -> forecastBackendPref(id) },
            telemetryStore = KvTelemetryStore(repository),
            // Re-read every cycle. Deserialization failure ⇒ null ⇒ the frozen model, never a
            // half-applied adapter.
            loraStore = LoraStore { modelId ->
                repository.attachedLora(modelId)?.let { row ->
                    nativeCore.loraDeserialize(row.blob)
                        ?: null.also { Timber.w("adapter %d for %s failed to load; running frozen", row.id, modelId) }
                }
            },
            // The same ChannelBuilder the context channels come from, so both views of the logged
            // doses are built from one set of records.
            baselineStore = KvBaselineStore(repository),
            curveEvents = CurveEventSource { fromMs, toMs -> channelBuilder.eventsIn(fromMs, toMs) },
            // Disabled ⇒ null ⇒ no gate. Deliberately no death-mode check: the gate stays active in
            // DEATH (D4).
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

    /** Serialised with the FGS cycles by the controller's own mutex; bypasses no §3.6 gate. */
    fun reevaluateInferenceNow() {
        appScope.launch {
            runCatching { inferenceController.runFromHistory(InferenceCause.GRID_TICK, System.currentTimeMillis()) }
        }
    }

    private val curveReforecastScheduled = AtomicBoolean(false)

    /**
     * Debounced, leading-edge and coalescing: a burst of writes folds into one forward, and the guard
     * is released just BEFORE it, so a write landing mid-cycle earns its own. Bypasses no §3.6 gate —
     * the same [InferenceController.runFromHistory] the cadence driver calls. Never delays the write.
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

    /** Public-safe: no secrets. */
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

    private val hardwareProbe by lazy { HardwareProbe(appContext) }

    suspend fun detectHardware(): HardwareInfo =
        withContext(dispatchers.io) { hardwareProbe.probe() }

    // BatteryManager EXTRA_TEMPERATURE, tenths of °C. A real sensor value, never a proxied fan figure.
    val temperatureUnit: Flow<TempUnit> = settingsStore.temperatureUnit.map { TempUnit.fromKey(it) }
    suspend fun setTemperatureUnit(u: TempUnit) = settingsStore.setTemperatureUnit(u.key)

    /** Celsius, or null if unreadable. Sticky-intent read; call off-main. */
    fun readDeviceTempC(): Double? = runCatching {
        val intent = appContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10.0 }
    }.getOrNull()

    // Celsius throughout. The gate itself is wired in via `thermalProvider` above.
    val thermalGateEnabled: Flow<Boolean> = settingsStore.thermalGateEnabled
    val inferenceMaxTempC: Flow<Double> = settingsStore.inferenceMaxTempC
    val thermalWarnMarginC: Flow<Double> = settingsStore.thermalWarnMarginC
    suspend fun setThermalGateEnabled(on: Boolean) = settingsStore.setThermalGateEnabled(on)
    suspend fun setInferenceMaxTempC(c: Double) = settingsStore.setInferenceMaxTempC(c)
    suspend fun setThermalWarnMarginC(c: Double) = settingsStore.setThermalWarnMarginC(c)

    /**
     * Data-independent: a picture of a zone algebra, not of this patient, so one pair serves every
     * model. Empty on a stub core, which the figures render as no regions rather than wrong ones.
     * 51 200 classifications across the pair, hence off-main.
     */
    private val errorGridLatticesOnce: ErrorGridLattices by lazy {
        ErrorGridLattices(
            clarke = ZoneLattice.build(nativeCore::clarkeZoneGrid),
            dts = ZoneLattice.build(nativeCore::dtsZoneGrid),
        )
    }

    suspend fun errorGridLattices(): ErrorGridLattices =
        withContext(dispatchers.default) { errorGridLatticesOnce }

    /** mg/dL per minute, from the crate. Empty on a stub core ⇒ unlabelled bins. */
    val trendBinEdges: List<Double> by lazy { nativeCore.trendBinEdges() }

    /**
     * Scored in the core on the band projection of `SPEC/invariants.md` §6.2, median line beneath. A
     * horizon with fewer than [minSamples] scored windows is flagged insufficient. CG-EGA is not
     * computed here — see [modelCgEga]. Off-main.
     */
    suspend fun modelMetrics(
        modelId: String,
        days: Int = ACCURACY_WINDOW_DAYS,
        minSamples: Int = 6,
    ): ModelMetrics = modelMetrics(modelId, days, minSamples, includeCgEga = false)

    /** §6.3, whole-window. Null when nothing scoreable was found. Off-main. */
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
        // §6.1 leaves the threshold to the consumer: here, the patient's own alarm bands.
        val config = MetricsConfig(
            hypoThresholdMgdl = settingsStore.alarmLow.first().toDouble(),
            hyperThresholdMgdl = settingsStore.alarmHigh.first().toDouble(),
            excursionPrecisionToleranceMgdl = EXCURSION_PRECISION_TOLERANCE_MGDL,
            minSamples = minSamples,
        )
        val suite = withContext(dispatchers.default) {
            nativeCore.forecastMetricsSuite(set.windows, ACCURACY_HORIZONS_MIN, config, includeCgEga)
        }
        return ModelMetrics(suite, set.nMatured, set.nIncomplete, minSamples, set.nForeignSource)
    }

    // Band recalibration, `SPEC/inference.md` §8.4. The median never moves; every classifier and the
    // wire read the RAW `ModelPrediction.bandsMgdl` as stored. The correction reaches exactly one
    // surface: the BG panel's forecast overlay, through [calibratedBands].

    /** One fit at a time, process-wide. A second entry is refused, never queued. */
    private val bandCalibrationRunning = AtomicBoolean(false)

    /** Observed from Room, so a fit lands on the graph without the panel being reopened and there is
     *  no in-memory authority to rebuild after process death. */
    val bandCalibrations: StateFlow<Map<String, BandCalibration>> =
        repository.observeBandCalibrations()
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * The §8.4 apply, for the one display surface entitled to it. Null ⇒ draw the raw fan: no
     * correction, expired, a shape that disagrees with the fan's, or the core refused. The map is an
     * argument so the overlay's `produceState` can key on it.
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
     * [calibratedBands] over a whole sweep of one model's fans. [fansMgdl] is fan-major,
     * `nFans · horizonSteps · nQuantiles`. Eligibility is decided once, on [calibratedBands]'s rules.
     */
    fun calibratedFanBatch(
        calibrations: Map<String, BandCalibration>,
        modelId: String,
        fansMgdl: () -> List<Double>,
        horizonSteps: Int,
        nQuantiles: Int,
    ): List<Double>? {
        // Eligibility first: [fansMgdl] flattens a day of fans, discarded on the common no-correction path.
        val delta = eligibleDelta(calibrations, modelId, horizonSteps, nQuantiles) ?: return null
        return nativeCore.applyQuantileConformalBatch(fansMgdl(), delta)
    }

    /** Held in one place because both applies must answer it identically. */
    private fun eligibleDelta(
        calibrations: Map<String, BandCalibration>,
        modelId: String,
        horizonSteps: Int,
        nQuantiles: Int,
    ): List<Double>? {
        val cal = calibrations[modelId] ?: return null
        // Not this forecast's correction. The core would reject it anyway; this saves an FFI hop.
        if (cal.steps != horizonSteps || cal.nQuantiles != nQuantiles) return null
        // Kept rather than deleted: the drill-down still says what lapsed and when.
        if (cal.expiredAt(System.currentTimeMillis())) return null
        // A delta states ONE sensor's error. Null on either side is UNKNOWN and draws the raw fan.
        val authoritative = registry.authoritative.value?.value
        if (authoritative == null || cal.sourceId != authoritative) return null
        return cal.delta
    }

    /**
     * Minutes, or null when it cannot be established. The number a band correction must be fitted at:
     * fitting at the accuracy suite's longest horizon would tie every correction to 120 min. The
     * descriptor is asked first — it is available from discovery onward.
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
     * By hand rather than by rule: the case cannot be detected after the fact — a correction fitted
     * across a CGM source change measures the gap between two sensors, and once the older forecasts
     * have aged out nothing is left to infer that from. Raw fan until a refit.
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
            // `forecastWindows` is newest-first; the conformal split is chronological, so the order
            // is load-bearing here in a way it never is for the order-free metric suite.
            val chronological = set.windows.asReversed()
            val fit = withContext(dispatchers.default) {
                nativeCore.fitQuantileConformal(chronological, minCalWindows)
            }
            // `steps == 0` is the core's "nothing scoreable": no counts worth printing, so it reads
            // as no result rather than a refusal with n = 0.
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
                    // From the repository, not the registry: it must be the value `forecastWindows`
                    // filtered on.
                    sourceId = repository.authoritativeSourceId()?.value,
                ),
            )
            return BandCalibrationOutcome(fit, true, set.nMatured, set.nIncomplete)
        } finally {
            bandCalibrationRunning.set(false)
        }
    }

    /** Hours. Floored at the model MIN_CONTEXT so the gate cannot fall below the context the model
     *  needs to run at all. */
    suspend fun warmupHours(): Double =
        (repository.getKv(KV_WARMUP_HOURS)?.toDoubleOrNull() ?: InferenceControllerDefaults.WARMUP_HOURS)
            .coerceAtLeast(InferenceControllerDefaults.MIN_WARMUP_HOURS.toDouble())

    val warmupHoursSetting: Flow<Int> = repository.observeKv(KV_WARMUP_HOURS).map { raw ->
        (raw?.toDoubleOrNull() ?: InferenceControllerDefaults.WARMUP_HOURS)
            .coerceAtLeast(InferenceControllerDefaults.MIN_WARMUP_HOURS.toDouble())
            .toInt()
    }

    /** Whole hours, clamped to `[MIN_CONTEXT, 72]`. */
    suspend fun setWarmupHours(hours: Int) {
        val clamped = hours.coerceIn(InferenceControllerDefaults.MIN_WARMUP_HOURS, WARMUP_HOURS_MAX)
        repository.putKv(KV_WARMUP_HOURS, clamped.toString(), System.currentTimeMillis())
    }

    // §2.3. Every running model forecasts and pushes; the SELECTED one draws the BG panel.

    val maxModelsSetting: Flow<Int> get() = settingsStore.inferenceMaxModels

    /** Clamped to the SettingsStore bounds. */
    suspend fun setMaxModels(n: Int) = settingsStore.setInferenceMaxModels(n)

    suspend fun maxRunningModels(): Int = settingsStore.currentInferenceMaxModels()

    // INFERENCE.md §7.1. ONE value feeds the forecast cycle, the calculator's rolls and the
    // dashboard's smoothed overlay; splitting them would draw a line the model never saw.

    val savgolWindow: Flow<Int> get() = settingsStore.savgolWindow

    suspend fun smoothingWindow(): Int = settingsStore.currentSavgolWindow()

    /** Snapped to an offered detent. */
    suspend fun setSmoothingWindow(window: Int) = settingsStore.setSavgolWindow(window)

    /** Null/blank ⇒ auto = fp32 XNNPACK. */
    private suspend fun forecastBackendPref(modelId: String): BackendId? =
        repository.getKv(kvForecastBackend(modelId))?.takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { BackendId.valueOf(name) }.getOrNull() }

    /** Null = auto. */
    fun forecastBackendSetting(modelId: String): Flow<BackendId?> =
        repository.observeKv(kvForecastBackend(modelId)).map { raw ->
            raw?.takeIf { it.isNotBlank() }?.let { name -> runCatching { BackendId.valueOf(name) }.getOrNull() }
        }

    /**
     * The DISPLAY forecast cycle only: dosing stays fail-closed on a non-authoritative backend until
     * the agreement probe passes (§3.6-E). Returns the backend actually active, which differs from
     * the request when it failed to load.
     */
    suspend fun setForecastBackend(modelId: String, backend: BackendId?): BackendId? {
        repository.putKv(kvForecastBackend(modelId), backend?.name ?: "", System.currentTimeMillis())
        return inferenceController.setForecastBackend(modelId, backend)
    }

    suspend fun runBackendComparison(runs: Int = 20) = inferenceController.runBackendComparison(runs)

    /** Hydrates [alarmConfig] before the FGS reads it. */
    fun startInference() {
        appScope.launch {
            refreshAlarmConfig()
            inferenceController.restoreLast()
            inferenceController.refreshModels()
            // An update staged in a prior session, before any sync.
            refreshPendingModelUpdates()
            // After the initial discovery, so the running set is known: an update to the just-loaded
            // dosing model is staged for manual apply, never applied in place before load.
            autoSyncModels("startup")
        }
        // Read on the CGM hot path from a plain field, so it has to be published at startup.
        appScope.launch { refreshNightscoutEnabled() }
        appScope.launch { settingsStore.themeId.collect { themeIdSnapshot = it } }
        appScope.launch { settingsStore.customThemeJson.collect { customThemeJsonSnapshot = it } }
        appScope.launch { settingsStore.deathMode.collect { deathModeSnapshot = it } }
        appScope.launch { settingsStore.snoozeMin.collect { snoozeMinSnapshot = it } }
        appScope.launch { settingsStore.forecastMode.collect { forecastModeSnapshot = it } }
        appScope.launch { settingsStore.aggressiveScanEnabled.collect { aggressiveScanSnapshot = it } }
        appScope.launch { settingsStore.aggressiveOnlyCharging.collect { aggressiveOnlyChargingSnapshot = it } }
        appScope.launch { settingsStore.aggressiveShowGlucose.collect { aggressiveShowGlucoseSnapshot = it } }
        // Slow-moving; recomputed every 30 min so the widget reads a cached value.
        appScope.launch(dispatchers.default) {
            while (isActive) {
                val now = System.currentTimeMillis()
                gmiSnapshot = runCatching { statsRepository.localStats(StatsWindow.D30).gmi }
                    .getOrNull()?.takeIf { it in 3.0..25.0 }
                // §3.6 — the phone is the sole stats author. enqueueStats dedups to ≤1/window/day.
                pushStats(now)
                delay(30 * 60_000L)
            }
        }
        // The §3.8 (H7) re-mirror is NOT launched here: it is the `reMirror` hook on
        // [catchUpCoordinator], which owns the epoch gate. A second driver on the `syncStatus` edge
        // recorded the epoch itself and marked a partial upload complete.
    }

    /** Keystore-wrapped at rest — never in the keep-forever Room DB. */
    val tokenStore: TokenStore by lazy { KeystoreTokenStore(appContext) }

    val serverProfileStore: ServerProfileStore by lazy { ServerProfileStore(repository, tokenStore) }

    val syncHttpClient: SyncHttpClient by lazy {
        OkHttpSyncClient(
            endpoint = { serverProfileStore.activeEndpoint() },
            dispatchers = dispatchers,
        )
    }

    /**
     * The running-set guard matches on-disk `.pte` FILENAMES, not descriptor ids, which can diverge
     * from the filename for an adb-pushed model. An update to the dosing model is STAGED for
     * [applyModelUpdate]; SHA-256 is verified before anything discoverable is written.
     */
    val modelSyncCoordinator: ModelSyncCoordinator by lazy {
        ModelSyncCoordinator(
            modelsDir = modelsDir,
            http = syncHttpClient,
            runningArtifacts = { inferenceController.runningArtifactFileNames() },
        )
    }

    /** Null until run. */
    val modelSyncStatus = MutableStateFlow<String?>(null)

    /** Descriptor ids staged in `pending/`. A staged update never swaps the running model on its own. */
    val pendingModelUpdates = MutableStateFlow<Set<String>>(emptySet())

    private suspend fun refreshPendingModelUpdates() {
        pendingModelUpdates.value = withContext(dispatchers.io) {
            runCatching { modelSyncCoordinator.pendingModelIds() }.getOrDefault(emptySet())
        }
    }

    val outboxEnqueuer: OutboxEnqueuer by lazy { OutboxEnqueuer(repository) }

    // One-way bridge to a third-party logbook on the Nightscout `/api/v1` subset. It shares the
    // durable outbox and nothing else: a bridge that is off, unreachable or rejecting its secret must
    // never stall the patient's own sync. Not a shared contract, so nothing here belongs in `SPEC/`.

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

    /** The repository consults this on the CGM hot path. Call after every save. */
    suspend fun refreshNightscoutEnabled() {
        repository.nightscoutBridgeEnabled = nightscoutConfigStore.current() != null
    }

    suspend fun saveNightscoutBridge(url: String, secret: String, enabled: Boolean): String {
        nightscoutConfigStore.save(url, secret, enabled, System.currentTimeMillis())
        refreshNightscoutEnabled()
        return if (enabled) nightscoutClient.probe() else "off"
    }

    suspend fun probeNightscout(): String = nightscoutClient.probe()

    /** Process-scoped; the durable outbox itself is persisted. */
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
        // The desync flag is shared with the StreamClient, so a dropped WS frame escalates the next
        // catch-up to a full resync. `scope` is the process-lived appScope, NOT the service scope that
        // collects `events()`: the walk waits on a drain that collector kicks off.
        CatchUpCoordinator(
            stream = streamClient,
            http = syncHttpClient,
            repo = repository,
            scope = appScope,
            reMirror = HistoryReMirror { epoch -> reMirrorHistory(epoch) },
            tombstones = TombstoneReplay { replayTombstones() },
            desync = streamClient.desync,
        )
    }

    /** The §3.8 walk's persisted bookkeeping. Given plain functions, so its judgements are testable
     *  without Room. */
    private val reMirrorLedger: ReMirrorLedger by lazy {
        ReMirrorLedger(
            getKv = repository::getKv,
            putKv = repository::putKv,
            // Server-bound rows ONLY: a bridge row proves nothing about delivery, and counting one
            // would let an unreachable third party hold the walk open forever.
            oldestQueuedAtMs = repository::oldestServerBoundOutboxCreatedAt,
            maxQueueAgeMs = drainConfig.maxAgeMs,
        )
    }

    /** The FGS calls [SyncManager.launch] in its own lifecycle scope. */
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

    val outboxMaxAgeMs: Long get() = drainConfig.maxAgeMs
    val outboxMaxSize: Int get() = drainConfig.maxQueueSize

    /**
     * Every service read is guarded, so a missing service, a SecurityException or Wi-Fi being off
     * yields a partial snapshot rather than a throw. Off-main. Display only — touches no rail.
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
        // -127 is WifiInfo.INVALID_RSSI: no readable RSSI.
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

    val serverProfiles: Flow<List<ServerProfile>> = serverProfileStore.observeProfiles()

    val activeServerProfile: Flow<ServerProfile?> = serverProfileStore.observeActive()

    /** A blank [token] keeps the stored one. */
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
        launchAutoModelSync("profile-saved")
    }

    /**
     * Pages `GET /v1/series` from the start and LWW-merges into the wide `sample` table. Returns rows
     * merged; 0 with no profile or token. Off-main; never blocks the alarm path.
     */
    suspend fun resyncFromServer(): Int = withContext(dispatchers.io) {
        if (serverProfileStore.activeEndpoint() == null) 0
        else runCatching { catchUpCoordinator.catchUp(null) }.getOrDefault(0)
    }

    /**
     * The store a §3.8 walk is raised against, or null when there is no usable target. Carries the
     * profile id, base URL and edit stamp: [SyncHttpClient] resolves the endpoint per REQUEST, so any
     * profile save must invalidate the walk rather than credit an epoch to a store that never got it.
     */
    private suspend fun activeStoreIdentity(): String? {
        if (serverProfileStore.activeEndpoint() == null) return null
        val p = repository.activeProfile() ?: return null
        return "${p.id}\u001f${p.baseUrl}\u001f${p.updatedAtMs}"
    }

    /**
     * §3.8 (H7) — re-mirror the phone's history to a wiped or brand-new server. The scalar walk is
     * resumable: every bail-out is a `return false`, never a throw, so a pass keeps the ground it
     * proved and the next connect resumes from the banked cursor. Never blocks the alarm path.
     */
    private suspend fun reMirrorHistory(serverEpoch: String): Boolean = withContext(dispatchers.io) {
        val identity = activeStoreIdentity() ?: return@withContext false
        val walk = reMirrorLedger.resume(serverEpoch, identity, System.currentTimeMillis())

        if (walk.raiseEvents) {
            Timber.i(
                "re-mirroring history to store_epoch %s (stamp %d, resuming scalars after ts %d)",
                serverEpoch, walk.stampMs, walk.scalarCursor,
            )
            // Never age-evictable, top outbox priority.
            for (m in repository.loggedMealsInRange(0L, walk.stampMs).sortedBy { it.tsMs }) {
                outboxEnqueuer.enqueueMeal(m.toMealEventDto(), walk.stampMs)
            }
            for (d in repository.loggedDosesInRange(0L, walk.stampMs).sortedBy { it.tsMs }) {
                outboxEnqueuer.enqueueDose(d.toDoseEventDto(), walk.stampMs)
            }
            // Deduped ≤1/window/day.
            pushStats(walk.stampMs)
        }
        // Prove the phase out of the queue before crediting it. Unconditional: a scalar page's proof
        // is "nothing older than me remains".
        if (!drainThrough(walk.stampMs)) return@withContext false
        reMirrorLedger.bankEvents(walk.stampMs, System.currentTimeMillis())

        var cursor = walk.scalarCursor
        var pages = 0
        var scalarsComplete = false
        while (pages < REMIRROR_MAX_PAGES_PER_PASS) {
            val stamp = System.currentTimeMillis()
            // One INGEST dirty-marker per bucket. Null = no sample past the cursor: the walk is done.
            val next = repository.reMirrorScalarsBatch(cursor, REMIRROR_SCALAR_PAGE, stamp)
            if (next == null) { scalarsComplete = true; break }
            if (!drainThrough(stamp)) return@withContext false
            // Re-read the target before crediting: a page that drained to a repointed store must not
            // bank a cursor over it, which would skip those rows for good.
            if (activeStoreIdentity() != identity) return@withContext false
            cursor = next
            reMirrorLedger.bankScalarCursor(cursor, stamp)
            pages++
        }
        if (!scalarsComplete) {
            Timber.i("re-mirror banked %d scalar page(s) to ts %d; resumes on the next connect", pages, cursor)
            return@withContext false
        }
        // Re-read: the profile may have been repointed while the walk drained.
        val stillIdentity = activeStoreIdentity() ?: return@withContext false
        reMirrorLedger.delivered(serverEpoch, stillIdentity, System.currentTimeMillis())
    }

    /**
     * Drive the outbox until every row created at or before [throughMs] has left it. Drains DIRECTLY:
     * waiting on someone else's drain back-pressures against the collector this pass blocks. Bounded
     * three ways, each a plain `false`.
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

    /**
     * DESTRUCTIVE, and IN-PLACE: the FGS and process are deliberately NOT killed, so the held GATT
     * session keeps the sensor connected — hence `cgm_source` is preserved and the process-scoped
     * caches reset by hand. The CGM sensor secret survives; it can unbind a sensor still worn.
     */
    suspend fun resetAllData() = withContext(dispatchers.io) {
        // Drop the watch session BEFORE the wipe, so no late push can re-persist key material or a
        // nonce ceiling into the kv rows about to be cleared.
        runCatching { watchLink.stopForReset() }
        repository.wipeAllData(preserveCgmSources = true)
        runCatching { tokenStore.clearAll() }
        com.t1dm.app.watch.WatchKeyCipher.deleteKey()
        // The Room-backed StateFlows self-heal from the wiped store; these caches do not.
        refreshAlarmConfig()
        runCatching { clearSnooze() }
        runCatching { clearBolusAdvice() }
        runCatching { clearRoll() }
        gmiSnapshot = null
        // A plain field on the hot path: without this it stays true after its kv rows are gone.
        runCatching { refreshNightscoutEnabled() }
        // Derived patient data, memoized on an app-lifetime object.
        runCatching { statsRepository.invalidateCache() }
        // Monotonic and in-memory: it would survive and let the forecast run on the empty history.
        runCatching { inferenceController.resetWarmupLatch() }
        // The wipe is done, and nothing else would ever undo `stopForReset`.
        runCatching { watchLink.resumeAfterReset() }
        reevaluateInferenceNow()
    }

    /** A fresh Activity task WITHOUT killing the process, so the FGS and its GATT session survive. */
    fun restartApp() {
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { appContext.startActivity(it) }
    }

    /** `POST /v1/photos`, direct multipart rather than the JSON outbox. Any fault returns a failed
     *  [Result], never a throw. Off-main. */
    suspend fun uploadMealPhoto(tsMs: Long, bytes: ByteArray, ext: String): Result<Unit> =
        withContext(dispatchers.io) {
            runCatching { syncHttpClient.postPhoto(tsMs, bytes, ext); Unit }
        }

    /**
     * Never throws — a failure surfaces as the returned status line. A running-model update is
     * downloaded but STAGED (see [applyModelUpdate]); a new model is adopted on the trailing discovery.
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
     * `applyPending` renames the staged pair in place under an UNCHANGED id, so nothing downstream can
     * tell one checkpoint from the next. The stored forecasts, the accuracy figures and any band
     * correction are dropped: they are evidence about an artifact that is gone. False if none staged.
     */
    suspend fun applyModelUpdate(modelId: String): Boolean = withContext(dispatchers.io) {
        val applied = runCatching { modelSyncCoordinator.applyPending(modelId) }.getOrDefault(false)
        if (applied) {
            runCatching { repository.deletePredictionsForModel(modelId) }
            runCatching { repository.deleteBandCalibration(modelId) }
            // The cached head too: it would be served against the new graph, and the parity check
            // that catches exactly that is memoized per model id.
            runCatching { repository.deleteLorasForModel(modelId) }
            runCatching { repository.clearInfillForModel(modelId) }
            inferenceController.evictHead(modelId)
            inferenceController.refreshModels()
        }
        refreshPendingModelUpdates()
        applied
    }

    /** Beside the models, so `adb pull` reaches it. */
    private val adaptersDir: File
        get() = File(appContext.getExternalFilesDir(null), "adapters")

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

    private val _panelMaskNote = MutableStateFlow<String?>(null)

    val panelMaskNote: StateFlow<String?> = _panelMaskNote.asStateFlow()

    /**
     * What the selected model's descriptor permits a mask to be; null hides the control. Built only
     * from the descriptor: nothing in the app holds a geometry of its own.
     */
    suspend fun maskControls(): MaskControls? {
        // The SELECTED model, not the Lab's pick, which is null until the Lab has been opened once.
        val desc = inferenceController.selectedModelInfo()?.takeIf { it.real }?.descriptor
        val src = repository.authoritativeSourceId() ?: return null
        val span = desc?.let { it.minContextPatches * it.patchSize } ?: CUT_ONLY_CONTEXT_STEPS
        val rows = repository.recentReadings(src, span)
        val newestMeasured = rows.firstOrNull {
            isRealMeasurement(it.provenance, it.flag) && it.bgMgdl != null
        }?.tsMs ?: return null
        // No model, no patch geometry: fall back to the five-minute grid the store keys.
        // `fromDescriptor` refuses reconstruction separately.
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

    fun panelReconstructed(fromMs: Long, toMs: Long): Flow<List<ReconstructedBg>> =
        repository.observeReconstructed(fromMs, toMs)

    /** [geometry] came from where [selection] sits, never from a control. One selection at a time. */
    fun runPanelMask(selection: MaskSelection, geometry: MaskGeometry) {
        // The SAME model [maskControls] took the geometry from: the Lab's pick would decode the span
        // under a descriptor the drag was never bounded by.
        val modelId = inferenceController.selectedModelInfo()?.takeIf { it.real }?.id ?: run {
            _panelMaskNote.value = "No model selected"
            return
        }
        appScope.launch {
            _panelMaskNote.value = "Reconstructing…"
            val note = runCatching {
                labController.runSpan(modelId, selection.startMs, selection.endMs, geometry)
            }.getOrElse { it.message ?: "Reconstruction failed" }
            // Undoable only when a span actually landed: a forecast writes nothing, and a refusal
            // that pushed an entry would offer to take back somebody else's edit.
            if (geometry != MaskGeometry.FORECAST &&
                repository.reconstructedSpanSize(selection.startMs) > 0
            ) {
                pushBgEdit(BgEdit.Fill(selection.startMs))
            }
            _panelMaskNote.value = note
        }
    }

    // Held here, not in the composable: an edit outlives the screen that made it. In memory and
    // session-scoped on purpose — a stack surviving process death would offer to undo something the
    // record has long since agreed about.

    private sealed interface BgEdit {
        data class Cut(val rows: List<BgCut>) : BgEdit

        /** A span a fill drew. Undoing it discards the span; a promoted one refuses. */
        data class Fill(val spanStartMs: Long) : BgEdit
    }

    private val bgEdits = ArrayDeque<BgEdit>()
    private val _bgEditDepth = MutableStateFlow(0)

    val bgEditDepth: StateFlow<Int> = _bgEditDepth.asStateFlow()

    private fun pushBgEdit(edit: BgEdit) {
        bgEdits.addLast(edit)
        // Bounded: the stack holds the geometry of every cut it can undo.
        while (bgEdits.size > BG_EDIT_UNDO_MAX) bgEdits.removeFirst()
        _bgEditDepth.value = bgEdits.size
    }

    /**
     * The only route by which stored physiologic values leave the record. Both ends are snapped here
     * rather than trusted: the repository requires grid slots, which is what the server keys on.
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
                        // A promotion is reversible by demoting; a span a later cut dropped is gone.
                        repository.infillSpan(edit.spanStartMs).isEmpty() -> "That fill is already gone"
                        else -> "Fill was promoted — demote it first"
                    }
                }.getOrElse { it.message ?: "Undo failed" }
            }
        }
    }

    fun discardSpan(spanStartMs: Long) {
        appScope.launch {
            _panelMaskNote.value = runCatching { labController.discard(spanStartMs) }
                .getOrElse { it.message ?: "Discard failed" }
        }
    }

    fun retauSpan(spanStartMs: Long, tau: Double) {
        appScope.launch {
            _panelMaskNote.value = runCatching { labController.retau(spanStartMs, tau) }
                .getOrElse { it.message ?: "Could not move the line" }
            _tauPreview.value = null
        }
    }

    private val _tauPreview = MutableStateFlow<SpanLinePreview?>(null)

    /** Drawing only, never stored. */
    val tauPreview: StateFlow<SpanLinePreview?> = _tauPreview.asStateFlow()

    /** Its own job, cancelled by the next tick: a slider emits faster than a fan can be decoded. */
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
        // THIS model's adapters, not whichever model the Lab has picked.
        val adapters = runCatching { labController.adaptersOf(modelId) }.getOrElse { emptyList() }
        _loraPanel.value = LoraPanelState(modelId = modelId, unavailable = unavailable, adapters = adapters)
    }

    /** In [appScope], NOT the caller's: a fit outlives the screen that started it. */
    fun fitAdapter(modelId: String, spec: LoraFitSpec) {
        if (_loraPanel.value.busy) return
        // Progress lives in the panel's StateFlow, so coming back re-attaches to the running fit.
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

    /** Changes what the model IS: the standing forecast was made by a forecaster that just stopped
     *  existing, so the next cycle runs immediately. */
    suspend fun attachAdapter(modelId: String, adapterId: Long) {
        // A refusal is a RESULT, not an exception.
        runCatching { labController.attach(modelId, adapterId) }
            .onSuccess { refusal ->
                if (refusal != null) {
                    _loraPanel.update { s -> s.copy(error = refusal) }
                } else {
                    // Only when something changed: a refused attach would spend a forward for nothing.
                    reevaluateInferenceNow()
                }
            }
            .onFailure { _loraPanel.update { s -> s.copy(error = it.message ?: "Attach failed") } }
        refreshLoraPanel(modelId)
    }

    /** The typed name is compared by the controller, not by the dialog that collected it. */
    suspend fun overrideAdapterGuard(modelId: String, adapterId: Long, typedName: String) {
        runCatching { labController.overrideGuard(modelId, adapterId, typedName) }
            .onSuccess { refusal ->
                if (refusal != null) _loraPanel.update { s -> s.copy(error = refusal) }
            }
            .onFailure { _loraPanel.update { s -> s.copy(error = it.message ?: "Override failed") } }
        refreshLoraPanel(modelId)
    }

    /** In [appScope] for the reason a fit is: the replay outlives the screen that started it. */
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
            runCatching { repository.deleteBandCalibration(modelId) }
            runCatching { repository.deleteLorasForModel(modelId) }
            runCatching { repository.clearInfillForModel(modelId) }
            runCatching { repository.putKv(kvForecastBackend(modelId), "", System.currentTimeMillis()) }
        }
        refreshPendingModelUpdates()
        reevaluateInferenceNow()
    }

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
     * Silent and guarded: a failure is logged, never a crash. Suspends so the startup path can
     * sequence it after the initial discovery. Deliberately does not touch [modelSyncStatus].
     */
    private suspend fun autoSyncModels(reason: String) {
        if (serverProfileStore.activeEndpoint() == null) return
        runCatching { modelSyncCoordinator.sync() }
            .onFailure { Timber.tag(ModelSyncCoordinator.TAG).w(it, "auto model sync failed (%s)", reason) }
        runCatching { inferenceController.refreshModels() }
        refreshPendingModelUpdates()
    }

    /** For the profile-saved trigger, where nothing has to be ordered against the initial discovery. */
    private fun launchAutoModelSync(reason: String) {
        appScope.launch { autoSyncModels(reason) }
    }

    suspend fun checkServerHealth(): String = runCatching {
        val h = syncHttpClient.health()
        "reachable — status=${h.status}, ${h.ws_clients} ws client(s)"
    }.getOrElse { e ->
        when (e) {
            is NoActiveProfileException -> "no active profile / token configured"
            else -> "unreachable — ${e.message ?: e::class.simpleName}"
        }
    }

    /** The shared curve/PK engine — SPEC §3.3. */
    val curveEngine: CurveEngine by lazy { CurveEngine(nativeCore, dispatchers) }

    private val doseStore: DoseStore by lazy {
        RoomDoseStore(
            engine = curveEngine,
            loggedDoses = database.loggedDoseDao(),
            loggedMeals = database.loggedMealDao(),
            basalSchedules = database.basalScheduleDao(),
        )
    }

    /** SPEC §3.3. */
    val channelBuilder: ChannelBuilder by lazy {
        ChannelBuilder(curveEngine, doseStore, ExerciseChannelSource(::exerciseChannel))
    }

    /** Mixes multi-food GI/custom shapes into one carb-appearance curve. */
    val mealCurveResolver: MealCurveResolver by lazy { MealCurveResolver(curveEngine) }

    val mealsController: MealsController by lazy { MealsController(repository, mealCurveResolver, dispatchers) }

    val insulinController: InsulinController by lazy { InsulinController(repository, curveEngine, dispatchers) }

    val savedMeals: Flow<List<SavedMeal>> get() = mealsController.savedMeals
    val customFoods: Flow<List<Food>> get() = mealsController.customFoods

    /** The last 3 distinct GI-bearing logged meals. */
    val recentMeals: Flow<List<RecentMeal>> get() = repository.observeRecentMeals(3)
    val insulinTypes: Flow<List<InsulinType>> get() = insulinController.types

    /** Idempotent. Also settles an exercise bout the last process died in the middle of. */
    fun startBuilders() {
        appScope.launch {
            mealsController.seedIfEmpty()
            insulinController.seedBuiltinsIfEmpty()
            exerciseController.reconcileOpenSessions(System.currentTimeMillis())
        }
    }

    // A bout's per-5-minute magnitude goes in the wide sample's `exercise` scalar. The bout row and
    // its GPS track are phone-local and cross no wire.

    val exerciseController: ExerciseController by lazy {
        ExerciseController(
            repository,
            dispatchers,
            curveEngine,
            carbEquivPerMin = { settingsStore.currentCarbEquivPerMin() },
        )
    }

    /** Published by [com.t1dm.sensors.ExerciseRecorder]. Null whenever no bout is running. */
    val activeExercise = MutableStateFlow<ActiveExercise?>(null)

    /** Why no bout could START. A bout that IS running carries its reason on
     *  [ActiveExercise.degraded] instead. */
    val exerciseRefusal = MutableStateFlow<String?>(null)

    /** Derived rather than stored, so the two halves can never disagree about which is current. */
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

    val exercise: ExerciseSource get() = exerciseSource

    val latestMood: Flow<Int?> = repository.observeLatestMood()

    val previewCarbCurve: suspend (Double, Double) -> DoubleArray = { grams, gi ->
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        curveEngine.gamma(grams, k, theta, dur)
    }

    /** Bit-for-bit the curve [logBolus]/[logBasal] commit for that preset — both go through
     *  [presetCurve]. By value, not resolved, so the sparkline redraws on a chip tap. */
    val previewDoseCurve: suspend (Double, InsulinPresetSpec) -> DoubleArray = { units, spec ->
        presetCurve(units, spec)
    }

    /** feat 1 / feat 2 over a grid window. The model consumes the COMBINED insulin channel and has
     *  no use for the basal series. Off-main. */
    suspend fun dashboardCurveChannels(gridStartMs: Long, nSteps: Int): ModelChannels {
        val ch = channelBuilder.contextChannels(gridStartMs, nSteps)
        return ModelChannels(ch.carb, ch.insulin, ch.exercise)
    }

    /**
     * Grams of carbohydrate equivalent disposed per bucket, read from the `sample` column. NOT
     * reconstructed from the bout records, and must not be: rebuilding would re-rate every past bout
     * at today's carbohydrate-equivalent setting.
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

    /** Carbs, combined insulin and the BASAL-only sub-channel from ONE gather. Off-main. */
    suspend fun dashboardOverlayChannels(
        gridStartMs: Long,
        nSteps: Int,
    ): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val ch = channelBuilder.overlayChannels(gridStartMs, nSteps)
        return Triple(ch.carb, ch.insulin, ch.basal)
    }

    /**
     * `out[i]` = steps in `[gridStartMs + i·GRID_MS, +GRID_MS)`. Densified here: the read is sparse,
     * and `:feature:dashboard` takes a primitive array rather than any `:data` type.
     */
    suspend fun dashboardStepSeries(gridStartMs: Long, nSteps: Int): IntArray {
        if (nSteps <= 0) return IntArray(0)
        val step = T1dmRepository.GRID_MS
        // NOT-MEASURED sentinel, not zero: a bucket with no row was never watched. A recorded 0
        // stays one, because only buckets a row came back for are overwritten.
        val out = IntArray(nSteps) { StepsFrame.NO_DATA }
        val endMs = gridStartMs + (nSteps - 1).toLong() * step
        for (row in repository.stepSeriesInRange(gridStartMs, endMs)) {
            val i = ((row.ts - gridStartMs) / step).toInt()
            if (i in 0 until nSteps) out[i] = row.steps
        }
        return out
    }

    /**
     * The COMMITTED dose tails over `[rollStartMs, +nFutureSteps·STEP)` (PLAN §3.3).
     * `announced`/`candidate` are empty: the committed doses come from `futureOverrides`' own store
     * reads, so passing them again would double-count. Off-main. */
    suspend fun dashboardFutureChannels(rollStartMs: Long, nFutureSteps: Int): ModelChannels {
        val fc = channelBuilder.futureOverrides(rollStartMs, nFutureSteps, announced = emptyList(), candidate = null)
        // The writer already laid those slots down: the committed future is a read, not a projection.
        return ModelChannels(fc.carb, fc.insulin, fc.exercise)
    }

    /** §3.6-F provenance: logged doses only. */
    suspend fun iobCobNow(): IobCobReadout {
        val now = System.currentTimeMillis()
        // F5: `zeroMs` is the instant the last active insulin decays to zero, from the same gather as
        // the IOB. The widget pulls this on every refresh, so it must not be a second reconstruction.
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

    /** Hours, forward from IOB-zero. DISPLAY ONLY — no §3.6 gate reads this. */
    val dkaTimeline: Flow<DkaTimeline> =
        combine(
            settingsStore.dkaAfterIobZeroH,
            settingsStore.comaAfterDkaH,
            settingsStore.deathAfterComaH,
        ) { a, b, c -> DkaTimeline(a, b, c) }

    /**
     * Held to the SAME calibration threshold as the neural §8.4 correction — one policy constant.
     * This band IS the baseline's interval and decides whether its forecast clears the
     * collapsed-band guard at all.
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
     * Pinned to the fp32 XNNPACK CPU authority (§3.6-E): dose advice runs there whatever the switcher
     * says, so [info] must be [authorityModelInfo]. The displayed backend is carried informationally
     * and can never affect `trustworthy` or a rail.
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

    /** §3.3. The advisor has no pick of its own, so it searches against the insulin last logged. */
    private val bolusResolver = BolusResolver { doseU, atMs ->
        val spec = resolveRapidPreset(null)
        listOf(curveEngine.rapidEvent(doseU, atMs, spec.peakMin, spec.diaMin))
    }

    private val bolusCalculator by lazy { BolusCalculator(rollingForecaster, bolusResolver) }

    /** The GI is pinned, not followed from a setting: one that moved between probes would surface as
     *  the patient's ratio changing. */
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

    /** §3.6-D. Null ⇒ no signal. */
    private val anchorSource = AnchorInfoSource { nowMs -> buildAnchorInfo(nowMs) }

    /** §3.6-F, logged doses only. Null ⇒ store failure. */
    private val iobSource = IobSource { nowMs -> buildIobSnapshot(nowMs) }

    /** §3.6-E. Null ⇒ refusal. */
    private val backendSource = BackendInfoSource {
        val info = inferenceController.authorityModelInfo()
        if (info == null || !info.real) null else calcBackendInfo(info)
    }

    val doseAdvisor: DoseAdvisor by lazy {
        DoseAdvisor(bolusCalculator, anchorSource, iobSource, backendSource, { smoothingWindow() })
    }

    /** Never actuates. */
    sealed interface BolusAdviceUi {
        data object Idle : BolusAdviceUi
        data object Running : BolusAdviceUi
        data class Ready(val result: AdviceResult) : BolusAdviceUi
    }

    val bolusAdvice = MutableStateFlow<BolusAdviceUi>(BolusAdviceUi.Idle)

    /** Called from [com.t1dm.app.service.DoseCalcService] on a cancellable foreground job. */
    suspend fun runBolusAdvice(
        announcedCarbG: Double,
        announcedGi: Double,
        manualTargetMgdl: Double? = null,
        config: CalcConfig? = null,
    ) {
        bolusAdvice.value = BolusAdviceUi.Running
        // Loaded fresh per run.
        val base = config ?: runCatching { settingsStore.currentCalcConfig() }.getOrDefault(CalcConfig())
        // Absent ⇒ the persisted objective stands.
        val cfg = if (manualTargetMgdl != null) base.copy(objective = Objective.HitTargetBg(manualTargetMgdl)) else base
        val now = System.currentTimeMillis()
        val announced: List<CurveEvent> = if (announcedCarbG > 0.0) {
            val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(announcedGi)
            listOf(curveEngine.carbEvent(announcedCarbG, now, k, theta, dur))
        } else emptyList()
        // DEATH also lifts the §3.6-B degeneracy refusal; the rails are already off via currentCalcConfig.
        val result = runCatching { doseAdvisor.recommendBolus(now, announced, cfg, bypassDegeneracyGate = deathModeSnapshot) }
            .getOrElse { AdviceResult.Refused(listOf("Calculator error — ${it.message ?: it::class.simpleName}")) }
        bolusAdvice.value = BolusAdviceUi.Ready(result)
    }

    fun clearBolusAdvice() { bolusAdvice.value = BolusAdviceUi.Idle }

    // Ephemeral UI state, structurally isolated from the safety surfaces: a [RolledForecast] is never
    // written into [inferenceState].predictions, never passed to [doseAdvisor], and never read by the
    // notification or the top-bar indicator. So a 12×-rolled fan can never raise an alert or dose.

    val rolledForecast = MutableStateFlow<RolledForecast?>(null)

    val rollComputing = MutableStateFlow(false)

    private var rollJob: Job? = null

    /**
     * On the fp32 CPU authority, never the GPU: rolls multiply forwards and the GPU is ~4.5× worse per
     * forward. Fail-closed — a missing model, a degenerate roll or any error yields a non-eligible
     * [RolledForecast] with a reason, never a throw. Display only.
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

    fun clearRoll() {
        rollJob?.cancel()
        rollComputing.value = false
        rolledForecast.value = null
    }

    // Isolated as the rolled forecast is: a [SensitivityEstimate] is a type neither [doseAdvisor],
    // [inferenceState], the store nor the outbox accepts, so a probe can never raise an alert, move a
    // rail, or be mistaken for a logged fact.

    /** Null when no model response was obtained; the panels render "N/A" rather than hiding. */
    val sensitivity = MutableStateFlow<SensitivityEstimate?>(null)

    private var sensitivityJob: Job? = null

    /** When a probe was last STARTED, whatever it returned. */
    private var lastProbeAtMs: Long? = null

    /**
     * Re-probe past [SENSITIVITY_TTL_MS], drop past [SENSITIVITY_LAPSE_MS]. Called on a coarse ticker
     * by each displaying panel. Deliberately NOT driven off the inference cycle: `lastCycleTsMs`
     * stops advancing on the thermal, warm-up and no-context paths, where a held figure is stalest.
     */
    fun refreshSensitivityIfStale() {
        val now = System.currentTimeMillis()
        var held = sensitivity.value

        // A selection change invalidates the figure OUTRIGHT: it describes the artifact it was probed
        // on, and age has nothing to say about that. Read from the controller — `selectedId` is true
        // the instant the user taps, unlike `predictions`, which is empty for a model yet to forecast.
        val selectedModelId = runCatching { inferenceController.authorityModelInfo()?.id }.getOrNull()
        if (held != null && held.modelId != selectedModelId) {
            sensitivity.value = null
            lastProbeAtMs = null   // the clock belongs to the old model too
            held = null
        }

        // Absolute, not elapsed: a backwards clock correction must expire a held estimate, not
        // freeze it. Before every other branch, so the lapse is enforced on each call.
        val age = held?.let { Math.abs(now - it.atMs) }
        if (age != null && age >= SENSITIVITY_LAPSE_MS) sensitivity.value = null

        // Warm-up publishes no forecast at all. Drop what is held rather than only skipping the
        // re-probe: warm-up can begin with an estimate already up.
        if (inferenceState.value.warmup != null) {
            sensitivity.value = null
            return
        }
        if (age != null && age < SENSITIVITY_TTL_MS) return
        // Rate-limit ATTEMPTS, not just successes: a probe that withholds leaves nothing to age. A
        // withheld one retries on the shorter interval.
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

    /** Journals the dose the user says they administered; never actuates. A 0 U acceptance logs
     *  nothing and returns NO handle — an Undo on rowid 0 would delete whatever that happens to be. */
    suspend fun acceptAdvisedBolus(units: Double): LogHandle? =
        if (units.isFinite() && units > 0.0) logBolus(units) else null

    private suspend fun buildAnchorInfo(nowMs: Long): AnchorInfo? {
        val srcId = repository.authoritativeSourceId() ?: return null
        val recent = repository.recentReadings(srcId, 36) // ~3 h of 5-min grid
        if (recent.isEmpty()) return null
        val lastMeasured = recent
            .filter { isRealMeasurement(it.provenance, it.flag) && it.bgMgdl != null }
            .maxByOrNull { it.tsMs }
        // The newest row OUTRIGHT: filtering it would make `warmup` constant-false whenever the
        // window holds one NORMAL measured row.
        val newest = recent.maxByOrNull { it.tsMs }!!
        // A promoted RECONSTRUCTION counts as fabricated. Nothing gates on the fraction: these three
        // fields are the §3.6-F card's disclosure and nothing else.
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

    /**
     * How long a freshly logged push is held back — the window in which the Logs panel can still
     * withdraw it whole. It delays ONLY the outbox row: the forecast and IOB/COB read the local
     * `logged_*` rows, never the queue.
     */
    private suspend fun pushHoldMs(): Long =
        settingsStore.currentPushHoldMin().toLong() * 60_000L

    /** The repository grid-snaps `ts` and mints the `client_id`; the push is built from the PERSISTED
     *  entity, so app and wire agree on one grid ts and one id (§3.1/§3.2). */
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

    /** Persisted by [MealsController], which resolves the combined appearance curve into
     *  `customCurve`, grid-snaps `ts` and mints the `client_id`. */
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

    /** The only set of insulins a dose write can name. */
    suspend fun insulinPresetCatalog(): List<InsulinPresetSpec> = curveEngine.presetCatalog()

    /** The single place a preset becomes numbers, so preview and commit cannot diverge. */
    private suspend fun presetCurve(units: Double, spec: InsulinPresetSpec): DoubleArray = when (spec.family) {
        InsulinFamily.RapidExp -> curveEngine.expAction(units, spec.peakMin, spec.diaMin)
        InsulinFamily.BasalBateman -> curveEngine.bateman(units, spec.diaMin, spec.kaPerHour, spec.kePerHour)
    }

    /** Throws on an empty catalogue rather than substituting a curve: a dose row carrying an invented
     *  PK would be worse than no row at all. */
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

    /** The sticky memory of the last committed dose of that kind, else the head of the catalogue. */
    suspend fun resolvedRapidLabel(): String = resolveRapidPreset(null).label

    suspend fun resolvedBasalLabel(): String = resolveBasalPreset(null).label

    /**
     * `units > 0.0` alone admits +Infinity, which settles as NaN in IOB and defeats the §3.6-C ceiling
     * outright — every comparison against NaN is false. Fails closed: no row rather than a poisoned one.
     */
    private fun requireLoggableDose(units: Double) {
        require(units.isFinite() && units > 0.0) { "Dose units must be positive and finite (was $units)." }
    }

    /** A null [presetLabel] means the caller had no pick to offer and falls back to the last insulin
     *  logged; anything else is honoured. The push is built from the PERSISTED entity (§3.1/§3.2). */
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

    /** Carries the preset's DIA and ka/ke, so the Bateman reconstructs analytically. Null resolves as
     *  in [logBolus]. */
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

    /** Only AFTER the row persists, and only when the caller named a preset: a fallback resolution
     *  has expressed no preference and must not overwrite one. */
    private suspend fun rememberLoggedPreset(spec: InsulinPresetSpec, requestedLabel: String?) {
        if (requestedLabel == null) return
        when (spec.family) {
            InsulinFamily.RapidExp -> settingsStore.setLastRapidPreset(spec.label)
            InsulinFamily.BasalBateman -> settingsStore.setLastBasalPreset(spec.label)
        }
    }

    /** Persisted by [InsulinController], which resolves the type's PK action curve, grid-snaps `ts`
     *  and mints the `client_id`. Pushed exactly as [logBolus] and [logBasal] are. */
    suspend fun logTypedDose(type: InsulinType, units: Double): LogHandle {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val dose = insulinController.logDose(type, units)
        val outboxId = outboxEnqueuer.enqueueDose(dose.toDoseEventDto(), now, holdMs = pushHoldMs())
        mirrorToNightscout { nightscoutEnqueuer.enqueueDose(dose, now, holdMs = pushHoldMs()) }
        reforecastAfterCurveWrite()
        val kind = if (type.kind == InsulinKind.BOLUS) "bolus" else "basal"
        return dose.handle(outboxId, "${fmtAmount(units)} U $kind · ${type.name}")
    }

    /** The deletion travels as a tombstone on the same upsert the create rode, ordered against it by
     *  `updated_at`, so it lands whatever the push had already done. */
    suspend fun undoLog(handle: LogHandle) {
        when (handle.kind) {
            LoggedEventKind.MEAL -> tombstoneAndPushMeal(handle.rowId)
            LoggedEventKind.DOSE -> tombstoneAndPushDose(handle.rowId)
        }
        reforecastAfterCurveWrite()
    }

    /** Pushed under the SAME dedup key the create used, and marked pushed only after the enqueue
     *  returns, so a death between the two leaves it for [replayTombstones]. */
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

    /** Covers a death between the delete transaction and the enqueue, and a tombstone the queue's
     *  size cap evicted. Returns how many were re-filed. */
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

    /** Swallowing, deliberately: the clinical record is already committed, and nothing here may
     *  reach the receipt the caller is about to hand the user. */
    private suspend fun mirrorToNightscout(enqueue: suspend () -> Long) {
        if (!repository.nightscoutBridgeEnabled) return
        runCatching { enqueue() }
            .onFailure { Timber.tag("Nightscout").w(it, "mirror enqueue failed") }
    }

    /**
     * ONLY where the original mirror can still be recalled: `/api/v1` offers no update for a landed
     * treatment, so re-sending one would file a SECOND beside it and double-count the insulin. The
     * withdrawal runs whether or not the bridge is currently on.
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

    /**
     * ONE feed for the Logs panel's list and the BG panel's marks, which reduces it to
     * [com.t1dm.core.model.LogMarker] at its own edge, so a mark and its row share a list position.
     * No queue join: the outbox has no SENT state, so an absent row means sent, rejected or evicted.
     */
    val loggedEntries: Flow<List<LoggedEntry>> = combine(
        repository.observeRecentLoggedMeals(LOG_FEED_LIMIT),
        repository.observeRecentLoggedDoses(LOG_FEED_LIMIT),
    ) { meals, doses ->
        val rows = meals.map { it.toLoggedEntry() } + doses.map { it.toLoggedEntry() }
        rows
            // Totally ordered, not merely sorted by time: two rows can share a grid slot, and an
            // unstable order would reshuffle the list under the reader on every emission.
            .sortedWith(
                compareByDescending<LoggedEntry> { it.tsMs }
                    .thenBy { it.kind }
                    .thenByDescending { it.rowId },
            )
            .take(LOG_FEED_LIMIT)
    }

    /** Unconditional: the same tombstone path the undo takes, with no refusal branch left to have. */
    suspend fun deleteLoggedEntry(entry: LoggedEntry) {
        when (entry.kind) {
            CurveKind.CARB -> tombstoneAndPushMeal(entry.rowId)
            CurveKind.INSULIN -> tombstoneAndPushDose(entry.rowId)
        }
        reforecastAfterCurveWrite()
    }

    /**
     * Keeps the row's identity. The shape is re-resolved from the edited GI as the logging path
     * resolves it, and the stored curve is rescaled by the repository writer. The push supersedes
     * whatever is queued under that key.
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

    /** The dose twin of [editLoggedMeal]. [requireLoggableDose] guards the edit as it guards a write. */
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

    /** Minutes. */
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
        // Carried as STORED, never phrased here: `:core:design` owns how either reads.
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
        // The insulin the writer persisted — the curve this row reconstructs through.
        detail = note,
        updatedAtMs = updatedAt,
        mutatedAtMs = mutatedAtMs,
    )

    /** Integral amounts read as "45", a half unit as "4.5". */
    private fun fmtAmount(v: Double): String =
        if (v == Math.rint(v) && !v.isInfinite()) v.toLong().toString() else "%.1f".format(v)

    /** Folded into the wide sample; mood rides the six-scalar `POST /v1/ingest`, with no push of its
     *  own. */
    suspend fun saveMood(mood: Int) {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val gridTs = snapToGrid(now)
        repository.recordMood(gridTs, tz, mood, now)
    }

    private fun tzOffsetMin(nowMs: Long): Int =
        java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(nowMs)).totalSeconds / 60

    private fun snapToGrid(ts: Long): Long = Math.floorDiv(ts + 150_000L, 300_000L) * 300_000L

    val authoritativeSource: Flow<CgmSourceDescriptor?> = repository.observeAuthoritativeSource()

    val allSources: Flow<List<CgmSourceDescriptor>> = repository.observeSources()

    /**
     * Null means "whichever is authoritative". Deliberately not persisted: looking at a
     * non-authoritative sensor withholds the forecast, and a mode that does that must not survive a
     * restart silently.
     */
    private val viewedSourceId = MutableStateFlow<com.t1dm.core.model.CgmSourceId?>(null)

    /** The `active` re-check makes a stale choice self-correcting: a sensor deactivated while being
     *  looked at falls back to the authoritative one on the next emission. */
    val viewedSource: Flow<CgmSourceDescriptor?> =
        combine(viewedSourceId, authoritativeSource, repository.observeActiveSources()) { viewed, auth, active ->
            viewed?.let { id -> active.firstOrNull { it.id == id } } ?: auth
        }.distinctUntilChanged()

    /** The chart's dissolve key. The id rather than the descriptor: a descriptor re-emits on any
     *  field rewrite, dissolving a chart still drawing the same sensor. */
    val viewedSourceKey: Flow<String?> =
        viewedSource.map { it?.id?.value }.distinctUntilChanged()

    /**
     * The forecast overlay, hindsight sweep and rolled fan are withheld while this is true: none was
     * computed from this sensor. Derived from [viewedSource], not the raw id, which lingers after a
     * deactivation and would withhold the forecast forever with no way to clear it.
     */
    val viewingNonAuthoritative: Flow<Boolean> =
        combine(viewedSource, authoritativeSource) { viewed, auth ->
            viewed != null && auth != null && viewed.id != auth.id
        }.distinctUntilChanged()

    /**
     * Resolved here, not in the composable: the chip recomposes on every reading and clock tick. With
     * names hidden this is the persisted ordinal and nothing else — one family builds its advertised
     * name out of the number printed on the sensor.
     */
    val viewedSourceLabel: Flow<String?> =
        combine(viewedSource, settingsStore.showSensorNames) { d, showNames ->
            d?.incidentalName(showNames)
        }.distinctUntilChanged()

    /** The same, for the believed sensor. */
    val authoritativeSourceLabel: Flow<String?> =
        combine(authoritativeSource, settingsStore.showSensorNames) { d, showNames ->
            d?.incidentalName(showNames)
        }.distinctUntilChanged()

    /**
     * Steps to the next ACTIVE sensor, in the order the phone met them. Read straight off the
     * registry's StateFlows: a suspend point would put a frame between the tap and the trace changing.
     * Fewer than two active sensors resets the view rather than no-ops.
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
        // Null, not the id, so there is only one way to say "looking at the authoritative one".
        viewedSourceId.value = if (next == authoritativeId) null else next
    }

    /**
     * How far back the panel has loaded. Moves BACKWARDS only, via [extendHistoryBackTo]. Windowed
     * because it re-runs on every `cgm_reading` write over a store that is never pruned; the pannable
     * floor is read separately, through [historyFloorMs].
     */
    private val historyLoadedFromMs = MutableStateFlow(
        System.currentTimeMillis() - INITIAL_HISTORY_WINDOW_MS,
    )

    /** Clamped forward to now, and monotone backwards so panning out and back does not re-query. */
    fun extendHistoryBackTo(fromMs: Long) {
        val target = fromMs.coerceAtMost(System.currentTimeMillis())
        historyLoadedFromMs.update { current -> if (target < current) target else current }
    }

    /**
     * The VIEWED source's whole MODEL CLASS, one reading per grid slot, a real measurement outranking
     * a warm-up or interpolated one and the viewed source breaking the tie. Class-scoped where
     * everything downstream of a reading is source-scoped: nothing about authority moves with it (§3.1).
     */
    val dashboardReadings: Flow<List<CgmReading>> =
        combine(viewedSource, historyLoadedFromMs) { d, from -> d to from }
            .flatMapLatest { (d, from) ->
                if (d == null) flowOf(emptyList())
                else repository.observeReadingsForSensorModel(d.sensorModelId, d.id, from, Long.MAX_VALUE)
            }

    /**
     * Where the record begins for the viewed class, null while it holds nothing — the pannable floor,
     * INSTEAD of the oldest reading [dashboardReadings] happens to hold, which would put a meal logged
     * before the window out of reach.
     */
    val historyFloorMs: Flow<Long?> = viewedSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeOldestTsForSensorModel(d.sensorModelId)
    }

    /** mg/dL, oldest→newest. Bounded at the QUERY: a settings screen has no business scanning the
     *  whole store. */
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

    /** Read over the WHOLE store: the panel pans the entire history, and strokes are few,
     *  display-only and unindexed by source. */
    val paintStrokes: Flow<List<PaintStroke>> = repository.observePaintStrokes(0L, Long.MAX_VALUE)

    /** Returns the minted row id — what makes the undo stack and the eraser addressable. */
    suspend fun addPaintStroke(stroke: PaintStroke): Long = repository.addPaintStroke(stroke)

    /** Whole strokes only: the eraser and undo never work in units of geometry. */
    suspend fun deletePaintStroke(id: Long) = repository.deletePaintStroke(id)

    val latestReading: Flow<CgmReading?> = authoritativeSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeLatestReading(d.id)
    }

    /**
     * The pair is the guard, not a convenience: [latestReading] is the newest row whatever its
     * provenance, and a promoted reconstruction is one. A single value could put a model's number on
     * the always-on notification and the widgets.
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

    /** For the bottom bar's sensor chip, which must describe ONE sensor. [latestReading] stays
     *  authoritative for the BG value, the trend arrow and the staleness verdict beside it. */
    val viewedReading: Flow<CgmReading?> = viewedSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeLatestReading(d.id)
    }


    /**
     * §3.6-F, recomputed off-main on any trigger that can change it. All three arms are CHANGE
     * SIGNALS: nothing is read from them, and each is the cheapest observation of a table Room
     * invalidates whole. `mapLatest` cancels an in-flight compute on a newer trigger.
     */
    val iobCob: StateFlow<IobCobReadout?> =
        merge(
            latestReading.map { },
            repository.observeSampleWrites().map { },
            // Meal/dose logs do not project onto `sample` (§3.1), so the sample signal never fires on
            // one; without this a just-logged dose reads 0 U until the next reading.
            repository.logEvents.map { },
        )
            .onStart { emit(Unit) }
            .mapLatest { runCatching { iobCobNow() }.getOrNull() }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)

    val serviceRunning = MutableStateFlow(false)

    /** §3.6-A, republished for the UI. Pushed from the FGS's own state collector; its consumer is
     *  cosmetic and can never influence WHEN the engine fires. */
    val alarmState = MutableStateFlow(AlarmState.CLEAR)

    /**
     * `PredictiveAlertPresenter` is a SECOND, independent writer to the vibrator, so this is a second
     * edge any actuator interlock has to watch. The GATED decision, not the raw forecast.
     */
    val predictiveAlertRaised = MutableStateFlow(false)

    /** [fromMs] through to the newest reading. One shot, class-scoped, never subscribed: a reading
     *  arriving mid-run must not rebuild the ground under the car. */
    suspend fun gameReadings(fromMs: Long): List<CgmReading> {
        val source = repository.observeAuthoritativeSource().first() ?: return emptyList()
        return repository.observeReadingsForSensorModel(source.sensorModelId, source.id, fromMs, Long.MAX_VALUE).first()
    }

    /** [gameReadings]'s shape, bounded at BOTH ends: a review is a fixed picture of a finished bout. */
    suspend fun sessionReadings(fromMs: Long, toMs: Long): List<CgmReading> {
        val source = repository.observeAuthoritativeSource().first() ?: return emptyList()
        return repository.observeReadingsForSensorModel(source.sensorModelId, source.id, fromMs, toMs).first()
    }

    val graphSettings: GraphSettingsStore by lazy { GraphSettingsStore(repository) }

    val graphRange: Flow<BgRange> get() = graphSettings.range
    val graphWindowHours: Flow<Int> get() = graphSettings.windowHours

    suspend fun setGraphWindowHours(hours: Int) = graphSettings.setWindowHours(hours)

    suspend fun setGraphRange(minMgdl: Int, maxMgdl: Int) = graphSettings.setRange(minMgdl, maxMgdl)

    /** Ages the reachability lights without a new emission. 15 s against a 5-min data cadence. */
    private val reachabilityTicker: Flow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(15_000L) }
    }

    /** Neutral-typed, so `:feature:dashboard` never sees `:sync` or `:watch`. */
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

    /** Null on either side ⇒ "no signal" in the WCH/CGM lights. */
    val bgSignals: Flow<BgSignals> by lazy {
        combine(latestReading, watchSecurity) { latest, watch ->
            BgSignals(cgmRssi = latest?.rssi, watchRssi = watch.rssiDbm)
        }
    }

    /** Per-channel activity tokens: a change fires a one-shot flash, the value itself is opaque. */
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

    /** Days, 3–30. Only the TOTAL is a setting; the elapsed part comes from the sensor (see
     *  [sensorExpiryMs]). */
    val sensorLifeDays: Flow<Int> get() = settingsStore.sensorLifeDays

    // Read synchronously off @Volatiles to decide whether to raise the keep-screen-on AOD surface.
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

    /** Stores the ABSOLUTE expiry, so the countdown survives restarts. */
    suspend fun setSensorLifetime(days: Int, hours: Int, minutes: Int) {
        val durationMs = ((days.toLong() * 24 + hours) * 60 + minutes) * 60_000L
        settingsStore.setSensorExpiryMs(System.currentTimeMillis() + durationMs)
    }

    suspend fun clearSensorLifetime() = settingsStore.clearSensorExpiry()

    /** False on a fresh install and after a full reset. */
    val disclaimerAcknowledged: Flow<Boolean> get() = settingsStore.disclaimerAcknowledged

    suspend fun acknowledgeDisclaimer() = settingsStore.acknowledgeDisclaimer()
    suspend fun setSensorLifeDays(days: Int) = settingsStore.setSensorLifeDays(days)

    /**
     * Epoch-ms: the sensor's own start, anchored off the latest reading's `minFromStart`, plus the
     * configured total service life. Null until a reading carrying a sensor age arrives.
     */
    val sensorExpiryMs: Flow<Long?> by lazy {
        combine(latestReading, sensorLifeDays) { latest, lifeDays ->
            val mfs = latest?.minFromStart ?: return@combine null
            val startMs = latest.tsMs - mfs.toLong() * 60_000L
            startMs + lifeDays.toLong() * 86_400_000L
        }
    }

    /**
     * Epoch-ms, or null unless warm-up is genuinely in progress — anything missing fails closed rather
     * than inventing an instant. Anchored on `rxWallMs`, NOT the grid stamp: `tsMs` holds still across
     * a slot while `minFromStart` ticks every minute, so the countdown would run visibly backwards.
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

    val statsRepository: StatsRepository by lazy { StatsRepository(repository, nativeCore, dispatchers) }

    private val statsSource by lazy { AppStatsSource(statsRepository, syncHttpClient, nativeCore, dispatchers) }

    /** App-lifetime, so the window and composite survive Activity churn. */
    val statsViewModel: StatsViewModel by lazy { StatsViewModel(statsSource, appScope) }

    /** Fires `updateAll` after the kv commit: the FGS only pushes one while it is alive, so a switch
     *  made with it down would leave the widget on its stale composition. */
    fun setUnitSpace(space: com.t1dm.core.model.UnitSpace) {
        appScope.launch {
            statsRepository.setUnitSpace(space)
            runCatching { com.t1dm.app.widget.GlucoseWidget().updateAll(appContext) }
        }
    }

    /** `mean_hr`/`bg_hr_corr` are not in the phone's [AdvancedStats] yet (§8.2) ⇒ 0. */
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

    /** §3.6 — the sole stats producer; the server stores these verbatim. Each window is deduped
     *  ≤1/window/day inside [OutboxEnqueuer.enqueueStats]. */
    private suspend fun pushStats(nowMs: Long) {
        for (w in StatsWindow.entries) {
            runCatching { outboxEnqueuer.enqueueStats(statsRepository.localStats(w).toPush(w, nowMs), nowMs) }
                .onFailure { Timber.w(it, "stats push failed for %s", w.wire) }
        }
    }

    // A CLEAN REMOVABLE SEAM: deleting this block, AppWatchWiring and the module excises the whole
    // feature. The crypto is the uniffi-backed WatchSession (docs/WATCH_BLE.md); `:watch`'s own
    // loopback session is a host-test double only.

    /** Shared by the watch push and [lowPowerActive]. Reads its knobs fresh per call. */
    private val lowPower: AndroidLowPowerProvider by lazy {
        AndroidLowPowerProvider(
            context = appContext,
            enabled = { settingsStore.currentLowPowerEnabled() },
            thresholdPercent = { settingsStore.currentLowPowerPercent() },
            useOsSaver = { settingsStore.currentLowPowerUseOsSaver() },
        )
    }

    /** Polled off-main every 30 s. A read failure fails OPEN — not low-power. */
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
                // The live @Volatile per glance, so a Settings threshold edit reaches the watch.
                thresholdsProvider = { alarmConfig.thresholds },
                lossMinProvider = { alarmConfig.lossMin },
            ),
            lowPower = lowPower,
            dispatchers = dispatchers,
            config = WatchLinkConfig(enabled = true, autoConnect = true),
        )
    }

    val watchSecurity: StateFlow<WatchSecurityState> get() = watchLink.state

    fun pairWatch() = watchLink.beginPairing()
    fun confirmWatchSas() = watchLink.confirmSas()
    fun rotateWatchKeys() = watchLink.rotate()
    fun unpairWatch() = watchLink.unpair()

    /** The forecast was conditioned on the outgoing sensor's history. */
    fun invalidateInferenceOnSourceChange() = inferenceController.onCgmSourceChanged()

    fun makeAuthoritativeCgm(id: String) =
        registry.setAuthoritative(com.t1dm.core.model.CgmSourceId(id))

    /** Additive — nothing else stops. */
    fun activateCgm(id: String) = registry.activate(com.t1dm.core.model.CgmSourceId(id))

    /** Refused for the authoritative one. */
    fun deactivateCgm(id: String) = registry.deactivate(com.t1dm.core.model.CgmSourceId(id))

    /** A display flag: the source stays on record, so its readings stay in the panel's history. */
    fun hideCgm(id: String) = registry.hide(com.t1dm.core.model.CgmSourceId(id))

    /**
     * Minutes. Routed through the registry, not straight at the repository, so its in-memory
     * descriptor set moves with the column: a later re-sighting re-upserts that set and a stale copy
     * would overwrite the edit. Nothing to do with the inference warm-up (`setWarmupHours`).
     */
    suspend fun setSensorWarmupMin(minutes: Int) {
        val id = repository.authoritativeSourceId() ?: return
        registry.setWarmupWindowMin(id, minutes)
    }

    /** Suspends in low-power mode. */
    suspend fun pushToWatch(nowMs: Long) = watchLink.pushNow(nowMs)

    companion object {
        /** Hysteresis: once tripped, inference resumes only at `thresholdC - this`, so a reading
         *  hovering at the threshold cannot flap the forecast cycle to cycle. */
        const val THERMAL_RESUME_MARGIN_C = 2.0

        /** The mixed-meal default the bolus advisor also falls back to. */
        const val PROBE_GI = 55.0

        const val SENSITIVITY_TTL_MS = 30 * 60_000L

        /** One inference cycle: a recovering anchor is picked up without the retry costing more than
         *  the cycle running beside it. */
        const val SENSITIVITY_RETRY_MS = 5 * 60_000L

        /** Past this the figures describe a context — circadian phase, IOB, meal state — that is no
         *  longer the patient's. */
        const val SENSITIVITY_LAPSE_MS = 2 * 60 * 60_000L
    }
}
