package com.t1dm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.t1dm.app.notify.GlanceReadings
import com.t1dm.alerts.ActiveAlarm
import com.t1dm.alerts.AlarmController
import com.t1dm.alerts.AlarmEngine
import com.t1dm.alerts.AlarmKind
import com.t1dm.alerts.AlarmSeverity
import com.t1dm.alerts.AlertActuatorConfig
import com.t1dm.alerts.AndroidAlarmNotifier
import com.t1dm.alerts.isDismissable
import com.t1dm.alerts.kind
import com.t1dm.app.MainActivity
import com.t1dm.app.T1dmApplication
import com.t1dm.app.di.AppContainer
import com.t1dm.app.notify.AlarmActionReceiver
import com.t1dm.app.notify.AlertRepeatScheduler
import com.t1dm.app.notify.BgDirection
import com.t1dm.app.notify.BgGlance
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.app.notify.LiveNotificationPresenter
import com.t1dm.app.notify.NotificationIcons
import com.t1dm.app.notify.PredictiveAlertPresenter
import com.t1dm.app.settings.SettingsStore
import androidx.glance.appwidget.updateAll
import com.t1dm.app.widget.GlucoseWidget
import androidx.compose.ui.graphics.toArgb
import com.t1dm.core.design.applyWidgetPalette
import com.t1dm.core.design.resolvePalette
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.PredictedTime
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import kotlin.math.sin
import com.t1dm.inference.SyntheticContext
import com.t1dm.sensors.RepositoryStepSampleWriter
import com.t1dm.sensors.StepBucketer
import com.t1dm.sensors.StepRecorder
import com.t1dm.sensors.StepSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.TimeZone

private data class GlanceInputs(
    val readings: GlanceReadings,
    val direction: BgDirection?,
    val state: InferenceState,
    val unit: UnitSpace,
    val theme: Pair<String, String?>,
)

/** The next epoch-aligned period boundary strictly after [nowMs]. */
internal fun nextTimedCycleMs(nowMs: Long, periodMin: Int): Long {
    // A 0-minute period (corrupt kv) would divide by zero.
    val periodMs = periodMin.coerceAtLeast(1) * 60_000L
    return (nowMs / periodMs + 1) * periodMs
}

/** Foreground service (§2.3): GATT read, step counter, heartbeat, model-free alarms. */
class CgmScanService : LifecycleService() {

    private lateinit var container: AppContainer
    private lateinit var alarmEngine: AlarmEngine
    private lateinit var alarmController: AlarmController
    private var alarmNotifier: AndroidAlarmNotifier? = null
    /** Engine's single-thread slice; snooze/config updates post here to serialise (§2.3). */
    private var alarmScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /** Touched only from `refreshGlanceSurfaces`, on one coroutine — no synchronization. */
    private var lastWidgetPushSig: List<Any?>? = null

