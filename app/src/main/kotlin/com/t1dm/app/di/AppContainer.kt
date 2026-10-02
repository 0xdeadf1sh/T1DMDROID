package com.t1dm.app.di

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.PowerManager
import com.t1dm.app.notify.BgDirection
import com.t1dm.app.notify.GlanceReadings
import com.t1dm.app.notify.TREND_FIT_POINTS
import com.t1dm.app.notify.directionOf
import com.t1dm.alerts.ActiveAlarm
import com.t1dm.alerts.AlarmConfig
import com.t1dm.alerts.AlarmEngine
import com.t1dm.alerts.AlarmState
import com.t1dm.alerts.SnoozeState
import com.t1dm.alerts.AlertActuatorConfig
import com.t1dm.alerts.LiveConfig
import com.t1dm.alerts.VibrationPreset
import androidx.glance.appwidget.updateAll
import com.t1dm.app.cgm.AppCgmRepository
import com.t1dm.app.inference.KvArtifactLedger
import com.t1dm.app.inference.KvSelectionStore
import com.t1dm.app.inference.KvTelemetryStore
import com.t1dm.app.inference.RoomBgHistoryProvider
import com.t1dm.app.backup.BackupManager
import com.t1dm.app.settings.ConfigBackup
import com.t1dm.app.settings.SettingsStore
import com.t1dm.app.BuildConfig
import com.t1dm.feature.settings.AboutInfo
import com.t1dm.app.sync.RoomPredictionStore
import com.t1dm.app.sync.SyncManager
import com.t1dm.app.watch.AndroidLowPowerProvider
import com.t1dm.app.watch.AppWatchExtendedSource
import com.t1dm.app.watch.AppWatchGlanceSource
import com.t1dm.app.watch.RoomWatchStores
import com.t1dm.app.watch.UniffiWatchCodec
import com.t1dm.watch.WatchHub
import com.t1dm.watch.WatchLinkConfig
import com.t1dm.watch.WatchSecurityState
import com.t1dm.watch.ble.AndroidWatchCentral
import com.t1dm.watch.ble.WatchScanner
import com.t1dm.watch.proto.WatchDisplay
import com.t1dm.watch.proto.WatchPalette
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.drop
import com.t1dm.app.watch.UniffiWatchSessionFactory
import com.t1dm.core.ble.remoteLeDeviceCompat
import com.t1dm.cgm.AidexXFamilyDriver
import com.t1dm.cgm.ConnectedCgmRegistry
import com.t1dm.cgm.Ct5FamilyDriver
import com.t1dm.cgm.Libre3FamilyDriver
import com.t1dm.cgm.UniffiAidexSession
import com.t1dm.cgm.UniffiCt5Session
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.isRealMeasurement
import com.t1dm.core.model.AlarmFanEdges
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
import com.t1dm.core.model.GamePropDensity
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
import com.t1dm.core.design.LogEdit
import com.t1dm.core.design.exerciseKindLabel
import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.ExerciseSession
import com.t1dm.app.stats.AppStatsSource
import com.t1dm.core.model.SpanLinePreview
import com.t1dm.data.BgCut
import com.t1dm.data.T1dmRepository
import com.t1dm.ui.graph.OverlayInput
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
import com.t1dm.feature.dashboard.LinkHealth
import com.t1dm.feature.dashboard.ReachLight
import com.t1dm.watch.WatchLinkPhase
import com.t1dm.data.curve.ChannelBuilder
import com.t1dm.data.curve.ExerciseDisposal
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.curve.ExerciseChannelSource
import com.t1dm.data.curve.MealCurveResolver
import com.t1dm.data.curve.RoomDoseStore
import com.t1dm.data.exercise.ExerciseController
import com.t1dm.data.meals.InsulinController
import com.t1dm.data.meals.MealsController
import com.t1dm.data.stats.StatsRepository
import com.t1dm.feature.exercise.ExerciseSource
import com.t1dm.feature.stats.StatsViewModel
import com.t1dm.core.model.Food
import com.t1dm.core.model.MealComponent
import com.t1dm.core.model.RecentMeal
import com.t1dm.core.model.InsulinChoice
import com.t1dm.core.model.InsulinKind
import com.t1dm.core.model.InsulinType
import com.t1dm.core.model.SavedMeal
import com.t1dm.core.model.TempUnit
import com.t1dm.data.db.AppDatabase
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.SampleEntity
import com.t1dm.inference.BG_SERIES_ROW_MARGIN
import com.t1dm.inference.assembleBgSeries
import com.t1dm.core.model.BacktestRefusal
import com.t1dm.core.model.BacktestSensor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ForecastWindowSet
import com.t1dm.core.model.ModelBacktest
import com.t1dm.core.model.ModelDescriptor
import kotlin.coroutines.cancellation.CancellationException
import com.t1dm.data.db.toBlob
import com.t1dm.data.db.LoggedExerciseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.app.lab.LabController
import com.t1dm.feature.models.LoraFitSpec
import com.t1dm.feature.models.LoraFitProgress
import com.t1dm.feature.models.LoraPanelState
import com.t1dm.inference.HeadCache
import com.t1dm.inference.ContextChannelSource
import com.t1dm.inference.EventOnsetSource
import com.t1dm.inference.LoraStore
import com.t1dm.inference.ModelChannels
import com.t1dm.inference.FutureOverrideSource
import com.t1dm.inference.InferenceController
import com.t1dm.inference.InferenceControllerDefaults
import com.t1dm.inference.ProbeInsulinPort
import com.t1dm.inference.buildInferenceController
import com.t1dm.data.curve.GiToGamma
import com.t1dm.sync.QueueDrainer
import com.t1dm.sync.KeystoreTokenStore
import com.t1dm.sync.TokenStore
import com.t1dm.sync.nightscout.NightscoutClient
import com.t1dm.sync.nightscout.NightscoutConfigStore
import com.t1dm.sync.nightscout.NightscoutEnqueuer
import com.t1dm.sync.nightscout.OkHttpNightscoutClient
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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

private const val KEY_LIBRE3_ACCOUNT = "libre3.accountId"

private const val KEY_LIBRE3_REGION = "libre3.region"
private const val WARMUP_HOURS_MAX = 72

/** Long enough for a real excursion, short enough that a 25-sample filter still fits inside it. */
private const val SMOOTHING_PREVIEW_HOURS = 3L

/** Covers every window the panel offers; a re-query stays a few thousand rows, not a lifetime. */
private const val INITIAL_HISTORY_WINDOW_MS = 30L * 24 * 3_600_000L

/** Cut is by time, not by which table is busier — bounded per table and on the merged list. */
private const val LOG_FEED_LIMIT = 400

/** `SPEC/invariants.md` §6.2, §6.3. The longest also fixes the window the suite scores. */
private val ACCURACY_HORIZONS_MIN = listOf(30, 60, 120)

/** Windows per horizon below which a row is shown as insufficient. */
private const val ACCURACY_MIN_SAMPLES = 6

/** Shared by the accuracy suite and the band fit: same fortnight the figures are scored on. */
private const val ACCURACY_WINDOW_DAYS = 14

/** `SPEC/inference.md` §8.4. Floor on the calibration split (0.7 of set: 144 needs 206 windows). */
private const val CONFORMAL_MIN_CAL_WINDOWS = 144

/** Progress events between panel updates: an hour of origins. */
private const val BACKTEST_PROGRESS_EVERY = 12

/** Mirrors `T1DMAI`'s `EXCURSION_PRECISION_TOLERANCE_MGDL`; absent from `invariants.md` §6.1. */
private const val EXCURSION_PRECISION_TOLERANCE_MGDL = 10.0

/** Each Cut entry carries every row it removed, so the stack grows with how much was cut. */
private const val BG_EDIT_UNDO_MAX = 32

/** A day of five-minute slots — the edit-mode reach when no model is loaded to size it. */
private const val CUT_ONLY_CONTEXT_STEPS = 288

/** Gathered here because combine's typed arity is 5. */
private data class CgmRaw(
    val sources: List<CgmSourceDescriptor>,
    val authoritativeId: com.t1dm.core.model.CgmSourceId?,
    val activeIds: Set<com.t1dm.core.model.CgmSourceId>,
    /** The active sensors the radio budget carries; the rest wait. */
    val admittedIds: Set<com.t1dm.core.model.CgmSourceId>,
)

/** Per-sensor head, same reason: the per-sensor combine carries six flows. */
private data class CgmSensorHead(
    val status: com.t1dm.core.model.CgmSourceStatus,
    val rssiDbm: Int?,
    val telemetry: com.t1dm.core.model.CgmSourceTelemetry?,
    val bindable: Boolean,
    val sensorStartMs: Long?,
)

/** Wall-clock ms of minFromStart 0: the family's own anchor, else the reading's count. */
private fun sensorStartMs(latest: CgmReading, heldStartMs: Long?): Long? =
    heldStartMs ?: latest.minFromStart?.let { latest.tsMs - it.toLong() * 60_000L }

/** At [latest]; floored at 0, since the grid stamp can land just before the anchor. */
private fun sensorAgeMin(latest: CgmReading, heldStartMs: Long?): Int? =
    sensorStartMs(latest, heldStartMs)?.let { ((latest.tsMs - it) / 60_000L).coerceAtLeast(0L).toInt() }

private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** BG only; null BG = 0.0, which the reduction excludes. */
private fun CgmReading.toStatSample() = com.t1dm.core.model.StatSample(
    tsMs = tsMs,
    tzOffsetMin = tzOffsetMin,
    bgMgdl = bgMgdl?.toDouble() ?: 0.0,
    carbsG = null,
    bolusU = null,
    basalU = null,
    steps = null,
    mood = null,
)