    /** Alarm-engine feed: authoritative-source readings plus injected ones. */
    private val readingBus = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = 128)

    private lateinit var livePresenter: LiveNotificationPresenter
    private val predictiveAlerts by lazy {
        PredictiveAlertPresenter(this, ::fullScreenIntent, ::contentIntent)
    }
    private val repeatScheduler by lazy { AlertRepeatScheduler(this) }
    @Volatile private var repeatArmed = false

    /** Folds injected cumulatives exactly as the sensor's are folded. */
    private val debugBucketer = StepBucketer()

    override fun onCreate() {
        super.onCreate()
        container = (application as T1dmApplication).container

        createChannel()
        // Fails closed without BLUETOOTH_SCAN/CONNECT; MainActivity restarts once granted.
        val missing = missingGrants(this)
        if (missing.isNotEmpty()) {
            Timber.w(
                "CgmScanService: missing %s — the connected CGM session cannot start yet; stopping. " +
                    "It restarts once the permissions are granted.",
                missing.joinToString { it.substringAfterLast('.') },
            )
            stopSelf()
            return
        }
        startForegroundNotified()
        acquireWakeLock()
        livePresenter = LiveNotificationPresenter(this, CH_SERVICE, contentIntent())

        startPipeline()
        container.serviceRunning.value = true
    }

    private fun startPipeline() {
        if (started) return
        started = true

        // Deterministic alarm path, single-threaded (§2.3): serialises the two collectors.
        val alarmScope = CoroutineScope(
            lifecycleScope.coroutineContext + container.dispatchers.default.limitedParallelism(1),
        )
        this.alarmScope = alarmScope
        // §3.6 C1: no silence outlives its alarm; a restart re-fires from a clean gate.
        container.clearSnooze()
        alarmScope.launch {
            container.refreshAlertActuatorConfig()
            container.refreshAlarmConfig()
            alarmEngine = AlarmEngine(container.alarmConfig)
            runCatching {
                container.repository.authoritativeSourceId()
                    ?.let { container.repository.observeLastMeasuredReading(it).first() }
            }
                .onFailure { Timber.tag(TAG).w(it, "alarm seed read failed") }
                .getOrNull()
                ?.let { alarmEngine.seed(it, System.currentTimeMillis()) }
            val notifier = AndroidAlarmNotifier(
                context = this@CgmScanService,
                // Lambda, not a snapshot: a value here would freeze the choice for service life.
                actuatorConfig = { container.alertActuatorSnapshot },
                fullScreenIntent = ::fullScreenIntent,
                contentIntent = ::contentIntent,
                smallIcon = { NotificationIcons.icon(this@CgmScanService) },
                accentColor = { container.notificationAccentArgb },
                // DEATH mode: engine keeps firing (§3.6-A), notifier presents nothing.
                suppressed = { container.deathModeSnapshot },
                // Presentation silence only (§3.6 C1-C5); the engine still fires.
                snoozeState = { container.snoozeSnapshot },
                snoozeIntent = { alarm -> alarmActionIntent(alarm, AlarmActionReceiver.ACTION_ALARM_SNOOZE) },
                dismissIntent = { alarm -> alarmActionIntent(alarm, AlarmActionReceiver.ACTION_ALARM_DISMISS) },
                snoozeMinutes = { container.snoozeMinSnapshot },
                // Sound/vibration at most once per interval while an alarm holds the same band.
                minActuationIntervalMs = { container.alarmConfig.minActuationIntervalMin * 60_000L },
            )
            alarmNotifier = notifier
            // Battery-sensor °C for the deterministic over-temperature alarm (§3.6-A), live.
            alarmController = AlarmController(
                alarmEngine, notifier, container.alarmConfig,
                temperatureC = { container.readDeviceTempC() },
            )
            alarmController.launchIn(alarmScope, readingBus)
            // Settings save feeds this; never clears an active breach, next reading re-classifies.
            container.setAlarmConfigSink { cfg ->
                alarmScope.launch {
                    alarmEngine.updateConfig(cfg)
                    if (::alarmController.isInitialized) alarmController.updateConfig(cfg)
                }
            }
            alarmController.state.collect { st ->
                // §3.6 C1/C3.
                container.pruneSnooze(st)
                // Downstream of the engine: no consumer here changes when an alarm fires (§3.6-A).
                container.alarmState.value = st
                Timber.tag(TAG).i(
                    "ALARM active=%b threshold=%s loss=%s primarySeverity=%s",
                    st.isActive, st.threshold?.band, st.signalLoss?.windowMin, st.primary?.severity,
                )
                // Edge-triggered: re-announces an already-active alarm only, doze-resilient.
                val critical = st.isActive && st.primary?.severity == AlarmSeverity.CRITICAL &&
                    !container.deathModeSnapshot
                if (critical && !repeatArmed) {
                    repeatScheduler.schedule(container.alarmConfig.repeatCadenceMin)
                    repeatArmed = true
                } else if (!critical && repeatArmed) {
                    repeatScheduler.cancel()
                    repeatArmed = false
                }
            }
        }

        // Narrow to the authoritative source (§3.6-A): never synthesise across a disconnect.
        lifecycleScope.launch {
            container.registry.readings()
                .filter { it.sourceId == container.registry.authoritative.value }
                // main: an expired sensor raises no alarm; the live flow is its one consumer.
                .filter { !com.t1dm.app.cgm.isPastExpiry(it, container.registry.lifetimeMinOf(it.sourceId).first()) }
                .collect { readingBus.emit(it) }
        }

        // `isInitialized` guards: the alarm-init coroutine suspends on Room reads before assigning.
        lifecycleScope.launch {
            var previous: CgmSourceId? = null
            container.registry.authoritative.collect { id ->
                val promoted = previous != null && id != null && id != previous
                previous = id
                if (!promoted) return@collect
                val now = System.currentTimeMillis()
                if (::alarmEngine.isInitialized) alarmScope?.launch { alarmEngine.onSourceChanged(now) }
                container.invalidateInferenceOnSourceChange()
            }
        }

        startCgmCoordinator()

        startSteps()

        // Grid liveness (§2.3 — the service owns the tick).
        lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                runCatching { container.repository.putKv(KV_LAST_ALIVE, now.toString(), now) }
                delay(HEARTBEAT_MS)
            }
        }

        // Sibling scope, structurally independent of the model-free alarm path (§2.3, §3.6-A).
        lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(GRID_MS - (now % GRID_MS))
                // Opportunistic; the periodic drain and WorkManager are the fallbacks.
                runCatching { container.syncManager.drainNow() }
                    .onFailure { Timber.tag(TAG).w(it, "post-tick drain failed (independent of inference)") }
                // Off-main inside pushNow; self-suspends while the watch is in low-power mode.
                runCatching { container.pushToWatch(System.currentTimeMillis()) }
                    .onFailure { Timber.tag(TAG).w(it, "watch push failed (independent of alarm/inference)") }
                runCatching { container.repository.pruneRawSamples(System.currentTimeMillis()) }
                    .onFailure { Timber.tag(TAG).w(it, "raw sample prune failed (independent of alarm/inference)") }
            }
        }

        // The tick alone leaves a peripheral up to one grid step behind a 1-min sensor.
        lifecycleScope.launch {
            readingBus
                .filter { it.provenance == ReadingProvenance.MEASURED }
                .conflate()
                .collect {
                    runCatching { container.watchHub.pushReading(System.currentTimeMillis()) }
                        .onFailure { Timber.tag(TAG).w(it, "watch push failed (independent of alarm/inference)") }
                }
        }

        // Forecast-cadence driver, sibling of the alarm path (§3.6-A); drives runFromHistory only.
        lifecycleScope.launch(container.dispatchers.default) {
            container.inferenceController.refreshModels()
            val adaptiveTicks = readingBus
                .filter { container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_ADAPTIVE }
                .map { System.currentTimeMillis() }
            val timedTicks = flow {
                while (isActive) {
                    if (container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_TIMED) {
                        val now = System.currentTimeMillis()
                        delay((nextTimedCycleMs(now, container.forecastPeriodMin()) - now).coerceAtLeast(1L))
                        // The user may have left TIMED while we slept.
                        if (container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_TIMED) {
                            emit(System.currentTimeMillis())
                        }
                    } else {
                        delay(MODE_POLL_MS)
                    }
                }
            }
            merge(adaptiveTicks, timedTicks).conflate().collect { nowMs ->
                runCatching { container.inferenceController.runFromHistory(InferenceCause.GRID_TICK, nowMs) }
                    .onFailure { Timber.tag(TAG).w(it, "inference cycle failed (alarm path unaffected)") }
            }
        }

        container.syncManager.launch(lifecycleScope)

        container.watchHub.start(lifecycleScope)
        container.startWatchFeeds(lifecycleScope)

        // One shared BgGlanceComputer so surfaces agree; off-main since lifecycleScope is Main.
        lifecycleScope.launch(container.dispatchers.default) {
            val ticker = kotlinx.coroutines.flow.flow {
                while (isActive) { emit(Unit); delay(NOTIF_TICK_MS) }
            }
            val theme = combine(
                container.settingsStore.themeId,
                container.settingsStore.customThemeJson,
            ) { id, json -> id to json }
            combine(
                // Pair, not newest row: a promoted reconstruction is a row like any other.
                container.glanceReadings.onStart { emit(GlanceReadings.EMPTY to null) },
                container.inferenceState,
                container.statsRepository.unitSpace.onStart { emit(UnitSpace.MgDl) },
                ticker,
                theme,
            ) { (readings, direction), state, unit, _, themeSig ->
                GlanceInputs(readings, direction, state, unit, themeSig)
            }.collectLatest { (readings, direction, state, unit, themeSig) ->
                runCatching { refreshGlanceSurfaces(readings, direction, state, unit, themeSig) }
                    .onFailure { Timber.tag(TAG).w(it, "glance refresh failed (alarm path unaffected)") }
            }
        }

        // The snapshot gate covers future emissions; the ongoing monitoring notification stays.
        lifecycleScope.launch {
            container.deathMode.collect { on -> if (on) { alarmNotifier?.clear(); predictiveAlerts.clear() } }
        }
    }

    /** The §3.6 gate lives inside [BgGlanceComputer]; this only renders. */
    private suspend fun refreshGlanceSurfaces(
        readings: GlanceReadings,
        direction: BgDirection?,
        state: InferenceState,
        unit: UnitSpace,
        themeSig: Pair<String, String?>,
    ) {
        val nowMs = System.currentTimeMillis()
        val glance: BgGlance = BgGlanceComputer.compute(
            readings = readings,
            state = state,
            thresholds = container.alarmConfig.thresholds,
            edges = container.alarmFanEdges,
            lossMin = container.alarmConfig.lossMin,
            staleMin = 15,
            nowMs = nowMs,
            trend = direction?.trend,
        )
        val nextForecastAtMs = if (container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_TIMED) {
            nextTimedCycleMs(nowMs, container.forecastPeriodMin())
        } else {
            null
        }
        // Same (id, json) that drove this refresh, resolved once so both surfaces repaint together.
        val (themeId, customJson) = themeSig
        val palette = resolvePalette(themeId, customJson)
        val accent = palette.primary.toArgb()
        // A widget push parcels ~2 MB: only when what it draws changed.
        val widgetSig = widgetPushSig(
            glance, readings.lastMeasured?.rxWallMs, unit, accent, state.selectedPredictedTime, themeId, customJson,
        )
        val widgetChanged = widgetSig != lastWidgetPushSig
        if (widgetChanged) lastWidgetPushSig = widgetSig

        val nm = getSystemService(NotificationManager::class.java)
        // Every refresh: the body counts seconds.
        runCatching {
            nm.notify(
                NOTIF_ID,
                livePresenter.build(glance, unit, accent, state.selectedPredictedTime, nextForecastAtMs),
            )
        }

        val deterministicCriticalActive = ::alarmController.isInitialized &&
            alarmController.state.value.threshold?.severity == AlarmSeverity.CRITICAL
        // Minigame interlock: this buzzes the same actuator, so a cosmetic effect must yield to it.
        container.predictiveAlertRaised.value = if (container.deathModeSnapshot) {
            predictiveAlerts.clear()
            false
        } else {
            predictiveAlerts.update(
                glance, container.alertActuatorSnapshot, deterministicCriticalActive, accent,
            )
        }

        // Seed the globals the widget reads headlessly BEFORE pushing it.
        if (widgetChanged) {
            applyWidgetPalette(palette)
            runCatching { GlucoseWidget().updateAll(this) }
        }
        // One delayed re-render settles the accent; age is a Room read via the toggle.
        val ageMs = readings.lastMeasured?.let { System.currentTimeMillis() - it.rxWallMs }
            ?: Long.MAX_VALUE
        val animate = ageMs in 0 until GlucoseWidget.FRESH_WINDOW_MS &&
            runCatching { container.settingsStore.currentAnimationsEnabled() }.getOrDefault(true)
        if (animate) {
            lifecycleScope.launch(container.dispatchers.default) {
                delay(GlucoseWidget.FRESH_WINDOW_MS - ageMs + 150L)
                runCatching { GlucoseWidget().updateAll(this@CgmScanService) }
            }
        }
    }

    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun fullScreenIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Distinct request codes per (action, kind) so threshold and signal buttons never collide. */
    private fun alarmActionIntent(alarm: ActiveAlarm, action: String): PendingIntent {
        val kind = alarm.kind()
        val intent = Intent(this, AlarmActionReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmActionReceiver.EXTRA_ALARM_KIND, kind.name)
        val requestCode = 20 + action.hashCode() * 3 + kind.ordinal
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** §3.6 C1-C5: reads the LIVE engine state so the snooze records the band firing now. */
    private fun handleAlarmAction(intent: Intent, dismiss: Boolean) {
        val kindName = intent.getStringExtra(AlarmActionReceiver.EXTRA_ALARM_KIND) ?: return
        val kind = runCatching { AlarmKind.valueOf(kindName) }.getOrNull() ?: return
        if (!::alarmController.isInitialized) return
        val st = alarmController.state.value
        val alarm: ActiveAlarm? = when (kind) {
            AlarmKind.THRESHOLD -> st.threshold
            AlarmKind.SIGNAL_LOSS -> st.signalLoss
            AlarmKind.WEAK_SIGNAL -> st.weakSignal
            AlarmKind.OVER_TEMPERATURE -> null // never snoozable (§3.6 C5)
        }
        if (alarm == null) return
        // Urgent tiers are snooze-only; SnoozeState.dismiss refuses too, bails before the gate.
        if (dismiss && !alarm.isDismissable()) {
            Timber.tag(TAG).i("ALARM_ACTION DISMISS ignored — %s is not dismissable (urgent tier)", kind)
            return
        }
        val scope = alarmScope ?: lifecycleScope
        scope.launch {
            val until = System.currentTimeMillis() + container.currentSnoozeMin().coerceAtLeast(1) * 60_000L
            container.snoozeAlarm(alarm, until, dismiss)
            // Re-emit so the just-silenced alarm is cancelled at once.
            alarmNotifier?.emit(alarmController.state.value)
            Timber.tag(TAG).i("ALARM_ACTION %s kind=%s untilMs=%d", if (dismiss) "DISMISS" else "SNOOZE", kind, until)
        }
    }

    /** Deliberately unguarded: an early return here would leave the registry unhydrated. */
    private fun startCgmCoordinator() {
        runCatching { container.registry.start() }
            .onFailure { Timber.tag(TAG).w(it, "Failed to start connected CGM coordinator") }
    }

    private fun startSteps() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val source = StepSource(sm)
        if (!source.isAvailable()) {
            Timber.tag(TAG).i("No TYPE_STEP_COUNTER; step recorder idle")
            return
        }
        val writer = RepositoryStepSampleWriter(container.repository)
        val recorder = StepRecorder(source, writer, container.dispatchers)
        lifecycleScope.launch { runCatching { recorder.run() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_INJECT_READING -> injectReading(
                bgMgdl = intent.getIntExtra(EXTRA_BG, 120),
                ageMin = intent.getIntExtra(EXTRA_AGE_MIN, 0),
                warmup = intent.getBooleanExtra(EXTRA_WARMUP, false),
                trendTenths = intent.getIntExtra(EXTRA_TREND, 0),
            )
            ACTION_FORCE_SIGNAL_LOSS -> injectReading(
                bgMgdl = intent.getIntExtra(EXTRA_BG, 120),
                // Backdate past the loss window so the very next engine evaluation fires it.
                ageMin = container.alarmConfig.lossMin + 5,
                warmup = false,
                trendTenths = 0,
            )
            ACTION_INJECT_STEPS -> injectStepCounter(intent.getLongExtra(EXTRA_CUMULATIVE, 0L))
            ACTION_RUN_CYCLE -> runSyntheticCycle()
            ACTION_FORCE_DEGENERATE -> lifecycleScope.launch {
                container.inferenceController.refreshModels()
                container.inferenceController.debugPublishDegenerate(System.currentTimeMillis())
            }
            ACTION_FORCE_PREDICT -> {
                val start = intent.getIntExtra(EXTRA_START_BG, 120)
                val end = intent.getIntExtra(EXTRA_END_BG, 55)
                injectReading(bgMgdl = start, ageMin = 0, warmup = false, trendTenths = -18)
                lifecycleScope.launch {
                    container.inferenceController.refreshModels()
                    container.inferenceController.debugPublishForecast(
                        System.currentTimeMillis(), start.toDouble(), end.toDouble(),
                    )
                }
            }
            AlarmActionReceiver.ACTION_ALARM_SNOOZE -> handleAlarmAction(intent, dismiss = false)
            AlarmActionReceiver.ACTION_ALARM_DISMISS -> handleAlarmAction(intent, dismiss = true)
            ACTION_ALERT_REPEAT -> {
                val st = if (::alarmController.isInitialized) alarmController.state.value else null
                if (st != null && st.isActive && st.primary?.severity == AlarmSeverity.CRITICAL) {
                    alarmNotifier?.emit(st)
                    repeatScheduler.schedule(container.alarmConfig.repeatCadenceMin)
                } else {
                    repeatScheduler.cancel()
                    repeatArmed = false
                }
            }
            ACTION_SEED_CONTEXT -> seedMeasuredContext(intent.getDoubleExtra(EXTRA_HOURS, 25.0))
            ACTION_RUN_GRID_TICK -> lifecycleScope.launch {
                container.inferenceController.refreshModels()
                container.inferenceController.runFromHistory(
                    cause = InferenceCause.GRID_TICK, nowMs = System.currentTimeMillis(),
                )
            }
            ACTION_SET_WARMUP -> lifecycleScope.launch {
                container.setWarmupHours(intent.getIntExtra(EXTRA_HOURS, 24))
            }
            ACTION_LOG_MEAL -> logMeal(
                grams = intent.getDoubleExtra(EXTRA_GRAMS, 60.0),
                gi = intent.getDoubleExtra(EXTRA_GI, 80.0),
                ageMin = intent.getIntExtra(EXTRA_AGE_MIN, 0),
            )
            ACTION_LOG_BOLUS -> logBolus(
                units = intent.getDoubleExtra(EXTRA_UNITS, 4.0),
                ageMin = intent.getIntExtra(EXTRA_AGE_MIN, 0),
            )
            ACTION_LOG_BASAL -> logBasalDebug(
                units = intent.getDoubleExtra(EXTRA_UNITS, 20.0),
                tresiba = intent.getIntExtra(EXTRA_TRESIBA, 0) != 0,
            )
            ACTION_LOG_MOOD -> lifecycleScope.launch {
                container.saveMood(intent.getIntExtra(EXTRA_MOOD, 3))
            }
            ACTION_WATCH_PAIR -> { container.pairWatch(); Timber.tag(TAG).i("WATCH_PAIR") }
            // No EXTRA_DEVICE_ID confirms the pairing in progress; with it, that device's rotation.
            ACTION_WATCH_CONFIRM -> {
                container.confirmWatchSas(intent.getStringExtra(EXTRA_DEVICE_ID))
                Timber.tag(TAG).i("WATCH_CONFIRM")
            }
            // EXTRA_DEVICE_ID may be omitted while exactly one peripheral is paired.
            ACTION_WATCH_ROTATE -> {
                container.rotateWatchKeys(intent.getStringExtra(EXTRA_DEVICE_ID))
                Timber.tag(TAG).i("WATCH_ROTATE")
            }
            ACTION_WATCH_UNPAIR -> {
                container.unpairWatch(intent.getStringExtra(EXTRA_DEVICE_ID))
                Timber.tag(TAG).i("WATCH_UNPAIR")
            }
            ACTION_WATCH_PUSH -> lifecycleScope.launch {
                container.pushToWatch(System.currentTimeMillis())
                Timber.tag(TAG).i("WATCH_PUSH state=%s", container.watchSecurity.value.phase)
            }
        }
        return START_STICKY
    }

    /** ageMin > 0 backdates the row so its curve sits inside the context window (§3.3). */
    private fun logMeal(grams: Double, gi: Double, ageMin: Int) {
        lifecycleScope.launch {
            if (ageMin <= 0) {
                container.logCarb(grams, gi)
            } else {
                val now = System.currentTimeMillis()
                val ts = snapToGrid(now - ageMin * 60_000L)
                val (k, theta, dur) = CurveEngine.Presets.carbGammaForGi(gi)
                container.repository.logMeal(
                    LoggedMealEntity(
                        clientId = "", tsMs = ts, grams = grams, gi = gi, k = k, theta = theta,
                        durationMin = dur, customCurve = null,
                        tzOffsetMin = TimeZone.getDefault().getOffset(ts) / 60_000,
                        note = "backdated", updatedAt = now,
                    ),
                )
            }
            Timber.tag(TAG).i("LOG_MEAL grams=%.0f gi=%.0f ageMin=%d", grams, gi, ageMin)
        }
    }

    /** `ageMin > 0` backdates the dose so its action pulls down inside the context window. */
    private fun logBolus(units: Double, ageMin: Int) {
        lifecycleScope.launch {
            if (ageMin <= 0) {
                // No preset named: write inherits the last-logged insulin, an accepted advisory.
                container.logBolus(units)
            } else {
                val now = System.currentTimeMillis()
                val ts = snapToGrid(now - ageMin * 60_000L)
                val pk = container.curveEngine.bolusPk(
                    units,
                    container.curveEngine.defaultPreset(InsulinFamily.RapidGamma),
                )
                container.repository.logLoggedDose(
                    LoggedDoseEntity(
                        clientId = "", tsMs = ts, kind = DoseKind.BOLUS, units = units, durationMin = pk.durationMin,
                        k = pk.k, theta = pk.theta, kaPerHour = null, kePerHour = null,
                        customCurve = null,
                        tzOffsetMin = TimeZone.getDefault().getOffset(ts) / 60_000,
                        note = "backdated", updatedAt = now,
                    ),
                )
            }
            Timber.tag(TAG).i("LOG_BOLUS units=%.1f ageMin=%d", units, ageMin)
        }
    }

    /** Matched against the catalogue labels; a renamed/dropped preset degrades to last-logged. */
    private fun logBasalDebug(units: Double, tresiba: Boolean) {
        lifecycleScope.launch {
            val brand = if (tresiba) "Tresiba" else "Lantus"
            val label = container.insulinPresetCatalog()
                .firstOrNull { it.family == InsulinFamily.BasalBateman && it.label.contains(brand, ignoreCase = true) }
                ?.label
            container.logBasal(units, label)
            Timber.tag(TAG).i("LOG_BASAL units=%.1f tresiba=%b preset=%s", units, tresiba, label ?: "(last logged)")
        }
    }

    /** Debug: seeds the warmup numerator and the context history with signal, sensor-free. */
    private fun seedMeasuredContext(hours: Double) {
        lifecycleScope.launch {
            val src = ensureActiveSource()
            val now = System.currentTimeMillis()
            val anchor = snapToGrid(now)
            val steps = (hours * 60.0 / 5.0).toInt().coerceIn(1, 4096)
            for (i in 0 until steps) {
                val ts = anchor - (steps - 1L - i) * GRID_MS
                val bg = (120.0 + 30.0 * sin(i / 20.0)).coerceIn(70.0, 200.0)
                container.repository.upsertReading(
                    CgmReading(
                        sourceId = src, tsMs = ts, bgMgdl = bg.toInt(),
                        trendTenthsPerMin = 0, minFromStart = i, quality = 100,
                        provenance = ReadingProvenance.MEASURED, flag = ReadingFlag.NORMAL,
                        tzOffsetMin = TimeZone.getDefault().getOffset(ts) / 60_000,
                        rxWallMs = ts, rssi = -60,
                    ),
                )
            }
            Timber.tag(TAG).i("SEED_CONTEXT hours=%.1f steps=%d src=%s", hours, steps, src.value)
        }
    }

    /** Debug: one full cycle on a synthetic series, over the real controller path. */
    private fun runSyntheticCycle() {
        lifecycleScope.launch {
            container.inferenceController.refreshModels()
            val now = System.currentTimeMillis()
            container.inferenceController.runCycle(
                cause = InferenceCause.SYNTHETIC,
                series = SyntheticContext.plausible24h(anchorTsMs = now),
                nowMs = now,
            )
        }
    }

    /** An injected reading contests its grid slot; vary ageMin to land a new slot. */
    private fun injectReading(bgMgdl: Int, ageMin: Int, warmup: Boolean, trendTenths: Int) {
        lifecycleScope.launch {
            val src = ensureActiveSource()
            val now = System.currentTimeMillis()
            val rxWall = now - ageMin * 60_000L
            val tsMs = snapToGrid(rxWall)
            val reading = CgmReading(
                sourceId = src,
                tsMs = tsMs,
                bgMgdl = bgMgdl,
                trendTenthsPerMin = trendTenths,
                minFromStart = (now / 60_000L % 100_000L).toInt(),
                quality = 100,
                provenance = ReadingProvenance.MEASURED,
                flag = if (warmup) ReadingFlag.WARMUP else ReadingFlag.NORMAL,
                tzOffsetMin = TimeZone.getDefault().getOffset(rxWall) / 60_000,
                rxWallMs = rxWall,
                rssi = -60,
            )
            runCatching { container.repository.upsertReading(reading) }
                .onFailure { Timber.tag(TAG).w(it, "inject persist failed") }
            readingBus.emit(reading)
            Timber.tag(TAG).i(
                "INJECT bg=%d ageMin=%d warmup=%b ts=%d src=%s",
                bgMgdl, ageMin, warmup, tsMs, src.value,
            )
        }
    }

    private fun injectStepCounter(cumulative: Long) {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val buckets = debugBucketer.onSample(now, cumulative)
            val tz = TimeZone.getDefault().getOffset(now) / 60_000
            for (b in buckets) container.repository.recordSteps(b.bucketStartMs, tz, b.steps, now)
            Timber.tag(TAG).i("INJECT steps cumulative=%d -> %s", cumulative, buckets)
        }
    }

    private suspend fun ensureActiveSource(): CgmSourceId {
        container.repository.authoritativeSourceId()?.let { return it }
        val now = System.currentTimeMillis()
        container.repository.upsertSource(
            CgmSourceDescriptor(
                id = DEBUG_SOURCE,
                vendorId = "aidexx",
                // Own class, not the real AiDEX X one, so these appear only while selected.
                sensorModelId = CgmSensorModelId.AIDEX_DEBUG,
                // Nothing advertised it, so there is no name to record.
                advertName = null,
                displayName = "AiDEX X DEBUG",
                serialSuffix = "DEBUG",
                warmupWindowMin = 60,
                passiveOnly = true,
            ),
            authoritative = true,
            nowMs = now,
        )
        return DEBUG_SOURCE
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // START_STICKY covers process death; this covers a swipe-away.
        start(applicationContext)
        CgmWatchdog.enqueue(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        container.serviceRunning.value = false
        // So a post-teardown Settings save doesn't touch a dead engine.
        runCatching { container.setAlarmConfigSink(null) }
        runCatching { container.registry.stop() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startForegroundNotified() {
        val notif: Notification = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(NotificationIcons.res())
            .setColor(container.notificationAccentArgb)
            .setContentTitle("Monitoring active")
            .setContentText("Watching CGM, steps, and alarms")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        // connectedDevice ONLY: dataSync cap and BOOT_COMPLETED ban would kill this monitor.
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "CGM monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing CGM session, steps, and alarm"
                setShowBadge(false)
            },
        )
        // Retired channels; deleting an absent one is a no-op.
        runCatching { nm.deleteNotificationChannel("t1dm.glance.bg") }
        runCatching { nm.deleteNotificationChannel("t1dm.aod.scan") }
    }

    @Suppress("WakelockTimeout") // Must stay awake across Doze; released in onDestroy.
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "t1dm:cgm-scan").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        private const val TAG = "CgmScan"
        private const val CH_SERVICE = "t1dm.service.cgm"
        private const val NOTIF_ID = 4100
        private const val HEARTBEAT_MS = 60_000L
        /** Re-render cadence, so the notification's "updated N ago" stays honest. */
        private const val NOTIF_TICK_MS = 30_000L
        const val KV_LAST_ALIVE = "last_alive_ts"

        /** Spelled once in core:model: archive restore and MIGRATION_10_11 must recognise it. */
        private val DEBUG_SOURCE = CgmSourceId.DEBUG

        const val ACTION_INJECT_READING = "com.t1dm.app.INJECT_READING"
        const val ACTION_FORCE_SIGNAL_LOSS = "com.t1dm.app.FORCE_SIGNAL_LOSS"
        const val ACTION_INJECT_STEPS = "com.t1dm.app.INJECT_STEPS"
        const val ACTION_RUN_CYCLE = "com.t1dm.app.RUN_CYCLE"
        const val ACTION_FORCE_DEGENERATE = "com.t1dm.app.FORCE_DEGENERATE"
        const val ACTION_FORCE_PREDICT = "com.t1dm.app.FORCE_PREDICT"
        /** Delivered by [AlertRepeatScheduler] via [com.t1dm.app.notify.AlertRepeatReceiver]. */
        const val ACTION_ALERT_REPEAT = AlertRepeatScheduler.ACTION_ALERT_REPEAT
        const val ACTION_SEED_CONTEXT = "com.t1dm.app.SEED_CONTEXT"
        const val ACTION_RUN_GRID_TICK = "com.t1dm.app.RUN_GRID_TICK"
        const val ACTION_SET_WARMUP = "com.t1dm.app.SET_WARMUP"
        const val ACTION_LOG_MEAL = "com.t1dm.app.LOG_MEAL"
        const val ACTION_LOG_BOLUS = "com.t1dm.app.LOG_BOLUS"
        const val ACTION_LOG_BASAL = "com.t1dm.app.LOG_BASAL"
        const val ACTION_LOG_MOOD = "com.t1dm.app.LOG_MOOD"
        const val ACTION_WATCH_PAIR = "com.t1dm.app.WATCH_PAIR"
        const val ACTION_WATCH_CONFIRM = "com.t1dm.app.WATCH_CONFIRM"
        const val ACTION_WATCH_ROTATE = "com.t1dm.app.WATCH_ROTATE"
        const val ACTION_WATCH_UNPAIR = "com.t1dm.app.WATCH_UNPAIR"
        const val ACTION_WATCH_PUSH = "com.t1dm.app.WATCH_PUSH"
        const val EXTRA_BG = "bg"
        const val EXTRA_DEVICE_ID = "id"
        const val EXTRA_TRESIBA = "tresiba"
        const val EXTRA_AGE_MIN = "ageMin"
        const val EXTRA_WARMUP = "warmup"
        const val EXTRA_TREND = "trend"
        const val EXTRA_CUMULATIVE = "cumulative"
        const val EXTRA_HOURS = "hours"
        const val EXTRA_GRAMS = "grams"
        const val EXTRA_GI = "gi"
        const val EXTRA_UNITS = "units"
        const val EXTRA_MOOD = "mood"
        const val EXTRA_START_BG = "startBg"
        const val EXTRA_END_BG = "endBg"

        private const val GRID_MS = 300_000L
        /** How often the forecast driver wakes to notice a flip back into TIMED. */
        private const val MODE_POLL_MS = 30_000L
        private fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        private fun missingGrants(context: Context): List<String> = missingCgmGrants {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        /** Refused without the grants: a service stopped before startForeground crashes the app. */
        fun send(context: Context, intent: Intent) {
            val missing = missingGrants(context)
            if (missing.isNotEmpty()) {
                Timber.tag(TAG).w("not started: missing %s", missing.joinToString { it.substringAfterLast('.') })
                return
            }
            context.startForegroundService(intent)
        }

        fun start(context: Context) {
            send(context, Intent(context, CgmScanService::class.java))
        }
    }
}

/** BLUETOOTH_SCAN for connectedDevice, BLUETOOTH_CONNECT for the session. */
private val CGM_PERMISSIONS = listOf(
    android.Manifest.permission.BLUETOOTH_SCAN,
    android.Manifest.permission.BLUETOOTH_CONNECT,
)

internal fun missingCgmGrants(granted: (String) -> Boolean): List<String> = CGM_PERMISSIONS.filterNot(granted)

/** Age in whole minutes, as the widget draws it; [readingWallMs] repaints each new reading. */
internal fun widgetPushSig(
    glance: BgGlance,
    readingWallMs: Long?,
    unit: UnitSpace,
    accent: Int,
    predictedTime: PredictedTime?,
    themeId: String,
    customJson: String?,
): List<Any?> = listOf(
    glance.copy(readingAgeMs = glance.readingAgeMs / 60_000L),
    readingWallMs, unit, accent, predictedTime, themeId, customJson,
)