/** The composition root, built once in [com.t1dm.app.T1dmApplication]. */
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

    /** Debug-only: reads once per process and deletes on success. Null on release builds. */
    private fun ct5ImportSource(): com.t1dm.cgm.Ct5ImportSource? {
        if (!BuildConfig.DEBUG) return null
        // `File(null, name)` would silently become a relative path in the working directory.
        val dir = appContext.getExternalFilesDir(null) ?: return null
        val file = java.io.File(dir, CT5_IMPORT_FILE)
        return object : com.t1dm.cgm.Ct5ImportSource {
            override suspend fun read(): String? = withContext(dispatchers.io) {
                runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
            }

            override suspend fun consume() {
                withContext(dispatchers.io) { runCatching { file.delete() } }
            }
        }
    }

    private val cgmRepository by lazy {
        AppCgmRepository(repository)
    }

    /** main only: refuses to store a reading whose sensor is past its stated/rated life. */
    private val gatedCgmRepository: com.t1dm.cgm.CgmRepository by lazy {
        com.t1dm.app.cgm.ExpiryGatedCgmRepository(cgmRepository) { id ->
            registry.lifetimeMinOf(id).first()
        }
    }

    /** Null while Bluetooth is off or absent. */
    private fun bluetoothAdapter(): android.bluetooth.BluetoothAdapter? =
        appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter

    /** Out of Auto Backup: the lines carry readings and raw sensor traffic. */
    val cgmLogs: com.t1dm.cgm.CgmSensorLogs by lazy {
        com.t1dm.cgm.CgmSensorLogs(
            dir = File(appContext.noBackupFilesDir, "cgm-log"),
            scope = appScope,
            io = dispatchers.io,
        )
    }

    /** One coordinator for every CGM family — a second registry gives a second answer. */
    val registry: ConnectedCgmRegistry by lazy {
        ConnectedCgmRegistry(
            repository = gatedCgmRepository,
            scope = appScope,
            logs = cgmLogs,
            drivers = listOf(
                AidexXFamilyDriver(
                    bonded = com.t1dm.cgm.BondedAidexDevices(bluetoothAdapter()),
                    repository = gatedCgmRepository,
                    session = UniffiAidexSession(),
                    nowMs = System::currentTimeMillis,
                    logs = cgmLogs,
                    transportFactory = { device, log ->
                        com.t1dm.cgm.AndroidAidexGattTransport(appContext, device, log)
                    },
                    discover = {
                        com.t1dm.cgm.AidexDeviceScanner(
                            scanner = bluetoothAdapter()?.bluetoothLeScanner,
                            dispatchers = dispatchers,
                        ).discover()
                    },
                    // AiDEX advertises a public address (CGM.md §4).
                    deviceAt = { address ->
                        runCatching {
                            bluetoothAdapter()?.remoteLeDeviceCompat(
                                address,
                                android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC,
                            )
                        }.getOrNull()
                    },
                ),
                Ct5FamilyDriver(
                    repository = gatedCgmRepository,
                    session = UniffiCt5Session(),
                    nowMs = System::currentTimeMillis,
                    logs = cgmLogs,
                    discover = {
                        com.t1dm.cgm.Ct5DeviceScanner(
                            scanner = bluetoothAdapter()?.bluetoothLeScanner,
                            dispatchers = dispatchers,
                            session = UniffiCt5Session(),
                            screenOn = ::screenInteractive,
                        ).discover()
                    },
                    transportFactory = { device, scope, log ->
                        com.t1dm.cgm.AndroidCt5GattTransport(appContext, device, scope, log)
                    },
                    importSource = ct5ImportSource(),
                    screenOn = ::screenInteractive,
                    // CT5 advertises a static random address; a public-typed handle never connects.
                    deviceAt = { address ->
                        runCatching {
                            bluetoothAdapter()?.remoteLeDeviceCompat(
                                address,
                                android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM,
                            )
                        }.getOrNull()
                    },
                ),
                Libre3FamilyDriver(
                    repository = gatedCgmRepository,
                    nowMs = System::currentTimeMillis,
                    logs = cgmLogs,
                    discover = {
                        com.t1dm.cgm.Libre3DeviceScanner(
                            scanner = bluetoothAdapter()?.bluetoothLeScanner,
                            dispatchers = dispatchers,
                            screenOn = ::screenInteractive,
                        ).discover()
                    },
                    pairing = Libre3FamilyDriver.PairingStack(
                        native = com.t1dm.cgm.UniffiLibre3Native(),
                        tablesDir = {
                            // §9: the pushed tables dir; absent dir refuses the session (fail closed).
                            appContext.getExternalFilesDir(null)?.let { files ->
                                java.io.File(files, "libre3/tables").takeIf { it.isDirectory }?.path
                            }
                        },
                        transportAt = { address, _, log ->
                            // Type-agnostic: the platform resolves the address type from the
                            // fresh scan cache; Libre addresses are the NFC-minted display form.
                            val device = bluetoothAdapter()?.getRemoteDevice(address)
                                ?: return@PairingStack null
                            com.t1dm.cgm.AndroidLibre3GattTransport(
                                context = appContext,
                                device = device,
                                // §15 debug flag: handshake PDU hex to logcat for the live runs.
                                pduDebug = true,
                                log = log,
                            )
                        },
                    ),
                ),
            ),
        )
    }

    /** Interactive, not unlocked: a lit lock screen does not suspend the scan. */
    private fun screenInteractive(): Boolean =
        appContext.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    val settingsStore: SettingsStore by lazy { SettingsStore(repository) }

    private val alarmLive = LiveConfig(AlarmConfig.DEFAULT)

    /** §3.6-A. Coded defaults until [refreshAlarmConfig] hydrates the persisted thresholds. */
    val alarmConfig: AlarmConfig get() = alarmLive.value

    /** The same value for Compose: a plain read never invalidates a composition. */
    val alarmConfigFlow: StateFlow<AlarmConfig> = alarmLive.flow

    /** False while [alarmConfig] holds coded defaults; the widget's Glance bake must check this. */
    val alarmConfigHydrated: Boolean get() = alarmLive.hydrated

    /** Live-config seam into [AlarmEngine]; null while the FGS is down. Never re-arms a latch. */
    fun setAlarmConfigSink(sink: ((AlarmConfig) -> Unit)?) = alarmLive.setSink(sink)

    suspend fun refreshAlarmConfig() = updateAlarmConfig {}

    private suspend fun updateAlarmConfig(write: suspend () -> Unit) {
        alarmLive.update(write) { settingsStore.currentAlarmConfig() }
            .onFailure { Timber.w(it, "alarm config read failed; last config kept") }
    }

    // The notification presenters run outside Compose and cannot read `LocalT1dmSemantics`.
    @Volatile
    var themeIdSnapshot: String = com.t1dm.core.design.ThemeIds.TRON
        private set

    @Volatile
    var customThemeJsonSnapshot: String? = null
        private set

    val notificationAccentArgb: Int
        get() = com.t1dm.app.notify.NotificationIcons.accentArgb(themeIdSnapshot, customThemeJsonSnapshot)

    // DEATH: total-silence override, read synchronously by the FGS alarm; never exported.
    @Volatile
    var deathModeSnapshot: Boolean = false
        private set

    val deathMode: Flow<Boolean> get() = settingsStore.deathMode
    suspend fun setDeathMode(on: Boolean) = settingsStore.setDeathMode(on)

    // Presentation-only silence (§3.6 C1-C5); process-scoped, distinct from DEATH's fail-open.
    @Volatile
    var snoozeSnapshot: SnoozeState = SnoozeState.NONE
        private set

    /** Timed until [untilMs], or [dismiss] until the breach clears. */
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

    /** Whole minutes, kept current by a collector. */
    @Volatile
    var snoozeMinSnapshot: Int = SettingsStore.DEFAULT_SNOOZE_MIN
        private set

    val snoozeMin: Flow<Int> get() = settingsStore.snoozeMin
    suspend fun currentSnoozeMin(): Int = settingsStore.currentSnoozeMin()
    suspend fun setSnoozeMin(min: Int) = settingsStore.setSnoozeMin(min)

    /** GMI (estimated HbA1c, %) over 30 days. Null until computed or with too little data. */
    @Volatile
    var gmiSnapshot: Double? = null
        private set

    /** Local midnight → now. Runs on every widget push. */
    suspend fun stepsToday(): Int {
        val zone = java.time.ZoneId.systemDefault()
        val midnight = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        return repository.stepsInRange(midnight, System.currentTimeMillis())
    }

    // FGS driver reads ADAPTIVE-vs-TIMED off this, skipping a suspend into SettingsStore per tick.
    @Volatile
    var forecastModeSnapshot: String = SettingsStore.FORECAST_MODE_ADAPTIVE
        private set

    /** TIMED mode only; whole minutes. */
    suspend fun forecastPeriodMin(): Int = settingsStore.currentForecastPeriodMin()

    /** Per-severity sound + vibration; additive, never changes WHEN an alarm fires (§3.6-A). */
    suspend fun alertActuatorConfig(): AlertActuatorConfig = actuatorConfigOf(
        warningSoundOn = settingsStore.currentWarningSoundOn(),
        criticalSoundOn = settingsStore.currentCriticalSoundOn(),
        warningVibration = settingsStore.currentWarningVibration(),
        criticalVibration = settingsStore.currentCriticalVibration(),
        bypassDnd = settingsStore.currentBypassDnd(),
    )

    private fun actuatorConfigOf(
        warningSoundOn: Boolean,
        criticalSoundOn: Boolean,
        warningVibration: VibrationPreset,
        criticalVibration: VibrationPreset,
        bypassDnd: Boolean,
    ): AlertActuatorConfig {
        val alarmTone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        return AlertActuatorConfig(
            warningSound = if (warningSoundOn) alarmTone else null,
            criticalSound = if (criticalSoundOn) alarmTone else null,
            warningVibration = warningVibration,
            criticalVibration = criticalVibration,
            bypassDnd = bypassDnd,
        )
    }

    /** Stock settings until the first read: an unread store must not silence a critical alarm. */
    private val actuatorLive by lazy {
        LiveConfig(
            actuatorConfigOf(
                warningSoundOn = SettingsStore.DEFAULT_WARNING_SOUND_ON,
                criticalSoundOn = SettingsStore.DEFAULT_CRITICAL_SOUND_ON,
                warningVibration = SettingsStore.DEFAULT_WARNING_VIBRATION,
                criticalVibration = SettingsStore.DEFAULT_CRITICAL_VIBRATION,
                bypassDnd = SettingsStore.DEFAULT_BYPASS_DND,
            ),
        )
    }

    /** Synchronous snapshot for presenters, which run outside Compose and cannot suspend. */
    val alertActuatorSnapshot: AlertActuatorConfig get() = actuatorLive.value

    suspend fun refreshAlertActuatorConfig() = updateActuatorConfig {}

    private suspend fun updateActuatorConfig(write: suspend () -> Unit) {
        actuatorLive.update(write) { alertActuatorConfig() }
            .onFailure { Timber.w(it, "alert actuator config read failed; last config kept") }
    }

    suspend fun saveWarningVibration(preset: VibrationPreset) {
        updateActuatorConfig { settingsStore.setWarningVibration(preset) }
    }

    suspend fun saveCriticalVibration(preset: VibrationPreset) {
        updateActuatorConfig { settingsStore.setCriticalVibration(preset) }
    }

    suspend fun saveWarningSoundOn(on: Boolean) {
        updateActuatorConfig { settingsStore.setWarningSoundOn(on) }
    }

    suspend fun saveCriticalSoundOn(on: Boolean) {
        updateActuatorConfig { settingsStore.setCriticalSoundOn(on) }
    }

    suspend fun saveBypassDnd(on: Boolean) {
        updateActuatorConfig { settingsStore.setBypassDnd(on) }
    }

    private val vibrationActuator by lazy { com.t1dm.alerts.VibrationActuator(appContext) }

    /** Unknown names are ignored. Preview only — never touches the alarm path (§3.6-A). */
    fun previewVibration(name: String) {
        val preset = runCatching { com.t1dm.alerts.VibrationPreset.valueOf(name) }.getOrNull() ?: return
        vibrationActuator.buzz(preset)
    }

    suspend fun saveAlarmThresholds(urgentLow: Int, low: Int, high: Int, urgentHigh: Int) {
        updateAlarmConfig { settingsStore.setAlarmThresholds(urgentLow, low, high, urgentHigh) }
    }

    suspend fun saveLossWindows(lossMin: Int, lossEscalatedMin: Int) {
        updateAlarmConfig { settingsStore.setLossWindows(lossMin, lossEscalatedMin) }
    }

    /** A signal-QUALITY alert, distinct from loss-of-signal (§3.6-A). */
    suspend fun saveWeakSignal(enabled: Boolean, dbm: Int, sustainMin: Int) {
        updateAlarmConfig { settingsStore.setWeakSignal(enabled, dbm, sustainMin) }
    }

    suspend fun saveRepeatCadence(min: Int) {
        updateAlarmConfig { settingsStore.setRepeatCadence(min) }
    }

    suspend fun saveMinActuationMin(min: Int) {
        updateAlarmConfig { settingsStore.setMinActuationMin(min) }
    }

    /** The over-temperature alarm is EXEMPT from DEATH's global suppression (D4). */
    suspend fun saveOverTempConfig(enabled: Boolean, alertC: Double, clearC: Double, critical: Boolean) {
        updateAlarmConfig { settingsStore.setOverTempConfig(enabled, alertC, clearC, critical) }
    }

    /** Accepts the wrapped shape and the legacy flat settings-only file. Off-main. */
    suspend fun importConfigJson(text: String): ImportResult = withContext(dispatchers.io) {
        val parsed = ConfigBackup.parse(text)
        // Null only for a drawings-only backup; drawings apply regardless of the settings half.
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
            // De-duplicated on authoring instant; two distinct strokes cannot share a millisecond.
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

    class RestoreResult(
        val archive: ArchiveResult,
        val settingsKeys: Int,
        val settingsError: String?,
    )

    /** [open] is a factory: the legacy fallback re-reads the same file from the beginning. */
    suspend fun restoreArchive(open: suspend () -> java.io.InputStream): RestoreResult =
        withContext(dispatchers.io) {
            val result = try {
                open().use { repository.readArchive(it) }
            } catch (e: NotAnArchiveException) {
                // The legacy document's own format tag still refuses a foreign JSON.
                val bytes = open().use { it.readAtMost(MAX_LEGACY_BACKUP_BYTES + 1) }
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

            // Separately, so a configuration that will not import cannot cost the history that did.
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

    /** Legacy path is read wholly into memory; the archive path is streamed and needs no bound. */
    private val MAX_LEGACY_BACKUP_BYTES = 32 * 1024 * 1024

    /** Dev-time models dir on the app's external files, adb-pushable; the .pte is NOT bundled. */
    val modelsDir: File = File(appContext.getExternalFilesDir(null), "models").apply { mkdirs() }

    private val roomPredictionStore: RoomPredictionStore by lazy { RoomPredictionStore(repository) }

    val inferenceController: InferenceController by lazy {
        buildInferenceController(
            native = nativeCore,
            dispatchers = dispatchers,
            modelsDir = modelsDir,
            history = RoomBgHistoryProvider(repository, registry),
            predictionStore = roomPredictionStore,
            // Carb-appearance + insulin-action channels (SPEC §3.3).
            contextChannels = ContextChannelSource { gridStartMs, nSteps ->
                dashboardCurveChannels(gridStartMs, nSteps)
            },
            // Prediction zone on committed dose tails, via the calculator's curve engine (§3.3).
            futureOverrides = FutureOverrideSource { rollStartMs, nFutureSteps ->
                dashboardFutureChannels(rollStartMs, nFutureSteps)
            },
            // Fresh each cycle.
            warmupHoursProvider = { warmupHours() },
            // INFERENCE.md §7.1: same window as the calculator's roll and dashboard overlay.
            smoothingWindowProvider = { smoothingWindow() },
            // Real rapid-insulin unit, so the guard's mg/dL-per-unit is a receivable quantity.
            probeInsulin = ProbeInsulinPort { units, steps ->
                val curve = curveEngine.presetCurve(units, resolveRapidPreset(null))
                DoubleArray(steps) { i -> curve.getOrElse(i) { 0.0 } }
            },
            // Read fresh each discovery, so a Settings edit takes on the next refresh.
            maxRunningProvider = { maxRunningModels() },
            // Re-read fresh for every discovered id.
            telemetryStore = KvTelemetryStore(repository),
            selectionStore = KvSelectionStore(repository),
            artifactLedger = KvArtifactLedger(repository),
            // New files under an old id: what the old ones produced or were fitted on goes.
            onArtifactReplaced = { modelId ->
                repository.deleteBandCalibration(modelId)
                repository.deletePredictionsForModel(modelId)
                repository.detachLoras(modelId, System.currentTimeMillis())
            },
            // Deserialization failure ⇒ null ⇒ frozen model, never half-applied.
            eventOnsets = EventOnsetSource { fromMs, toMs -> channelBuilder.eventOnsets(fromMs, toMs) },
            // The forecast adapter only; a fill reads its own kind in LabController.runSpan.
            loraStore = LoraStore { modelId ->
                repository.attachedLora(modelId, MaskGeometry.FORECAST)?.let { row ->
                    nativeCore.loraDeserialize(row.blob)
                        ?: null.also { Timber.w("adapter %d for %s failed to load; running frozen", row.id, modelId) }
                }
            },
            // No death-mode check: the over-temp gate stays active in DEATH (D4).
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

    /** Serialised with the FGS cycles by the controller's own mutex; never bypasses a §3.6 gate. */
    fun reevaluateInferenceNow() {
        appScope.launch {
            runCatching { inferenceController.runFromHistory(InferenceCause.GRID_TICK, System.currentTimeMillis()) }
        }
    }

    private val curveReforecastScheduled = AtomicBoolean(false)

    /** Wall ms of the last dose, meal or exercise write; expires a held bolus recommendation. */
    val lastCurveWriteMs = MutableStateFlow<Long?>(null)

    /** Debounced, coalescing; guard releases just before forward. Caller's write never waits. */
    fun reforecastAfterCurveWrite() {
        lastCurveWriteMs.value = System.currentTimeMillis()
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

    // Device temperature: BatteryManager's EXTRA_TEMPERATURE, tenths of °C; no fan RPM to read.
    val temperatureUnit: Flow<TempUnit> = settingsStore.temperatureUnit.map { TempUnit.fromKey(it) }
    suspend fun setTemperatureUnit(u: TempUnit) = settingsStore.setTemperatureUnit(u.key)

    /** Battery-sensor °C, or null if unreadable. Sticky-intent read; call off-main. */
    fun readDeviceTempC(): Double? = runCatching {
        val intent = appContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { it / 10.0 }
    }.getOrNull()

    // Thermal gate knobs, Celsius. The gate itself is wired in via `thermalProvider` above.
    val thermalGateEnabled: Flow<Boolean> = settingsStore.thermalGateEnabled
    val inferenceMaxTempC: Flow<Double> = settingsStore.inferenceMaxTempC
    val thermalWarnMarginC: Flow<Double> = settingsStore.thermalWarnMarginC
    suspend fun setThermalGateEnabled(on: Boolean) = settingsStore.setThermalGateEnabled(on)
    suspend fun setInferenceMaxTempC(c: Double) = settingsStore.setInferenceMaxTempC(c)
    suspend fun setThermalWarnMarginC(c: Double) = settingsStore.setThermalWarnMarginC(c)

    /** One pair serves every model. Off-main: 51 200 classifications lifted across uniffi. */
    private val errorGridLatticesOnce: ErrorGridLattices by lazy {
        ErrorGridLattices(
            clarke = ZoneLattice.build(nativeCore::clarkeZoneGrid),
            dts = ZoneLattice.build(nativeCore::dtsZoneGrid),
        )
    }

    suspend fun errorGridLattices(): ErrorGridLattices =
        withContext(dispatchers.default) { errorGridLatticesOnce }

    /** Rate-bin edges, mg/dL per minute, from the crate. Empty on a stub core. */
    val trendBinEdges: List<Double> by lazy { nativeCore.trendBinEdges() }

    /** §6.1 alarm levels' fan positions, from the crate. Null on a stub core. */
    val alarmFanEdges: AlarmFanEdges? by lazy { nativeCore.alarmFanEdges() }

    /** Per horizon, band projection of `invariants.md` §6.2. CG-EGA: ask [modelCgEga]. */
    suspend fun modelMetrics(
        modelId: String,
        days: Int = ACCURACY_WINDOW_DAYS,
        minSamples: Int = ACCURACY_MIN_SAMPLES,
    ): ModelMetrics = modelMetrics(modelId, days, minSamples, includeCgEga = false)

    /** Whole-window CG-EGA (§6.3). Null when nothing scoreable was found. */
    suspend fun modelCgEga(modelId: String, days: Int = ACCURACY_WINDOW_DAYS, minSamples: Int = ACCURACY_MIN_SAMPLES): CgEga? =
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
        return metricsOf(set, minSamples, includeCgEga)
    }

    private suspend fun metricsOf(set: ForecastWindowSet, minSamples: Int, includeCgEga: Boolean): ModelMetrics {
        // §6.1: compared against the patient's own alarm bands, never the validation table.
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

    private val backtestRunning = AtomicBoolean(false)
    private var backtestJob: Job? = null
    private val _backtests = MutableStateFlow<Map<String, ModelBacktest>>(emptyMap())

    /** By model id; this process only. */
    val backtests: StateFlow<Map<String, ModelBacktest>> = _backtests.asStateFlow()

    /** One at a time, process-wide; in [appScope], so leaving the panel does not cancel it. */
    fun startBacktest(modelId: String, days: Int, sourceIds: List<String>) {
        if (!backtestRunning.compareAndSet(false, true)) {
            if (_backtests.value[modelId] !is ModelBacktest.Running) {
                _backtests.update { it + (modelId to ModelBacktest.Refused(days, BacktestRefusal.BUSY)) }
            }
            return
        }
        _backtests.update { it + (modelId to ModelBacktest.Running(days, 0, 0)) }
        backtestJob = appScope.launch(dispatchers.default) {
            try {
                val outcome = runBacktest(modelId, days, sourceIds.map(::CgmSourceId)) { done, total ->
                    if (done % BACKTEST_PROGRESS_EVERY == 0 || done == total) {
                        _backtests.update { it + (modelId to ModelBacktest.Running(days, done, total)) }
                    }
                }
                _backtests.update { it + (modelId to outcome) }
            } catch (e: CancellationException) {
                _backtests.update { it - modelId }
                throw e
            } catch (e: Exception) {
                Timber.w(e, "backtest of %s failed", modelId)
                _backtests.update { it + (modelId to ModelBacktest.Refused(days, BacktestRefusal.FAILED)) }
            } finally {
                backtestRunning.set(false)
            }
        }
    }

    fun cancelBacktest(modelId: String) {
        if (_backtests.value[modelId] is ModelBacktest.Running) backtestJob?.cancel()
    }

    /** A result describes the model as it ran; an offset, adapter or artifact change voids it. */
    private fun dropBacktest(modelId: String) {
        cancelBacktest(modelId)
        _backtests.update { it - modelId }
    }

    /** One sensor's whole record, newest first; the live series takes its rows by count. */
    private class BacktestStream(val source: CgmSourceId, val newestFirst: List<CgmReading>, val withFills: Boolean) {
        val indexOf = HashMap<Long, Int>(newestFirst.size * 2).apply {
            newestFirst.forEachIndexed { i, r -> put(r.tsMs, i) }
        }
    }

    private class BacktestOrigin(val stream: BacktestStream, val tsMs: Long)

    private suspend fun runBacktest(
        modelId: String,
        days: Int,
        sourceIds: List<CgmSourceId>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ModelBacktest {
        val t0 = System.nanoTime()
        val now = System.currentTimeMillis()
        val desc = inferenceController.descriptorOf(modelId)
            ?: return ModelBacktest.Refused(days, BacktestRefusal.NOT_LOADED)
        if (sourceIds.isEmpty()) return ModelBacktest.Refused(days, BacktestRefusal.NO_SENSOR)
        // Fills live in the authoritative sensor's stream only, as the live cycle reads them.
        val authoritative = repository.authoritativeSourceId()
        val horizonMaxMin = ACCURACY_HORIZONS_MIN.max()
        val since = now - days.toLong() * 86_400_000L
        val lastOrigin = now - horizonMaxMin * 60_000L
        val streams = sourceIds.distinct().map { id ->
            BacktestStream(id, repository.readingsInRange(id, 0L, now).asReversed(), withFills = id == authoritative)
        }
        val origins = streams.flatMap { s ->
            s.newestFirst.asReversed()
                .filter { it.bgMgdl != null && isRealMeasurement(it.provenance, it.flag) && it.tsMs in since..lastOrigin }
                .map { BacktestOrigin(s, it.tsMs) }
        }
        if (origins.isEmpty()) return ModelBacktest.Refused(days, BacktestRefusal.NO_HISTORY)

        val maxSteps = desc.maxContextPatches * desc.patchSize
        val minSteps = desc.minContextPatches * desc.patchSize
        val contextFrom = origins.minOf { it.tsMs } - maxSteps * CurveEngine.STEP_MS
        val horizonEnd = origins.maxOf { it.tsMs } + desc.predictionHorizonHours * 3_600_000L + CurveEngine.STEP_MS
        val doses = doseStore.snapshot(contextFrom - ChannelBuilder.PAD_MS, horizonEnd)
        val infills = repository.infillInRange(0L, now)
        val infillCreatedAt = infills.associate { it.ts to it.createdAtMs }

        // What the phone held when the anchor arrived; a promoted reconstruction's rx is its slot.
        fun known(r: CgmReading, asOfMs: Long): Boolean =
            if (r.provenance == ReadingProvenance.RECONSTRUCTED) {
                (infillCreatedAt[r.tsMs] ?: Long.MAX_VALUE) <= asOfMs
            } else {
                r.rxWallMs <= asOfMs
            }

        val inputAt: suspend (BacktestOrigin, ModelDescriptor) -> InferenceController.BacktestInput? = { o, _ ->
            val newestFirst = o.stream.newestFirst
            val at = o.stream.indexOf.getValue(o.tsMs)
            val asOf = maxOf(o.tsMs, newestFirst[at].rxWallMs)
            val limit = maxSteps + BG_SERIES_ROW_MARGIN
            val rows = ArrayList<CgmReading>(limit)
            var j = at
            while (j < newestFirst.size && rows.size < limit) {
                if (known(newestFirst[j], asOf)) rows += newestFirst[j]
                j++
            }
            assembleBgSeries(rows, o.stream.source.value, maxSteps, minSteps, withReconstructed = true) { from, to ->
                if (!o.stream.withFills) {
                    emptyMap()
                } else {
                    infills.asSequence()
                        .filter { it.ts in from..to && it.createdAtMs <= asOf }
                        .associate { it.ts to it.mgdl }
                }
            }?.let { series ->
                val builder = ChannelBuilder(curveEngine, doses.at(asOf))
                InferenceController.BacktestInput(
                    cycleTsMs = o.tsMs,
                    series = series,
                    context = ContextChannelSource { g, n -> dashboardCurveChannels(g, n, builder) },
                    future = FutureOverrideSource { r, n -> dashboardFutureChannels(r, n, builder) },
                )
            }
        }
        val run = inferenceController.backtest(modelId, origins, inputAt, onProgress)
        run.refusal?.let { return ModelBacktest.Refused(days, it) }

        // Newest first, as `forecastWindows` hands the suite its rows.
        val newestFirst = run.forecasts.sortedByDescending { it.cycleTsMs }
        val set = repository.forecastWindowsOf(newestFirst, streams.mapTo(HashSet()) { it.source }, horizonMaxMin, since, now)
        val bySource = run.forecasts.groupingBy { it.sourceId }.eachCount()
        return ModelBacktest.Done(
            days = days,
            metrics = metricsOf(set, ACCURACY_MIN_SAMPLES, includeCgEga = true),
            nForecasts = run.forecasts.size,
            nOrigins = origins.size,
            forecastsBySource = streams.associate { it.source.value to (bySource[it.source.value] ?: 0) },
            adapterAttached = run.adapterAttached,
            stopped = run.stopped,
            elapsedMs = (System.nanoTime() - t0) / 1_000_000L,
            finishedAtMs = System.currentTimeMillis(),
        )
    }

    // §8.4: correction reaches only drawn fans: BG panel overlay, hindsight sweep, watch forecast.

    /** One fit at a time, process-wide; a second entry is refused, never queued. */
    private val bandCalibrationRunning = AtomicBoolean(false)

    /** Observed from Room, so a fit lands without reopening the panel; survives process death. */
    val bandCalibrations: StateFlow<Map<String, BandCalibration>> =
        repository.observeBandCalibrations()
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** §8.4 apply. Null means draw the raw fan: expired, wrong shape, or the core refused. */
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

    /** [calibratedBands] over one model's fans. [fansMgdl] is fan-major. */
    fun calibratedFanBatch(
        calibrations: Map<String, BandCalibration>,
        modelId: String,
        fansMgdl: () -> List<Double>,
        horizonSteps: Int,
        nQuantiles: Int,
    ): List<Double>? {
        // [fansMgdl] flattens a day of fans that a null delta would discard.
        val delta = eligibleDelta(calibrations, modelId, horizonSteps, nQuantiles) ?: return null
        return nativeCore.applyQuantileConformalBatch(fansMgdl(), delta)
    }

    /** Eligibility half of the §8.4 apply, factored so a fan is never raw on one surface only. */
    private fun eligibleDelta(
        calibrations: Map<String, BandCalibration>,
        modelId: String,
        horizonSteps: Int,
        nQuantiles: Int,
    ): List<Double>? {
        val cal = calibrations[modelId] ?: return null
        // A delta fitted at a different horizon or fan width is not this forecast's correction.
        if (cal.steps != horizonSteps || cal.nQuantiles != nQuantiles) return null
        // Stale: row is kept so the drill-down can say what lapsed, but stops being drawn.
        if (cal.expiredAt(System.currentTimeMillis())) return null
        // A delta states one sensor's error; null on either side refuses an unvouched fix.
        val authoritative = registry.authoritative.value?.value
        if (authoritative == null || cal.sourceId != authoritative) return null
        return cal.delta
    }

    /** [modelId]'s own forecast horizon, minutes; null when it cannot be established. */
    private fun modelHorizonMin(modelId: String): Int? {
        val state = inferenceState.value
        val fromDescriptor = state.metas.firstOrNull { it.modelId == modelId }?.predictionHorizonHours
        if (fromDescriptor != null && fromDescriptor > 0) return fromDescriptor * 60
        val p = state.predictions.firstOrNull { it.modelId == modelId } ?: return null
        if (p.horizonSteps <= 0 || p.stepMs <= 0L) return null
        return (p.horizonSteps.toLong() * p.stepMs / 60_000L).toInt()
    }

    /** By hand: a source change can't be detected once older forecasts have aged out. */
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
            // `forecastWindows` is newest-first; the conformal split is chronological here.
            val chronological = set.windows.asReversed()
            val fit = withContext(dispatchers.default) {
                nativeCore.fitQuantileConformal(chronological, minCalWindows)
            }
            // `steps == 0` is "nothing was scoreable" — no result, not a refusal with n = 0.
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
                    // From the repository, not the registry: must match `forecastWindows`'s filter.
                    sourceId = repository.authoritativeSourceId()?.value,
                ),
            )
            return BandCalibrationOutcome(fit, true, set.nMatured, set.nIncomplete)
        } finally {
            bandCalibrationRunning.set(false)
        }
    }

    /** Hours, floored at the model's MIN_CONTEXT so the gate cannot fall below what it needs. */
    suspend fun warmupHours(): Double =
        (repository.getKv(KV_WARMUP_HOURS)?.toDoubleOrNull() ?: InferenceControllerDefaults.WARMUP_HOURS)
            .coerceAtLeast(InferenceControllerDefaults.MIN_WARMUP_HOURS.toDouble())

    val warmupHoursSetting: Flow<Int> = repository.observeKv(KV_WARMUP_HOURS).map { raw ->
        (raw?.toDoubleOrNull() ?: InferenceControllerDefaults.WARMUP_HOURS)
            .coerceAtLeast(InferenceControllerDefaults.MIN_WARMUP_HOURS.toDouble())
            .toInt()
    }

    suspend fun setWarmupHours(hours: Int) {
        val clamped = hours.coerceIn(InferenceControllerDefaults.MIN_WARMUP_HOURS, WARMUP_HOURS_MAX)
        repository.putKv(KV_WARMUP_HOURS, clamped.toString(), System.currentTimeMillis())
    }

    // Running-set cap: every running model forecasts; the selected one draws the BG panel.

    val maxModelsSetting: Flow<Int> get() = settingsStore.inferenceMaxModels

    suspend fun setMaxModels(n: Int) = settingsStore.setInferenceMaxModels(n)

    suspend fun maxRunningModels(): Int = settingsStore.currentInferenceMaxModels()

    // INFERENCE.md §7.1: one window shared by forecast cycle, calculator rolls, dashboard overlay.

    val savgolWindow: Flow<Int> get() = settingsStore.savgolWindow

    suspend fun smoothingWindow(): Int = settingsStore.currentSavgolWindow()

    /** Snapped to an offered detent. */
    suspend fun setSmoothingWindow(window: Int) = settingsStore.setSavgolWindow(window)

    fun startInference() {
        appScope.launch {
            refreshAlarmConfig()
            inferenceController.restoreLast()
            inferenceController.refreshModels()
        }
        // Bridge flag is read on the CGM hot path from a plain field; must be published at startup.
        appScope.launch { refreshNightscoutEnabled() }
        appScope.launch { settingsStore.themeId.collect { themeIdSnapshot = it } }
        appScope.launch { settingsStore.customThemeJson.collect { customThemeJsonSnapshot = it } }
        appScope.launch { settingsStore.deathMode.collect { deathModeSnapshot = it } }
        appScope.launch { settingsStore.snoozeMin.collect { snoozeMinSnapshot = it } }
        appScope.launch { settingsStore.forecastMode.collect { forecastModeSnapshot = it } }
        // GMI moves slowly; the widget reads this cache instead of a 30-day recompute per refresh.
        appScope.launch(dispatchers.default) {
            while (isActive) {
                gmiSnapshot = runCatching { statsRepository.localStats(StatsWindow.D30).gmi }
                    .getOrNull()?.takeIf { it in 3.0..25.0 }
                delay(30 * 60_000L)
            }
        }
    }

    /** The Nightscout api-secret at rest, Keystore-wrapped — never in the keep-forever Room DB. */
    val tokenStore: TokenStore by lazy { KeystoreTokenStore(appContext) }

    // One-way mirror of BG, carbs and bolus to a Nightscout host.

    /** URL + on/off in `kv`, the api-secret in the Keystore. */
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

    /** Publishes bridge on/off to the repository, consulted on the CGM hot path. */
    suspend fun refreshNightscoutEnabled() {
        // Startup runs this on appScope, which has no handler: a throw kills the CGM service too.
        repository.nightscoutBridgeEnabled = try {
            nightscoutConfigStore.current() != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Nightscout config unreadable; bridge off")
            false
        }
    }

    /** Returns what the host said to a probe. */
    suspend fun saveNightscoutBridge(url: String, secret: String, enabled: Boolean): String {
        nightscoutConfigStore.save(url, secret, enabled, System.currentTimeMillis())
        refreshNightscoutEnabled()
        return if (enabled) nightscoutClient.probe() else "off"
    }

    suspend fun probeNightscout(): String = nightscoutClient.probe()

    private val queueDrainer: QueueDrainer by lazy {
        QueueDrainer(
            dao = database.outboxDao(),
            sampleAt = repository::sampleAt,
            dispatchers = dispatchers,
            nightscout = nightscoutClient,
            trendAt = repository::authoritativeTrendAt,
        )
    }

    /** Always-on orchestrator; the FGS calls [SyncManager.launch] in its lifecycle scope. */
    val syncManager: SyncManager by lazy {
        SyncManager(drainer = queueDrainer, dispatchers = dispatchers)
    }

    val nightscoutError: StateFlow<String?> get() = syncManager.nightscoutError

    /** Erase everything, return to first-run in place. Process not killed, GATT survives. */
    suspend fun resetAllData() = withContext(dispatchers.io) {
        // Drop the watch session before the wipe: a late push would re-persist the erased pairing.
        runCatching { watchHub.stopForReset() }
        try {
            repository.wipeAllData(preserveCgmSources = true)
            // A cut entry holds the erased rows; its undo would write them back.
            bgEdits.clear()
            _bgEditDepth.value = 0
            runCatching { tokenStore.clearAll() }
            com.t1dm.app.watch.WatchKeyCipher.deleteKey()
            // Room-backed StateFlows self-heal from the wiped store; process-scoped caches do not.
            refreshAlarmConfig()
            runCatching { clearSnooze() }
            runCatching { clearBolusAdvice() }
            runCatching { clearRoll() }
            gmiSnapshot = null
            // Plain field the CGM hot path reads; process lives on, so must be re-published.
            runCatching { refreshNightscoutEnabled() }
            // Derived patient data on an app-lifetime object; it must not stay resident.
            runCatching { statsRepository.invalidateCache() }
            // The sensor logs carry readings too.
            runCatching { cgmLogs.clearAll() }
            // Monotonic, in-memory: else the forecast would run on the now-empty history.
            runCatching { inferenceController.resetWarmupLatch() }
        } finally {
            // Nothing else undoes `stopForReset`; a failed or cancelled wipe must still resume.
            withContext(NonCancellable) { runCatching { watchHub.resumeAfterReset() } }
        }
        reevaluateInferenceNow()
    }

    private val _resetting = MutableStateFlow(false)
    val resetting: StateFlow<Boolean> = _resetting.asStateFlow()

    /** App scope, not the screen's: leaving mid-wipe must not cancel it. */
    fun eraseAllAndRestart() {
        if (!_resetting.compareAndSet(false, true)) return
        appScope.launch(dispatchers.main) {
            try {
                resetAllData()
                restartApp()
            } finally {
                _resetting.value = false
            }
        }
    }

    /** Fresh task without killing the process, so the FGS and its GATT session survive. */
    fun restartApp() {
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { appContext.startActivity(it) }
    }

    /** Where an exported adapter lands: beside the models, so `adb pull` reaches it. */
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

    /** Last mask run or promotion message; a silent refusal reads as a no-op control. */
    val panelMaskNote: StateFlow<String?> = _panelMaskNote.asStateFlow()

    /** What the selected model's descriptor permits a mask to be; null hides the control. */
    suspend fun maskControls(): MaskControls? {
        val desc = inferenceController.selectedModelInfo()?.takeIf { it.real }?.descriptor
        val src = repository.authoritativeSourceId() ?: return null
        val span = desc?.let { it.minContextPatches * it.patchSize } ?: CUT_ONLY_CONTEXT_STEPS
        val rows = repository.recentReadings(src, span)
        val newestMeasured = rows.firstOrNull {
            isRealMeasurement(it.provenance, it.flag) && it.bgMgdl != null
        }?.tsMs ?: return null
        // No model, no patch geometry: fall back to the five-minute grid the store keys.
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

    /** [selection] is the edit bar's stretch; [geometry] is derived from it. One at a time. */
    fun runPanelMask(selection: MaskSelection, geometry: MaskGeometry) {
        // The SAME model [maskControls] took the geometry from.
        val modelId = inferenceController.selectedModelInfo()?.takeIf { it.real }?.id ?: run {
            _panelMaskNote.value = "No model selected"
            return
        }
        appScope.launch {
            _panelMaskNote.value = "Reconstructing…"
            val note = runCatching {
                labController.runSpan(modelId, selection.startMs, selection.endMs, geometry)
            }.getOrElse { it.message ?: "Reconstruction failed" }
            // Undoable only when a span landed: an undo for a refusal would take back another edit.
            if (geometry != MaskGeometry.FORECAST &&
                repository.reconstructedSpanSize(selection.startMs) > 0
            ) {
                pushBgEdit(BgEdit.Fill(selection.startMs))
            }
            _panelMaskNote.value = note
        }
    }

    // Held here, not in the composable: an edit outlives the screen. Session-scoped on purpose.

    private sealed interface BgEdit {
        data class Cut(val rows: List<BgCut>) : BgEdit

        data class Fill(val spanStartMs: Long) : BgEdit
    }

    private val bgEdits = ArrayDeque<BgEdit>()
    private val _bgEditDepth = MutableStateFlow(0)

    val bgEditDepth: StateFlow<Int> = _bgEditDepth.asStateFlow()

    private fun pushBgEdit(edit: BgEdit) {
        bgEdits.addLast(edit)
        while (bgEdits.size > BG_EDIT_UNDO_MAX) bgEdits.removeFirst()
        _bgEditDepth.value = bgEdits.size
    }

    /** Only route by which stored BG values leave the record. Both ends snapped to grid slots. */
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
                        // A promoted span reverses by demoting; one a later cut dropped is gone.
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

    /** Move a drawn span's line to the fan's τ-th quantile, and store the level it landed on. */
    fun retauSpan(spanStartMs: Long, tau: Double) {
        appScope.launch {
            _panelMaskNote.value = runCatching { labController.retau(spanStartMs, tau) }
                .getOrElse { it.message ?: "Could not move the line" }
            _tauPreview.value = null
        }
    }

    private val _tauPreview = MutableStateFlow<SpanLinePreview?>(null)

    /** The line the τ slider is currently over, before it is committed. Drawing only. */
    val tauPreview: StateFlow<SpanLinePreview?> = _tauPreview.asStateFlow()

    /** Cancelled by the next tick: a slider emits faster than a fan can be read and decoded. */
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
        val adapters = runCatching { labController.adaptersOf(modelId) }.getOrElse { emptyList() }
        _loraPanel.value = LoraPanelState(modelId = modelId, unavailable = unavailable, adapters = adapters)
    }

    /** Runs in [appScope], not the caller's: a screen's own scope would cancel it on navigation. */
    fun fitAdapter(modelId: String, spec: LoraFitSpec) {
        if (_loraPanel.value.busy) return
        // Progress lives here, not in the screen, so returning re-attaches to a running fit.
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

    /** Changes what the model is; the stored correction and forecasts are kept. */
    suspend fun attachAdapter(modelId: String, adapterId: Long) {
        dropBacktest(modelId)
        // A refusal is a RESULT, not an exception.
        runCatching { labController.attach(modelId, adapterId) }
            .onSuccess { refusal ->
                if (refusal != null) {
                    _loraPanel.update { s -> s.copy(error = refusal) }
                } else {
                    // Only when something changed; a refused attach would waste a full forward.
                    reevaluateInferenceNow()
                }
            }
            .onFailure { _loraPanel.update { s -> s.copy(error = it.message ?: "Attach failed") } }
        refreshLoraPanel(modelId)
    }

    /** Second action clearing a guard refusal; name compared by the controller, not the dialog. */
    suspend fun overrideAdapterGuard(modelId: String, adapterId: Long, typedName: String) {
        runCatching { labController.overrideGuard(adapterId, typedName) }
            .onSuccess { refusal ->
                if (refusal != null) _loraPanel.update { s -> s.copy(error = refusal) }
            }
            .onFailure { _loraPanel.update { s -> s.copy(error = it.message ?: "Override failed") } }
        refreshLoraPanel(modelId)
    }

    /** In [appScope] like a fit: a screen's own scope would cancel the replay on navigation. */
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
        dropBacktest(modelId)
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
        dropBacktest(modelId)
        runCatching { inferenceController.deleteModel(modelId) }
        withContext(dispatchers.io) {
            runCatching { repository.deletePredictionsForModel(modelId) }
            // Correction was fitted on this model's forecasts; means nothing without them.
            runCatching { repository.deleteBandCalibration(modelId) }
            // An adapter outlives nothing it was fitted on, and a fill is that model's own guess.
            runCatching { repository.deleteLorasForModel(modelId) }
            runCatching { repository.clearInfillForModel(modelId) }
        }
        reevaluateInferenceNow()
    }

    /** The shared curve/PK engine (SPEC §3.3). */
    val curveEngine: CurveEngine by lazy { CurveEngine(nativeCore, dispatchers) }

    private val doseStore: RoomDoseStore by lazy {
        RoomDoseStore(
            engine = curveEngine,
            loggedDoses = database.loggedDoseDao(),
            loggedMeals = database.loggedMealDao(),
            basalSchedules = database.basalScheduleDao(),
        )
    }

    /** Reconstructs carb-appearance / insulin-action channels from logged events (§3.3). */
    val channelBuilder: ChannelBuilder by lazy {
        ChannelBuilder(curveEngine, doseStore, ExerciseChannelSource(::exerciseChannel))
    }

    /** Mixes multi-food GI/custom shapes into one carb-appearance curve. */
    val mealCurveResolver: MealCurveResolver by lazy { MealCurveResolver(curveEngine) }

    /** Glycemic dictionary (FTS5) + saved meals; seeds the bundled dataset once. */
    val mealsController: MealsController by lazy { MealsController(repository, mealCurveResolver, dispatchers) }

    val insulinController: InsulinController by lazy { InsulinController(repository, curveEngine, dispatchers) }

    val savedMeals: Flow<List<SavedMeal>> get() = mealsController.savedMeals
    val customFoods: Flow<List<Food>> get() = mealsController.customFoods

    /** The last 3 distinct GI-bearing logged meals. */
    val recentMeals: Flow<List<RecentMeal>> get() = repository.observeRecentMeals(3)
    val insulinTypes: Flow<List<InsulinType>> get() = insulinController.types

    /** Everything a dose could be written against: Insulin catalogue, then builder rows. */
    val insulinChoices: Flow<List<InsulinChoice>>
        get() = insulinController.types.map { types ->
            insulinPresetCatalog().map(InsulinChoice::Preset) + types.map(InsulinChoice::Type)
        }

    /** Idempotent. Also settles an exercise bout the last process died in the middle of. */
    fun startBuilders() {
        appScope.launch {
            mealsController.seedIfEmpty()
            insulinController.syncBuiltins()
            exerciseController.reconcileOpenSessions(System.currentTimeMillis())
        }
    }

    // Bout's magnitude belongs in the sample's `exercise` scalar; row + GPS stay phone-local.

    val exerciseController: ExerciseController by lazy {
        ExerciseController(
            repository,
            dispatchers,
            curveEngine,
            carbEquivPerMin = { settingsStore.currentCarbEquivPerMin() },
        )
    }

    /** Published by [com.t1dm.app.service.ExerciseService]; null whenever no bout is running. */
    val activeExercise = MutableStateFlow<ActiveExercise?>(null)

    /** Why no bout could start; a running bout has its own reason in [ActiveExercise.degraded]. */
    val exerciseRefusal = MutableStateFlow<String?>(null)

    /** One degraded-exercise reason to render. Derived, not stored, so the two can't disagree. */
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

    /** The mood last folded into the wide sample. */
    val latestMood: Flow<Int?> = repository.observeLatestMood()

    /** The exact carb appearance (Ra) curve the model will see for a GI. */
    val previewCarbCurve: suspend (Double, Double) -> DoubleArray = { grams, gi ->
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        curveEngine.gamma(grams, k, theta, dur)
    }

    /** Disposal curve a bout lays into the exercise channel; empty if it disposes nothing. */
    val previewExerciseCurve: suspend (Double) -> DoubleArray = { durationMin ->
        val p = ExerciseDisposal.paramsFor(durationMin, settingsStore.currentCarbEquivPerMin())
        if (p.grams <= 0.0) DoubleArray(0) else curveEngine.gamma(p.grams, p.k, p.theta, p.durationMin)
    }

    /** Bit-for-bit the curve logBolus/logBasal persist. Preset by value: a chip tap redraws it. */
    val previewDoseCurve: suspend (Double, InsulinPresetSpec) -> DoubleArray = { units, spec ->
        curveEngine.presetCurve(units, spec)
    }

    /** Model consumes the combined insulin channel; has no use for the basal series. */
    suspend fun dashboardCurveChannels(
        gridStartMs: Long,
        nSteps: Int,
        builder: ChannelBuilder = channelBuilder,
    ): ModelChannels {
        val ch = builder.contextChannels(gridStartMs, nSteps)
        return ModelChannels(ch.carb, ch.insulin)
    }

    /** From `sample`, not bout records: rebuilding would re-rate every bout at today's rate. */
    suspend fun exerciseChannel(gridStartMs: Long, nSteps: Int): DoubleArray {
        val out = DoubleArray(nSteps)
        if (nSteps <= 0) return out
        val endMs = gridStartMs + (nSteps - 1).toLong() * CurveEngine.STEP_MS
        val rows = runCatching { repository.samplesInRange(gridStartMs, endMs) }.getOrElse {
            Timber.w(it, "exercise channel read failed; the panel draws no disposal")
            return out
        }
        return exerciseChannelOf(rows, gridStartMs, nSteps)
    }

    /** [rows] may overhang the window; only its slots are read. */
    private fun exerciseChannelOf(rows: List<SampleEntity>, gridStartMs: Long, nSteps: Int): DoubleArray {
        val out = DoubleArray(nSteps)
        for (r in rows) {
            val g = r.exercise ?: continue
            val i = Math.floorDiv(r.ts - gridStartMs, CurveEngine.STEP_MS).toInt()
            if (r.ts >= gridStartMs && i in 0 until nSteps && g.isFinite() && g > 0.0) out[i] = g
        }
        return out
    }

    /** Carbs, combined insulin, and the basal-only sub-channel over one grid window, one gather. */
    suspend fun dashboardOverlayChannels(gridStartMs: Long, nSteps: Int): OverlayInput {
        val ch = channelBuilder.overlayChannels(gridStartMs, nSteps)
        return OverlayInput(ch.carb, ch.insulin, ch.basal, ch.exercise)
    }

    /** `out[i]` = steps in `[gridStartMs + i*GRID_MS, +GRID_MS)`. Densified, never a Room row. */
    suspend fun dashboardStepSeries(gridStartMs: Long, nSteps: Int): IntArray {
        if (nSteps <= 0) return IntArray(0)
        val step = T1dmRepository.GRID_MS
        // NOT-MEASURED sentinel, not zero: a bucket with no row was never watched, not still.
        val out = IntArray(nSteps) { StepsFrame.NO_DATA }
        val endMs = gridStartMs + (nSteps - 1).toLong() * step
        for (row in repository.stepSeriesInRange(gridStartMs, endMs)) {
            val i = ((row.ts - gridStartMs) / step).toInt()
            if (i in 0 until nSteps) out[i] = row.steps
        }
        return out
    }

    /** Committed dose tails (§3.3). `announced`/`candidate` empty: passing again double-counts. */
    suspend fun dashboardFutureChannels(
        rollStartMs: Long,
        nFutureSteps: Int,
        builder: ChannelBuilder = channelBuilder,
    ): ModelChannels {
        val fc = builder.futureOverrides(rollStartMs, nFutureSteps, announced = emptyList(), candidate = null)
        return ModelChannels(fc.carb, fc.insulin)
    }

    /** §3.6-F provenance: logged doses only. */
    suspend fun iobCobNow(): IobCobReadout {
        val now = System.currentTimeMillis()
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

    /** Hours forward from IOB-zero. DISPLAY-ONLY — no §3.6 gate reads this. */
    val dkaTimeline: Flow<DkaTimeline> =
        combine(
            settingsStore.dkaAfterIobZeroH,
            settingsStore.comaAfterDkaH,
            settingsStore.deathAfterComaH,
        ) { a, b, c -> DkaTimeline(a, b, c) }

    private val selectedModelProvider = SelectedModelProvider {
        val info = inferenceController.authorityModelInfo()
        if (info == null || !info.real) null
        else object : SelectedModelHandle {
            override val descriptor = info.descriptor
            override val backendInfo = calcBackendInfo(info)
            override suspend fun run(input: GraphTensors): com.t1dm.inference.backend.GraphOutput =
                inferenceController.runSelectedAuthority(info, input)

            override suspend fun adapt(
                out: com.t1dm.inference.backend.GraphOutput,
                gi: com.t1dm.core.model.GraphInput,
            ): List<Double>? = inferenceController.adaptedHeadRawFor(info, out, gi)
        }
    }

    /** fp32 XNNPACK CPU authority (§3.6-E) or nothing; `:calc` never sees another backend. */
    private fun calcBackendInfo(info: com.t1dm.inference.InferenceController.SelectedModelInfo): BackendInfo =
        BackendInfo(backend = info.backend)

    /** Drives the selected fp32 model, gating each roll on the Rust degeneracy check. */
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

    /** Dose-scaled gamma PK announced-future events (§3.3); searches the last logged insulin. */
    private val bolusResolver = BolusResolver { doseU, atMs ->
        listOf(curveEngine.rapidEvent(doseU, atMs, resolveRapidPreset(null)))
    }

    private val bolusCalculator by lazy { BolusCalculator(rollingForecaster, bolusResolver) }

    /** Probe's meal at a pinned GI: a moving GI surfaces as the patient's own ratio changing. */
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

    /** §3.6-D anchor facts from the authoritative source's recent readings (null ⇒ no signal). */
    private val anchorSource = AnchorInfoSource { nowMs -> buildAnchorInfo(nowMs) }

    /** §3.6-F logged-doses-only IOB/COB snapshot (fail-closed: null ⇒ store failure). */
    private val iobSource = IobSource { nowMs -> buildIobSnapshot(nowMs) }

    /** §3.6-E backend/precision provenance; null (⇒ refusal) with no real selected model. */
    private val backendSource = BackendInfoSource {
        val info = inferenceController.authorityModelInfo()
        if (info == null || !info.real) null else calcBackendInfo(info)
    }

    /** Fail-closed: gate → grid search → degeneracy → rails → card. */
    val doseAdvisor: DoseAdvisor by lazy {
        DoseAdvisor(bolusCalculator, anchorSource, iobSource, backendSource, { smoothingWindow() })
    }

    /** Never actuates. */
    sealed interface BolusAdviceUi {
        data object Idle : BolusAdviceUi
        data object Running : BolusAdviceUi

        /** [computedAtMs] is the search start; [targetMgdl] null = the Settings objective. */
        data class Ready(val result: AdviceResult, val computedAtMs: Long, val targetMgdl: Double?) : BolusAdviceUi
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
        // Loaded fresh per run, so a Settings edit takes effect on the next recommendation.
        val base = config ?: runCatching { settingsStore.currentCalcConfig() }.getOrDefault(CalcConfig())
        // Manual target overrides the objective so the grid lands the median on it; else it stands.
        val cfg = if (manualTargetMgdl != null) base.copy(objective = Objective.HitTargetBg(manualTargetMgdl)) else base
        val now = System.currentTimeMillis()
        val announced: List<CurveEvent> = if (announcedCarbG > 0.0) {
            val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(announcedGi)
            listOf(curveEngine.carbEvent(announcedCarbG, now, k, theta, dur))
        } else emptyList()
        // DEATH also lifts the §3.6-B degeneracy refusal, so the advisor emits rather than refuses.
        val result = runCatching { doseAdvisor.recommendBolus(now, announced, cfg, bypassDegeneracyGate = deathModeSnapshot) }
            .getOrElse { AdviceResult.Refused(listOf("Calculator error — ${it.message ?: it::class.simpleName}")) }
        bolusAdvice.value = BolusAdviceUi.Ready(result, now, manualTargetMgdl)
    }

    fun clearBolusAdvice() { bolusAdvice.value = BolusAdviceUi.Idle }

    // Ephemeral, display-only: a distinct type, never fed to [doseAdvisor] or notifications.

    val rolledForecast = MutableStateFlow<RolledForecast?>(null)

    val rollComputing = MutableStateFlow(false)

    private var rollJob: Job? = null

    /** On fp32 CPU authority, never the GPU (~4.5x worse per forward). Failure yields a reason. */
    fun requestRollForDisplay(requestedHours: Double) {
        rollJob?.cancel()
        rollJob = appScope.launch {
            rollComputing.value = true
            try {
                val rf = runCatching {
                    rollingForecaster.rollForDisplay(System.currentTimeMillis(), requestedHours)
                }.getOrElse {
                    RolledForecast.missing(requestedHours, 0, "Roll failed — ${it.message ?: it::class.simpleName}")
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

    // Isolated like the rolled forecast: [SensitivityEstimate] is a type nothing else accepts.

    /** Null when no model response was obtained; panels render "N/A" rather than hiding it. */
    val sensitivity = MutableStateFlow<SensitivityEstimate?>(null)

    private var sensitivityJob: Job? = null

    /** When a probe was last STARTED, whatever it returned. */
    private var lastProbeAtMs: Long? = null

    /** Re-probe past TTL_MS; drop past LAPSE_MS. Not tied to the inference cycle. */
    fun refreshSensitivityIfStale() {
        val now = System.currentTimeMillis()
        var held = sensitivity.value

        // Read from the controller, not the UI: `predictions` has no entry until it forecasts.
        val selectedModelId = runCatching { inferenceController.authorityModelInfo()?.id }.getOrNull()
        if (held != null && held.modelId != selectedModelId) {
            sensitivity.value = null
            lastProbeAtMs = null   // the clock belongs to the old model too
            held = null
        }

        // Age is absolute, not elapsed: a backwards clock correction must expire it, not freeze it.
        val age = held?.let { Math.abs(now - it.atMs) }
        if (age != null && age >= SENSITIVITY_LAPSE_MS) sensitivity.value = null

        // Warm-up publishes no forecast; drop what's held rather than just skip the re-probe.
        if (inferenceState.value.warmup != null) {
            sensitivity.value = null
            return
        }
        if (age != null && age < SENSITIVITY_TTL_MS) return
        // Rate-limit attempts, not successes: a withheld probe retries on the shorter interval.
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

    /** Never actuates. A 0 U acceptance logs nothing, no handle (Undo on rowid 0 is unsafe). */
    suspend fun acceptAdvisedBolus(units: Double): LogHandle? =
        if (units.isFinite() && units > 0.0) logBolus(units) else null

    private suspend fun buildAnchorInfo(nowMs: Long): AnchorInfo? {
        val srcId = repository.authoritativeSourceId() ?: return null
        val recent = repository.recentReadings(srcId, 36) // ~3 h of 5-min grid context
        if (recent.isEmpty()) return null
        val lastMeasured = recent
            .filter { isRealMeasurement(it.provenance, it.flag) && it.bgMgdl != null }
            .maxByOrNull { it.tsMs }
        // `newest` is the newest row outright: filtered, warmup goes false on one measured row.
        val newest = recent.maxByOrNull { it.tsMs }!!
        // A promoted reconstruction counts as fabricated; fields are only the §3.6-F disclosure.
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
            bolusIobU = channelBuilder.bolusOnBoard(nowMs),
            cobG = channelBuilder.onBoard(nowMs, CurveKind.CARB),
            lastLoggedDoseTsMs = repository.latestLoggedInsulinTs(),
        )
    }.getOrNull()

    /** Window an undo can still recall the Nightscout mirror; delays only the outbox row. */
    private suspend fun pushHoldMs(): Long =
        settingsStore.currentPushHoldMin().toLong() * 60_000L

    /** Repository grid-snaps `ts`, mints `client_id`; the mirror reads the persisted row. */
    suspend fun logCarb(grams: Double, gi: Double, note: String? = null): LogHandle {
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
        val meal = repository.logMeal(
            LoggedMealEntity(
                clientId = "", tsMs = now, grams = grams, gi = gi, k = k, theta = theta,
                durationMin = dur, customCurve = null, tzOffsetMin = tz,
                note = note?.trim()?.takeIf { it.isNotEmpty() }, updatedAt = now,
            ),
        )
        mirrorToNightscout { nightscoutEnqueuer.enqueueMeal(meal, now, holdMs = pushHoldMs()) }
        reforecastAfterCurveWrite()
        return meal.handle("${fmtAmount(grams)} g (GI ${fmtAmount(gi)})")
    }

    /** Multi-food path: [MealsController] resolves the combined curve into `customCurve`. */
    suspend fun logBuilderMeal(components: List<MealComponent>): LogHandle {
        val now = System.currentTimeMillis()
        val meal = mealsController.logMeal(components)
        mirrorToNightscout { nightscoutEnqueuer.enqueueMeal(meal, now, holdMs = pushHoldMs()) }
        reforecastAfterCurveWrite()
        val foods = components.size
        return meal.handle("${fmtAmount(meal.grams)} g ($foods food${if (foods == 1) "" else "s"})")
    }

    /** Insulins the Insulin screen writes; [insulinChoices] unions this with `insulin_type`. */
    suspend fun insulinPresetCatalog(): List<InsulinPresetSpec> = curveEngine.presetCatalog()

    /** Throws on an empty catalogue: a dose with invented PK is worse than no row at all. */
    private suspend fun resolvePreset(family: InsulinFamily, requestedLabel: String?): InsulinPresetSpec =
        requireNotNull(
            resolveInsulinPreset(
                catalog = insulinPresetCatalog(),
                family = family,
                requested = requestedLabel,
                lastLogged = when (family) {
                    InsulinFamily.RapidGamma -> settingsStore.lastRapidPreset()
                    InsulinFamily.BasalBateman -> settingsStore.lastBasalPreset()
                },
            ),
        ) { "The insulin preset catalogue holds no $family entry." }

    private suspend fun resolveRapidPreset(label: String?) = resolvePreset(InsulinFamily.RapidGamma, label)

    private suspend fun resolveBasalPreset(label: String?) = resolvePreset(InsulinFamily.BasalBateman, label)

    /** Insulin a dose with no pick would carry: last dose of that kind, else catalogue head. */
    suspend fun resolvedRapidLabel(): String = resolveRapidPreset(null).label

    suspend fun resolvedBasalLabel(): String = resolveBasalPreset(null).label

    /** `units > 0.0` alone admits +Infinity, which settles as NaN, defeating the §3.6-C ceiling. */
    private fun requireLoggableDose(units: Double) {
        require(units.isFinite() && units > 0.0) { "Dose units must be positive and finite (was $units)." }
    }

    /** Row carries the dose's gamma k/theta/DIA (§3.1); null label falls to last logged. */
    suspend fun logBolus(units: Double, presetLabel: String? = null): LogHandle {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val rapid = resolveRapidPreset(presetLabel)
        val pk = curveEngine.bolusPk(units, rapid)
        val dose = repository.logLoggedDose(
            LoggedDoseEntity(
                clientId = "", tsMs = now, kind = DoseKind.BOLUS, units = units, durationMin = pk.durationMin,
                k = pk.k, theta = pk.theta, kaPerHour = null, kePerHour = null,
                tzOffsetMin = tz, note = rapid.label, updatedAt = now,
            ),
        )
        rememberLoggedPreset(rapid, presetLabel)
        mirrorToNightscout { nightscoutEnqueuer.enqueueDose(dose, now, holdMs = pushHoldMs()) }
        reforecastAfterCurveWrite()
        return dose.handle("${fmtAmount(units)} U bolus · ${rapid.label}")
    }

    /** Long-acting twin of [logBolus]: row carries the action window + ka/ke, Bateman reconstructs it. */
    suspend fun logBasal(units: Double, presetLabel: String? = null): LogHandle {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val tz = tzOffsetMin(now)
        val basal = resolveBasalPreset(presetLabel)
        val dose = repository.logLoggedDose(
            LoggedDoseEntity(
                clientId = "", tsMs = now, kind = DoseKind.BASAL, units = units, durationMin = basal.actionMin,
                k = null, theta = null, kaPerHour = basal.kaPerHour, kePerHour = basal.kePerHour,
                tzOffsetMin = tz, note = basal.label, updatedAt = now,
            ),
        )
        rememberLoggedPreset(basal, presetLabel)
        mirrorToNightscout { nightscoutEnqueuer.enqueueDose(dose, now, holdMs = pushHoldMs()) }
        reforecastAfterCurveWrite()
        return dose.handle("${fmtAmount(units)} U basal · ${basal.label}")
    }

    /** Stickiness, not a setting. Called after persisting, only when caller named a preset. */
    private suspend fun rememberLoggedPreset(spec: InsulinPresetSpec, requestedLabel: String?) {
        if (requestedLabel == null) return
        when (spec.family) {
            InsulinFamily.RapidGamma -> settingsStore.setLastRapidPreset(spec.label)
            InsulinFamily.BasalBateman -> settingsStore.setLastBasalPreset(spec.label)
        }
    }

    /** Dose against a picked insulin type; mirror built from the entity exactly as [logBolus]. */
    suspend fun logTypedDose(type: InsulinType, units: Double): LogHandle {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val dose = insulinController.logDose(type, units)
        mirrorToNightscout { nightscoutEnqueuer.enqueueDose(dose, now, holdMs = pushHoldMs()) }
        // Owed to the row, not the mirror: forecast reads `logged_dose`, never the queue.
        reforecastAfterCurveWrite()
        val kind = if (type.kind == InsulinKind.BOLUS) "bolus" else "basal"
        return dose.handle("${fmtAmount(units)} U $kind · ${type.name}")
    }

    /** One transaction writes the tombstone and recalls a mirror still queued. */
    suspend fun undoLog(handle: LogHandle) {
        val now = System.currentTimeMillis()
        when (handle.kind) {
            LoggedEventKind.MEAL -> repository.tombstoneLoggedMeal(handle.rowId, now)
            LoggedEventKind.DOSE -> repository.tombstoneLoggedDose(handle.rowId, now)
        }
        reforecastAfterCurveWrite()
    }

    /** Gated and swallowing: nothing here may propagate into the receipt handed to the user. */
    private suspend fun mirrorToNightscout(enqueue: suspend () -> Long) {
        if (!repository.nightscoutBridgeEnabled) return
        runCatching { enqueue() }
            .onFailure { Timber.tag("Nightscout").w(it, "mirror enqueue failed") }
    }

    /** Only where the mirror can be recalled: `/api/v1` has no update, so resend double-counts. */
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

    private fun LoggedMealEntity.handle(label: String) = LogHandle(
        kind = LoggedEventKind.MEAL,
        rowId = id,
        clientId = clientId,
        tsMs = tsMs,
        label = label,
    )

    private fun LoggedDoseEntity.handle(label: String) = LogHandle(
        kind = LoggedEventKind.DOSE,
        rowId = id,
        clientId = clientId,
        tsMs = tsMs,
        label = label,
    )

    /** Newest meals/doses interleaved. Reduced to [LogMarker] at the panel edge; no queue join. */
    val loggedEntries: Flow<List<LoggedEntry>> = loggedEntryFeed(LOG_FEED_LIMIT)

    fun loggedEntryFeed(limit: Int): Flow<List<LoggedEntry>> = combine(
        repository.observeRecentLoggedMeals(limit),
        repository.observeRecentLoggedDoses(limit),
        repository.observeRecentLoggedExercise(limit),
    ) { meals, doses, exercise ->
        val rows = meals.map { it.toLoggedEntry() } + doses.map { it.toLoggedEntry() } +
            exercise.map { it.toLoggedEntry() }
        rows
            // Totally ordered, not sorted: an unstable order reshuffles the list on any emission.
            .sortedWith(
                compareByDescending<LoggedEntry> { it.tsMs }
                    .thenBy { it.kind }
                    .thenByDescending { it.rowId },
            )
            .take(limit)
    }

    /** [loggedEntryFeed] over a fixed window instead of a row budget; oldest first. */
    fun loggedEntriesIn(fromMs: Long, toMs: Long): Flow<List<LoggedEntry>> = combine(
        repository.observeLoggedMealsInRange(fromMs, toMs),
        repository.observeLoggedDosesInRange(fromMs, toMs),
        repository.observeLoggedExerciseInRange(fromMs, toMs),
    ) { meals, doses, exercise ->
        (meals.map { it.toLoggedEntry() } + doses.map { it.toLoggedEntry() } + exercise.map { it.toLoggedEntry() })
            .sortedWith(compareBy<LoggedEntry> { it.tsMs }.thenBy { it.kind }.thenBy { it.rowId })
    }

    suspend fun deleteLoggedEntry(entry: LoggedEntry) {
        val now = System.currentTimeMillis()
        when (entry.kind) {
            CurveKind.CARB -> repository.tombstoneLoggedMeal(entry.rowId, now)
            CurveKind.INSULIN -> repository.tombstoneLoggedDose(entry.rowId, now)
            CurveKind.EXERCISE -> exerciseController.deleteLoggedExercise(entry.rowId)
        }
        reforecastAfterCurveWrite()
    }

    /** [source] replayed at [startMs]; its disposal lands in `sample.exercise`, no model input. */
    suspend fun replayExercise(source: ExerciseSession, startMs: Long) {
        exerciseController.replay(source, startMs) ?: return
        reforecastAfterCurveWrite()
    }

    /** Time only: §5 makes the magnitude a function of duration, and the duration is the bout's. */
    suspend fun shiftLoggedExercise(entry: LoggedEntry, tsMs: Long) {
        exerciseController.shiftLoggedExercise(entry.rowId, tsMs) ?: return
        reforecastAfterCurveWrite()
    }

    /** One entry point for every editing surface; a bout carries no amount of its own. */
    suspend fun applyLogEdit(entry: LoggedEntry, edit: LogEdit) {
        when (entry.kind) {
            CurveKind.CARB -> editLoggedMeal(entry, edit.amount, edit.gi, edit.note, edit.tsMs)
            CurveKind.INSULIN -> editLoggedDose(entry, edit.amount, edit.insulin, edit.tsMs)
            CurveKind.EXERCISE -> shiftLoggedExercise(entry, edit.tsMs)
        }
    }

    /** Keeps the row identity; re-mirrors only if the queued copy was still recallable. */
    suspend fun editLoggedMeal(entry: LoggedEntry, grams: Double, gi: Double?, note: String?, tsMs: Long) {
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
                note = note,
            ),
            now,
        ) ?: return
        remirrorEditedTreatment(edited.clientId) {
            nightscoutEnqueuer.enqueueMeal(edited, now, holdMs = pushHoldMs())
        }
        reforecastAfterCurveWrite()
    }

    /** Twin of [editLoggedMeal]; a pick, or new units of a dose-scaled insulin, re-resolve PK. */
    suspend fun editLoggedDose(entry: LoggedEntry, units: Double, insulin: InsulinChoice?, tsMs: Long) {
        requireLoggableDose(units)
        val now = System.currentTimeMillis()
        val old = repository.loggedDoseById(entry.rowId) ?: return
        val choice = insulin ?: if (units == old.units) null else {
            doseScaledOwnChoice(insulinChoices.first(), entry.insulin, entry.detail)
        }
        val edited = when (choice) {
            is InsulinChoice.Preset -> repository.editLoggedDose(old.retypedTo(choice.spec, units, tsMs), now)
            else -> insulinController.editDose(old, (choice as? InsulinChoice.Type)?.type, units, tsMs, now)
        } ?: return
        remirrorEditedTreatment(edited.clientId) {
            nightscoutEnqueuer.enqueueDose(edited, now, holdMs = pushHoldMs())
        }
        reforecastAfterCurveWrite()
    }

    /** Every PK field set as [logBolus]/[logBasal] set it. */
    private suspend fun LoggedDoseEntity.retypedTo(
        spec: InsulinPresetSpec,
        units: Double,
        tsMs: Long,
    ): LoggedDoseEntity = when (spec.family) {
        InsulinFamily.RapidGamma -> {
            val pk = curveEngine.bolusPk(units, spec)
            copy(
                tsMs = tsMs, units = units, kind = DoseKind.BOLUS, durationMin = pk.durationMin,
                k = pk.k, theta = pk.theta, kaPerHour = null, kePerHour = null,
                customCurve = null, note = spec.label,
            )
        }
        InsulinFamily.BasalBateman -> copy(
            tsMs = tsMs, units = units, kind = DoseKind.BASAL, durationMin = spec.actionMin,
            k = null, theta = null, kaPerHour = spec.kaPerHour, kePerHour = spec.kePerHour,
            customCurve = null, note = spec.label,
        )
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
        // Both carried as stored: a builder meal has a combined curve, no single index.
        gi = gi,
        detail = note,
        updatedAtMs = updatedAt,
        mutatedAtMs = mutatedAtMs,
    )

    /** [LoggedEntry.amount] is minutes here, [LoggedEntry.detail] the bout kind. */
    private fun LoggedExerciseEntity.toLoggedEntry() = LoggedEntry(
        rowId = id,
        clientId = clientId,
        kind = CurveKind.EXERCISE,
        insulin = null,
        tsMs = tsMs,
        tzOffsetMin = tzOffsetMin,
        amount = durationMin,
        gi = null,
        detail = exerciseKindLabel(kind),
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
        // Insulin the writer persisted — the curve this row reconstructs through.
        detail = note,
        updatedAtMs = updatedAt,
        mutatedAtMs = mutatedAtMs,
    )

    /** Integral amounts read as "45", a half unit still as "4.5". */
    private fun fmtAmount(v: Double): String =
        if (v == Math.rint(v) && !v.isInfinite()) v.toLong().toString() else "%.1f".format(v)

    /** Mood rides the six-scalar `POST /v1/ingest`; there is no separate curve push. */
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

    /** Every unhidden sensor with a reading, authoritative first, then by newest reading. */
    val backtestSensors: Flow<List<BacktestSensor>> =
        combine(allSources.distinctUntilChanged(), authoritativeSource, settingsStore.showSensorNames) { all, auth, show ->
            Triple(all, auth?.id, show)
        }.mapLatest { (all, authId, show) ->
            all.filter { !it.hidden || it.id == authId }
                .mapNotNull { d ->
                    val extent = repository.readingExtent(d.id) ?: return@mapNotNull null
                    val label = if (show) d.displayName else d.ordinalLabel()
                    BacktestSensor(d.id.value, label, d.id == authId, extent.newestMs)
                }
                .sortedWith(compareByDescending<BacktestSensor> { it.authoritative }.thenByDescending { it.newestMs })
        }

    /** Null means "whichever is authoritative"; unpersisted, must not survive a restart. */
    private val viewedSourceId = MutableStateFlow<com.t1dm.core.model.CgmSourceId?>(null)

    /** Viewed source when chosen and active, else authoritative; `active` self-corrects it. */
    val viewedSource: Flow<CgmSourceDescriptor?> =
        combine(viewedSourceId, authoritativeSource, repository.observeActiveSources()) { viewed, auth, active ->
            viewed?.let { id -> active.firstOrNull { it.id == id } } ?: auth
        }.distinctUntilChanged()

    /** The id, not the descriptor: a descriptor re-emits on any field, dissolving the chart. */
    val viewedSourceKey: Flow<String?> =
        viewedSource.map { it?.id?.value }.distinctUntilChanged()

    /** True while looking at a non-authoritative sensor: overlay/sweep/roll are withheld then. */
    val viewingNonAuthoritative: Flow<Boolean> =
        combine(viewedSource, authoritativeSource) { viewed, auth ->
            viewed != null && auth != null && viewed.id != auth.id
        }.distinctUntilChanged()

    /** What to call the viewed sensor with the name-privacy setting applied. */
    val viewedSourceLabel: Flow<String?> =
        combine(viewedSource, settingsStore.showSensorNames) { d, showNames ->
            d?.incidentalName(showNames)
        }.distinctUntilChanged()

    /** Its serial, same privacy setting; masked it is the ordinal digit, never the real one. */
    val viewedSourceSerial: Flow<String?> =
        combine(viewedSource, settingsStore.showSensorNames) { d, showNames ->
            d?.incidentalSerial(showNames)
        }.distinctUntilChanged()

    /** Its lifecycle state; Idle stands in before a source is adopted. */
    val viewedStatus: Flow<com.t1dm.core.model.CgmSourceStatus> =
        viewedSource.flatMapLatest { d ->
            if (d == null) flowOf(com.t1dm.core.model.CgmSourceStatus.Idle) else registry.statusOf(d.id)
        }.distinctUntilChanged()

    /** The same, for the believed sensor. */
    val authoritativeSourceLabel: Flow<String?> =
        combine(authoritativeSource, settingsStore.showSensorNames) { d, showNames ->
            d?.incidentalName(showNames)
        }.distinctUntilChanged()

    /** Step to the next active sensor, wrapping through the authoritative one. */
    fun cycleViewedSource() {
        val order = registry.sources.value.map { it.id }.filter { it in registry.activeIds.value }
        if (order.size < 2) {
            viewedSourceId.value = null
            return
        }
        val authoritativeId = registry.authoritative.value
        val current = viewedSourceId.value ?: authoritativeId
        val next = order[(order.indexOf(current) + 1).mod(order.size)]
        // Null rather than the id when the step lands back on the believed sensor.
        viewedSourceId.value = if (next == authoritativeId) null else next
    }

    /** How far back the BG panel has loaded. Moves backwards only; windowed to bound cost. */
    private val historyLoadedFromMs = MutableStateFlow(
        System.currentTimeMillis() - INITIAL_HISTORY_WINDOW_MS,
    )

    /** Clamped forward to now; monotone backwards so panning out and back does not re-query. */
    fun extendHistoryBackTo(fromMs: Long) {
        val target = fromMs.coerceAtMost(System.currentTimeMillis())
        historyLoadedFromMs.update { current -> if (target < current) target else current }
    }

    /** The viewed sensor's own readings; two sensors never share a trace. */
    val dashboardReadings: Flow<List<CgmReading>> =
        combine(viewedSource, historyLoadedFromMs) { d, from -> d to from }
            .flatMapLatest { (d, from) ->
                if (d == null) flowOf(emptyList())
                else repository.observeReadingsForSource(d.id, from, Long.MAX_VALUE)
            }

    /** Where the viewed sensor's record begins, or null while empty. One aggregate, not window. */
    val historyFloorMs: Flow<Long?> = viewedSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeOldestTsForSource(d.id)
    }

    /** Active source's trailing [SMOOTHING_PREVIEW_HOURS] of mg/dL, oldest to newest. */
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

    /** Freehand annotation layer, read over the whole store: strokes are few and display-only. */
    val paintStrokes: Flow<List<PaintStroke>> = repository.observePaintStrokes(0L, Long.MAX_VALUE)

    /** Row id the store minted; the only write path the annotation layer has. */
    suspend fun addPaintStroke(stroke: PaintStroke): Long = repository.addPaintStroke(stroke)

    /** Whole strokes only — the eraser and undo never work in units of geometry. */
    suspend fun deletePaintStroke(id: Long) = repository.deletePaintStroke(id)

    val latestReading: Flow<CgmReading?> = authoritativeSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeLatestReading(d.id)
    }

    /** Two readings every glance surface needs, and [directionOf]'s arrow, as one value. */
    val glanceReadings: Flow<Pair<GlanceReadings, BgDirection?>> = authoritativeSource.flatMapLatest { d ->
        if (d == null) {
            flowOf(GlanceReadings.EMPTY to null)
        } else {
            combine(
                repository.observeLatestReading(d.id),
                repository.observeLastMeasuredReading(d.id),
                registry.telemetryOf(d.id),
            ) { latest, measured, sensor -> Triple(latest, measured, sensor) }
                .mapLatest { (latest, measured, sensor) ->
                    GlanceReadings.of(latest, measured) to
                        directionOf(latest, sensor) { repository.recentReadings(d.id, TREND_FIT_POINTS) }
                }
        }
    }

    /** [directionOf] over rows already read, newest first; the widget's pull. */
    suspend fun directionNow(id: com.t1dm.core.model.CgmSourceId, rows: List<CgmReading>): BgDirection? =
        directionOf(rows.firstOrNull(), registry.telemetryOf(id).first()) { rows.take(TREND_FIT_POINTS) }

    /** Viewed source's newest reading, for the bottom chip; latestReading stays authoritative. */
    val viewedReading: Flow<CgmReading?> = viewedSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeLatestReading(d.id)
    }

    /** Viewed sensor's live link strength while its session is held; null with no session. */
    val viewedLinkRssi: Flow<Int?> = viewedSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else registry.rssiOf(d.id)
    }

    /** Follows [viewedReading], so the arrow and the number beside it describe the same sensor. */
    val viewedDirection: Flow<BgDirection?> = viewedSource.flatMapLatest { d ->
        if (d == null) {
            flowOf(null)
        } else {
            combine(repository.observeLatestReading(d.id), registry.telemetryOf(d.id)) { latest, sensor ->
                latest to sensor
            }.mapLatest { (latest, sensor) ->
                directionOf(latest, sensor) { repository.recentReadings(d.id, TREND_FIT_POINTS) }
            }
        }
    }

    /** IOB/COB (§3.6-F), recomputed off-main on any trigger; mapLatest cancels an in-flight run. */
    val iobCob: StateFlow<IobCobReadout?> =
        merge(
            latestReading.map { },
            repository.observeSampleWrites().map { },
            repository.logEvents.map { },
        )
            .onStart { emit(Unit) }
            .mapLatest { runCatching { iobCobNow() }.getOrNull() }
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000), null)

    val serviceRunning = MutableStateFlow(false)

    /** Deterministic alarm picture (§3.6-A); cosmetic consumers never influence when it fires. */
    val alarmState = MutableStateFlow(AlarmState.CLEAR)

    /** Model-predictive urgent alert; gated, suppressed under a breach, cleared under DEATH. */
    val predictiveAlertRaised = MutableStateFlow(false)

    /** [fromMs] through the newest reading. One-shot, never subscribed. */
    suspend fun gameReadings(fromMs: Long): List<CgmReading> {
        val source = repository.observeAuthoritativeSource().first() ?: return emptyList()
        return repository.observeReadingsForSource(source.id, fromMs, Long.MAX_VALUE).first()
    }

    /** The bout's own sensor, not today's: a bout worn on a replaced sensor keeps its trace. */
    suspend fun sessionReadings(fromMs: Long, toMs: Long): List<CgmReading> {
        val source = repository.sourceWithMostReadingsIn(fromMs, toMs) ?: return emptyList()
        return repository.observeReadingsForSource(source, fromMs, toMs).first()
    }

    val graphSettings: GraphSettingsStore by lazy { GraphSettingsStore(repository) }

    /** Eager: a minigame keys its scene on this, and a first emission after mount reloads it. */
    val gameProps: StateFlow<GamePropDensity> =
        settingsStore.gameProps.stateIn(appScope, SharingStarted.Eagerly, GamePropDensity.Sparse)

    val graphRange: Flow<BgRange> get() = graphSettings.range
    val graphWindowHours: Flow<Int> get() = graphSettings.windowHours

    suspend fun setGraphWindowHours(hours: Int) = graphSettings.setWindowHours(hours)

    suspend fun setGraphRange(minMgdl: Int, maxMgdl: Int) = graphSettings.setRange(minMgdl, maxMgdl)

    /** So the lights age without a new emission. 15 s is ample for a 5-min data cadence. */
    private val reachabilityTicker: Flow<Long> = flow {
        while (true) { emit(System.currentTimeMillis()); delay(15_000L) }
    }

    /** Neutral-typed so `:feature:dashboard` never sees `:watch`. */
    val bgReachability: Flow<BgReachability> by lazy {
        combine(latestReading, watchSecurity, reachabilityTicker) { latest, watch, now ->
            BgReachability(
                cgm = cgmLight(latest, now),
                watch = watchLight(watch.phase),
            )
        }
    }

    /** Per-channel "last activity" tokens: a change fires a flash; the value itself is opaque. */
    val bgPulses: Flow<BgPulses> by lazy {
        combine(latestReading, watchSecurity) { latest, watch ->
            BgPulses(
                cgm = latest?.tsMs ?: 0L,
                watch = watch.lastPushMs ?: 0L,
            )
        }
    }

    /** Sensor's own start plus the wear it states or is rated for. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sensorExpiryMs: Flow<Long?> by lazy {
        latestReading.flatMapLatest { latest ->
            if (latest == null) return@flatMapLatest flowOf(null)
            combine(
                registry.sensorStartMsOf(latest.sourceId),
                registry.lifetimeMinOf(latest.sourceId),
            ) { heldStartMs, lifeMin ->
                val startMs = sensorStartMs(latest, heldStartMs) ?: return@combine null
                lifeMin?.let { startMs + it.toLong() * 60_000L }
            }
        }
    }

    /** When the active sensor's warm-up ends, or null. Anchor is `rxWallMs`, not the grid stamp. */
    val sensorWarmupEndMs: Flow<Long?> by lazy {
        combine(latestReading, authoritativeSource) { latest, active ->
            if (latest == null || latest.flag != ReadingFlag.WARMUP) return@combine null
            val mfs = latest.minFromStart ?: return@combine null
            val window = active?.warmupWindowMin ?: return@combine null
            latest.rxWallMs - mfs.toLong() * 60_000L + window.toLong() * 60_000L
        }
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
        WatchLinkPhase.UNPAIRED -> ReachLight(LinkHealth.OFF, "nothing paired")
        WatchLinkPhase.LIVE -> ReachLight(LinkHealth.OK, "paired — pushing every 5 min")
        WatchLinkPhase.SUSPENDED_LOW_POWER -> ReachLight(LinkHealth.DEGRADED, "low-power — push suspended")
        WatchLinkPhase.ERROR -> ReachLight(LinkHealth.DOWN, "link error — re-pair needed")
        else -> ReachLight(LinkHealth.DEGRADED, "connecting — ${phase.name.lowercase().replace('_', ' ')}")
    }

    val statsRepository: StatsRepository by lazy { StatsRepository(repository, nativeCore, dispatchers) }

    private val statsSource by lazy { AppStatsSource(statsRepository, nativeCore) }

    /** App-lifetime, so the window and composite survive Activity churn. */
    val statsViewModel: StatsViewModel by lazy { StatsViewModel(statsSource, appScope) }

    /** Also refreshes the widget in-process: a switch while the FGS is down leaves it stale. */
    fun setUnitSpace(space: com.t1dm.core.model.UnitSpace) {
        appScope.launch {
            statsRepository.setUnitSpace(space)
            runCatching { com.t1dm.app.widget.GlucoseWidget().updateAll(appContext) }
        }
    }

    // ESP32-C3 accessory as a clean removable seam: deleting this block excises the whole feature.

    /** Reads its knobs fresh per call. */
    private val lowPower: AndroidLowPowerProvider by lazy {
        AndroidLowPowerProvider(
            context = appContext,
            enabled = { settingsStore.currentLowPowerEnabled() },
            thresholdPercent = { settingsStore.currentLowPowerPercent() },
            useOsSaver = { settingsStore.currentLowPowerUseOsSaver() },
        )
    }

    /** Polled off-main every 30 s. A read failure fails OPEN (not low-power). */
    val lowPowerActive: Flow<Boolean> = flow {
        while (true) {
            emit(withContext(dispatchers.io) { runCatching { lowPower.isLowPower() }.getOrDefault(false) })
            delay(30_000)
        }
    }

    private val watchScanner by lazy { WatchScanner(appContext) }

    val watchHub: WatchHub by lazy {
        WatchHub(
            centralProvider = { AndroidWatchCentral(appContext, dispatchers, watchScanner) },
            sessionFactory = UniffiWatchSessionFactory(),
            stores = RoomWatchStores(repository, appContext),
            codec = UniffiWatchCodec(),
            glanceSource = AppWatchGlanceSource(
                repository = repository,
                inferenceState = inferenceState,
                // Read live each glance, so a Settings threshold edit reaches the watch.
                thresholdsProvider = { alarmConfig.thresholds },
                edgesProvider = { alarmFanEdges },
                lossMinProvider = { alarmConfig.lossMin },
                sensorTelemetry = { registry.telemetryOf(it).first() },
            ),
            extendedSource = AppWatchExtendedSource(
                repository = repository,
                inferenceState = inferenceState,
                calibratedBands = { p ->
                    calibratedBands(
                        repository.observeBandCalibrations().first(),
                        p.modelId, p.bandsMgdl, p.horizonSteps, p.nQuantiles,
                    )
                },
                stats = statsRepository,
                display = ::watchDisplay,
            ),
            lowPower = lowPower,
            dispatchers = dispatchers,
            config = WatchLinkConfig(enabled = true, autoConnect = true),
        )
    }

    /** SPEC/watch.md §5.7: the theme, thresholds and graph frame the phone itself draws with. */
    private suspend fun watchDisplay(): WatchDisplay {
        val p = com.t1dm.core.design.resolvePalette(
            settingsStore.currentThemeId(),
            settingsStore.currentCustomThemeJson(),
        )
        val t = alarmConfig.thresholds
        val range = graphSettings.currentRange()
        return WatchDisplay(
            dark = p.dark,
            palette = WatchPalette(
                background = p.background.toArgb(),
                surface = p.surface.toArgb(),
                surfaceVariant = p.surfaceVariant.toArgb(),
                primary = p.primary.toArgb(),
                onPrimary = p.onPrimary.toArgb(),
                secondary = p.secondary.toArgb(),
                onSecondary = p.onSecondary.toArgb(),
                ink = p.ink.toArgb(),
                inkMuted = p.inkMuted.toArgb(),
                grid = p.grid.toArgb(),
                urgentLow = p.urgentLow.toArgb(),
                low = p.low.toArgb(),
                inRange = p.inRange.toArgb(),
                high = p.high.toArgb(),
                urgentHigh = p.urgentHigh.toArgb(),
            ),
            thresholds = intArrayOf(t.urgentLowMgdl, t.lowMgdl, t.highMgdl, t.urgentHighMgdl),
            rangeMin = range.minMgdl,
            rangeMax = range.maxMgdl,
            windowH = graphSettings.currentWindowHours(),
            staleMin = AppWatchGlanceSource.STALE_MIN,
            lossMin = alarmConfig.lossMin,
            name = p.displayName,
        )
    }

    /** Pushes the display on any change it carries, and the forecast on each inference cycle. */
    fun startWatchFeeds(scope: CoroutineScope) {
        scope.launch(dispatchers.default) {
            combine(
                settingsStore.themeId,
                settingsStore.customThemeJson,
                alarmConfigFlow,
                graphSettings.range,
                graphSettings.windowHours,
            ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
                .distinctUntilChanged()
                .drop(1)
                .collect { runCatching { watchHub.pushDisplay(System.currentTimeMillis()) } }
        }
        scope.launch(dispatchers.default) {
            inferenceState.map { it.lastCycleTsMs }
                .distinctUntilChanged()
                .drop(1)
                .collect { runCatching { watchHub.pushForecast(System.currentTimeMillis()) } }
        }
    }

    /** The best-connected pairing, for the dashboard light. */
    val watchSecurity: StateFlow<WatchSecurityState> get() = watchHub.summary
    val watchDevices: StateFlow<List<WatchSecurityState>> get() = watchHub.devices
    val watchPairing: StateFlow<WatchSecurityState?> get() = watchHub.pairing

    fun pairWatch() = watchHub.beginPairing()
    fun cancelWatchPairing() = watchHub.cancelPairing()
    /** Null [id] confirms the pairing in progress; otherwise that pairing's rotation. */
    fun confirmWatchSas(id: String?) = watchHub.confirmSas(id)
    fun rotateWatchKeys(id: String?) = watchHub.rotate(id)
    fun unpairWatch(id: String?) = watchHub.unpair(id)

    /** One sensor's live read-outs, folded (combine's arity is 5; panel draws N sensors). */
    private fun cgmSensorLive(
        descriptor: CgmSourceDescriptor,
        recoverable: Set<com.t1dm.core.model.CgmSourceId>,
    ): Flow<com.t1dm.feature.cgm.CgmSensorLive> {
        val id = descriptor.id
        val facts = registry.factsFor(descriptor.vendorId)
        // The typed combine tops out at five flows; the head is folded first.
        val head = combine(
            registry.statusOf(id),
            registry.rssiOf(id),
            registry.telemetryOf(id),
            registry.bindableOf(id),
            registry.sensorStartMsOf(id),
        ) { status, rssi, telemetry, bindable, startMs ->
            CgmSensorHead(
                status = status,
                rssiDbm = rssi,
                telemetry = telemetry,
                bindable = bindable,
                sensorStartMs = startMs,
            )
        }
        val history = combine(
            registry.backfillInFlightOf(id),
            registry.historyExhaustedOf(id),
        ) { inFlight, exhausted -> inFlight to exhausted }
        return combine(
            head,
            history,
            registry.failureOf(id),
            // This sensor's own newest reading, not the believed sensor's.
            repository.observeLatestReading(id),
            registry.lifetimeMinOf(id),
        ) { h, (backfilling, exhausted), failure, latest, lifetimeMin ->
            com.t1dm.feature.cgm.CgmSensorLive(
                status = h.status,
                // Polled link RSSI; null while no session is held, unlike a reading's own RSSI.
                rssiDbm = h.rssiDbm,
                telemetry = h.telemetry,
                bindable = h.bindable,
                sensorAgeMin = latest?.let { sensorAgeMin(it, h.sensorStartMs) },
                readingTsMs = latest?.tsMs,
                lifetimeMin = lifetimeMin,
                ratedCycleDays = facts.ratedCycleDays,
                supportsActivate = facts.supportsActivate,
                supportsHistory = facts.supportsHistory,
                backfillInFlight = backfilling,
                historyExhausted = exhausted,
                failureNote = failure,
                supportsHistoryRepair = facts.supportsHistoryRepair,
                supportsProvision = facts.supportsProvision,
                supportsFrameCrypto = facts.supportsFrameCrypto,
                keyRecoverable = descriptor.id in recoverable,
            )
        }
    }

    /** Every sensor's live read-outs, keyed by id. Rebuilt only when the sensor set changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val cgmSensorsLive: Flow<Map<String, com.t1dm.feature.cgm.CgmSensorLive>> by lazy {
        combine(
            registry.sources
                .map { list -> list.filterNot { it.hidden } }
                .distinctUntilChangedBy { list -> list.map { it.id.value } },
            registry.recoverable,
        ) { list, recoverable -> list to recoverable }
            .flatMapLatest { (list, recoverable) ->
                if (list.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(list.map { d -> cgmSensorLive(d, recoverable).map { d.id.value to it } }) { it.toMap() }
                }
            }
    }

    val cgmPanel: StateFlow<com.t1dm.feature.cgm.CgmPanelState> by lazy {
        combine(
            combine(
                registry.sources, registry.authoritative, registry.activeIds, registry.admittedIds,
            ) { sources, authoritativeId, activeIds, admittedIds ->
                CgmRaw(sources, authoritativeId, activeIds, admittedIds)
            },
            cgmSensorsLive,
            registry.scanning,
            registry.unidentified,
        ) { raw, live, scanning, unidentified ->
            com.t1dm.feature.cgm.cgmPanelState(
                sources = raw.sources,
                authoritativeId = raw.authoritativeId?.value,
                activeIds = raw.activeIds.mapTo(HashSet()) { it.value },
                admittedIds = raw.admittedIds.mapTo(HashSet()) { it.value },
                live = live,
                maxSessions = ConnectedCgmRegistry.MAX_CONCURRENT_SESSIONS,
                scanning = scanning,
                unidentified = unidentified.count,
                unidentifiedRssiDbm = unidentified.bestRssiDbm,
            )
        }.stateIn(appScope, SharingStarted.WhileSubscribed(5_000), com.t1dm.feature.cgm.CgmPanelState())
    }

    /** Authoritative sensor changed: drop the forecast fit to the outgoing sensor's history. */
    fun invalidateInferenceOnSourceChange() = inferenceController.onCgmSourceChanged()

    fun makeAuthoritativeCgm(id: String) =
        registry.setAuthoritative(com.t1dm.core.model.CgmSourceId(id))

    /** Additive — nothing else stops. */
    fun activateCgm(id: String) = registry.activate(com.t1dm.core.model.CgmSourceId(id))

    /** Refused for the authoritative one. */
    fun deactivateCgm(id: String) = registry.deactivate(com.t1dm.core.model.CgmSourceId(id))

    /** A display flag: the source stays on record, so its readings stay in the panel's history. */
    fun hideCgm(id: String) = registry.hide(com.t1dm.core.model.CgmSourceId(id))

    /** Force this family's fresh-sensor activation on one sensor's session, chosen by the user. */
    fun activateCgmSensor(id: String) = registry.activateSensor(com.t1dm.core.model.CgmSourceId(id))

    /** Claim an unclaimed sensor, irreversibly: the frames it writes cannot be undone. */
    fun bindCgmSensor(id: String) = registry.bindSensor(com.t1dm.core.model.CgmSourceId(id))

    /** One NFC tap: patch-info → activate/switch → state saved, sighting becomes adoptable. */
    suspend fun provisionLibre3Sensor(
        accountId: String,
        region: com.t1dm.cgm.Libre3Region,
        link: com.t1dm.cgm.Libre3NfcProvision.NfcVLink,
    ): com.t1dm.cgm.Libre3NfcProvision.Outcome {
        val outcome = com.t1dm.cgm.Libre3NfcProvision(
            link = link,
            native = com.t1dm.cgm.UniffiLibre3Native(),
            nowMs = System::currentTimeMillis,
        ).provision(accountId, region)
        if (outcome is com.t1dm.cgm.Libre3NfcProvision.Outcome.Provisioned) {
            val address = outcome.state.bleAddress
            // The sensor already changed; a Stop now must not drop its only PIN.
            withContext(kotlinx.coroutines.NonCancellable) {
                cgmRepository.saveSensorSecret(
                    com.t1dm.cgm.libre3SourceId(address),
                    outcome.state.encode(),
                )
                registry.rescanNow()
            }
        }
        return outcome
    }

    suspend fun libre3AccountPrefs(): Pair<String?, String?> =
        repository.getKv(KEY_LIBRE3_ACCOUNT) to repository.getKv(KEY_LIBRE3_REGION)

    suspend fun setLibre3AccountPrefs(accountId: String, region: String) {
        val now = System.currentTimeMillis()
        repository.putKv(KEY_LIBRE3_ACCOUNT, accountId, now)
        repository.putKv(KEY_LIBRE3_REGION, region, now)
    }

    fun rescanCgm() = registry.rescanNow()

    fun reconnectCgm(id: String) = registry.reconnect(com.t1dm.core.model.CgmSourceId(id))

    fun fetchCgmHistory(id: String) = registry.fetchHistory(com.t1dm.core.model.CgmSourceId(id))

    fun recoverCgmKey(id: String) = registry.recoverKey(com.t1dm.core.model.CgmSourceId(id))

    /** Console only; null while no Libre 3 data plane is open for [id]. */
    fun libre3DataPlane(id: String): com.t1dm.cgm.Libre3DataPlaneHandle? =
        (registry.sessionOf(com.t1dm.core.model.CgmSourceId(id)) as? com.t1dm.cgm.Libre3ConnectedSource)?.dataPlane

    /** DESTRUCTIVE: drops the sensor's readings and re-dates its wear. */
    fun repairCgmHistory(id: String) = registry.repairHistory(com.t1dm.core.model.CgmSourceId(id))

    fun cgmLog(id: String): Flow<List<com.t1dm.core.model.CgmLogEntry>> =
        cgmLogs.follow(com.t1dm.core.model.CgmSourceId(id))

    fun echoCgmConsole(id: String, line: String) =
        cgmLogs.of(com.t1dm.core.model.CgmSourceId(id)).i("CgmConsole", line)

    suspend fun cgmReadingStats(id: String): com.t1dm.feature.cgm.CgmReadingStats {
        val sourceId = com.t1dm.core.model.CgmSourceId(id)
        val counts = repository.readingCounts(sourceId)
        val extent = repository.readingExtent(sourceId)
        return com.t1dm.feature.cgm.CgmReadingStats(counts.total, counts.measured, extent?.oldestMs, extent?.newestMs)
    }

    /** Newest first. */
    suspend fun recentCgmReadings(id: String, n: Int): List<com.t1dm.core.model.CgmReading> =
        repository.recentReadings(com.t1dm.core.model.CgmSourceId(id), n)

    /** Any sensor's window, unlike [setSensorWarmupMin]; the registry clamps it. */
    fun setCgmWarmupMin(id: String, minutes: Int) =
        registry.setWarmupWindowMin(com.t1dm.core.model.CgmSourceId(id), minutes)

    /** One line per entry, nothing folded; false when the file could not be written. */
    suspend fun exportCgmLog(id: String, name: String, uri: android.net.Uri): Boolean = withContext(dispatchers.io) {
        val entries = cgmLogs.snapshot(com.t1dm.core.model.CgmSourceId(id))
        runCatching {
            val out = appContext.contentResolver.openOutputStream(uri, "wt") ?: error("no stream for $uri")
            out.bufferedWriter().use { w ->
                com.t1dm.feature.cgm.writeCgmLogExport(
                    out = w,
                    name = name,
                    id = id,
                    entries = entries,
                    zone = java.time.ZoneId.systemDefault(),
                    nowMs = System.currentTimeMillis(),
                )
            }
        }.onFailure { Timber.w(it, "CGM log export failed") }.isSuccess
    }

    /** Active source's sensor warm-up window, minutes — nothing to do with [setWarmupHours]. */
    suspend fun setSensorWarmupMin(minutes: Int) {
        val id = repository.authoritativeSourceId() ?: return
        registry.setWarmupWindowMin(id, minutes)
    }

    /** Suspends in low-power mode. */
    suspend fun pushToWatch(nowMs: Long) = watchHub.tick(nowMs)

    companion object {
        private const val CT5_IMPORT_FILE = "cgm_import.json"

        /** Hysteresis: resumes at `thresholdC - this`, so a hovering reading cannot flap it. */
        const val THERMAL_RESUME_MARGIN_C = 2.0

        /** The mixed-meal default the bolus advisor also falls back to. */
        const val PROBE_GI = 55.0

        /** How long a probed ISF/ICR estimate stands before a displaying panel re-probes. */
        const val SENSITIVITY_TTL_MS = 30 * 60_000L

        /** A probe that withheld a figure waits this long — one inference cycle. */
        const val SENSITIVITY_RETRY_MS = 5 * 60_000L

        /** Past this the figures describe a context that is no longer the patient's. */
        const val SENSITIVITY_LAPSE_MS = 2 * 60 * 60_000L
    }
}
