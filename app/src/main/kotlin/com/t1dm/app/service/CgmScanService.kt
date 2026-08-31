package com.t1dm.app.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.t1dm.app.aod.AodScanActivity
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
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
import com.t1dm.app.notify.GlanceReadings
import com.t1dm.app.notify.AlarmActionReceiver
import com.t1dm.app.notify.AlertRepeatScheduler
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
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.design.resolvePalette
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import com.t1dm.cgm.BleAdvertScanner
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSensorModelId
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.InsulinFamily
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.curve.CurveEngine
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.toBlob
import kotlin.math.sin
import com.t1dm.inference.SyntheticContext
import com.t1dm.sensors.RoomStepSampleWriter
import com.t1dm.sensors.StepBucketer
import com.t1dm.sensors.StepRecorder
import com.t1dm.sensors.StepSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.TimeZone

/** [theme] is (themeId, customThemeJson). */
private data class GlanceInputs(
    val readings: GlanceReadings,
    val state: InferenceState,
    val unit: UnitSpace,
    val theme: Pair<String, String?>,
)

/** The always-on foreground service (§2.3): the passive BLE scan, the step counter, the 5-min
 *  heartbeat and the model-free alarm path (§3.6-A), with no `:inference` dependency. Typed
 *  `connectedDevice`, NOT `dataSync` — that carries an Android-15+ 6 h/24 h cap and a
 *  `BOOT_COMPLETED`-start ban. */
class CgmScanService : LifecycleService() {

    private lateinit var container: AppContainer
    private lateinit var alarmEngine: AlarmEngine
    private lateinit var alarmController: AlarmController
    private var alarmNotifier: AndroidAlarmNotifier? = null
    /** The single-thread slice the engine and its collectors run on; snooze/config updates are posted
     *  here so they serialise with those collectors (§2.3). Set in [startPipeline]. */
    private var alarmScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /** Touched only from `refreshGlanceSurfaces`, which the combine collects on one coroutine, so it
     *  needs no synchronization. */
    private var lastGlancePushSig: List<Any?>? = null

    /** Alarm-engine input: the active source's readings plus injected ones. */
    private val readingBus = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = 128)

    private lateinit var livePresenter: LiveNotificationPresenter
    private val predictiveAlerts by lazy {
        PredictiveAlertPresenter(this, ::fullScreenIntent, ::contentIntent)
    }
    private val repeatScheduler by lazy { AlertRepeatScheduler(this) }
    @Volatile private var repeatArmed = false

    /** Debug step folding so injected TYPE_STEP_COUNTER cumulatives bucket exactly as the sensor's. */
    private val debugBucketer = StepBucketer()

    /** Scan report delay, ms: 0 is real-time, [screenOffReportDelayMs] is offloaded batching. A new
     *  value restarts the scan in that mode (see [AidexXSourceRegistry.start]). */
    private val reportDelayFlow = MutableStateFlow(0L)
    @Volatile private var screenOffReportDelayMs = 0L

    /** On screen-off (while engaged) raise the keep-screen-on [AodScanActivity] after a grace, so the
     *  phone never deep-idles. Holding the display on does NOT rescue a reportDelay-0 scan, so under
     *  the AOD the scan stays in BATCH mode. Main looper: activity starts must run there. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reengageRunnable = Runnable { maybeEngageAod() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    reportDelayFlow.value = screenOffReportDelayMs
                    if (aggressiveEngaged()) {
                        mainHandler.removeCallbacks(reengageRunnable)
                        mainHandler.postDelayed(reengageRunnable, AOD_REENGAGE_GRACE_MS)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Do NOT arm real-time here: the phone is still on the keyguard and HyperOS
                    // suspends a reportDelay-0 scan the whole time it is locked. Locked cannot be told
                    // from unlocked at this edge either — a showWhenLocked activity OCCLUDES the
                    // keyguard and isKeyguardLocked() then reports FALSE. Only ACTION_USER_PRESENT arms.
                    mainHandler.removeCallbacks(reengageRunnable)
                    runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID_AOD) }
                }
                Intent.ACTION_USER_PRESENT -> {
                    reportDelayFlow.value = 0L
                }
            }
        }
    }

    private fun aggressiveEngaged(): Boolean =
        container.aggressiveScanSnapshot &&
            (!container.aggressiveOnlyChargingSnapshot || isCharging())

    private fun isCharging(): Boolean =
        runCatching { getSystemService(BatteryManager::class.java)?.isCharging == true }.getOrDefault(false)

    /** Reports FALSE while a showWhenLocked activity OCCLUDES the keyguard, so it is used ONLY for the
     *  initial mode at scan start; ACTION_USER_PRESENT is the trustworthy "unlocked" signal. */
    private fun isKeyguardLocked(): Boolean =
        runCatching { getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)

    /** A background `startActivity` is refused on Android 14+ with no visible window (BAL); a
     *  full-screen-intent notification launches the activity over the keyguard instead. */
    private fun maybeEngageAod() {
        if (!aggressiveEngaged()) return
        val interactive = runCatching { getSystemService(PowerManager::class.java)?.isInteractive == true }
            .getOrDefault(true)
        if (interactive) return
        val pi = PendingIntent.getActivity(
            this, 5,
            Intent(this, AodScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = Notification.Builder(this, CH_AOD)
            .setSmallIcon(NotificationIcons.res(NotificationIcons.Glyph.MONITOR, container.iconStyle))
            .setColor(container.notificationAccentArgb)
            .setContentTitle("Keeping the CGM scan alive")
            .setContentText("The screen is held dark while the phone is locked.")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(AOD_REENGAGE_GRACE_MS)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID_AOD, notif) }
            .onFailure { Timber.tag(TAG).w(it, "AOD full-screen-intent post failed") }
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as T1dmApplication).container

        createChannel()
        // Fail closed: a connectedDevice foreground service legally cannot start without
        // BLUETOOTH_SCAN. MainActivity restarts the service once the permission is granted.
        if (!hasScanPermission()) {
            Timber.w(
                "CgmScanService: BLUETOOTH_SCAN not granted — the connectedDevice foreground service " +
                    "cannot start yet; stopping. It restarts once the permission is granted."
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

    /** The connectedDevice FGS type requires a granted BT-scan permission to start (Android 14+). */
    private fun hasScanPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun startPipeline() {
        if (started) return
        started = true

        // A single-threaded `default` slice (§2.3): keeps the notifier's posting off the main thread
        // and serialises the two collectors driving the engine.
        val alarmScope = CoroutineScope(
            lifecycleScope.coroutineContext + container.dispatchers.default.limitedParallelism(1),
        )
        this.alarmScope = alarmScope
        // A fresh service instance forgets any stale snooze (§3.6 C1).
        container.clearSnooze()
        alarmScope.launch {
            container.refreshAlertActuatorConfig()
            alarmEngine = AlarmEngine(container.alarmConfig)
            val notifier = AndroidAlarmNotifier(
                context = this@CgmScanService,
                // Live: a snapshot here would freeze the choice for the life of the service.
                actuatorConfig = { container.alertActuatorSnapshot },
                fullScreenIntent = ::fullScreenIntent,
                contentIntent = ::contentIntent,
                smallIcon = { critical ->
                    NotificationIcons.icon(
                        this@CgmScanService,
                        if (critical) NotificationIcons.Glyph.ALARM else NotificationIcons.Glyph.WARNING,
                        container.iconStyle,
                    )
                },
                accentColor = { container.notificationAccentArgb },
                // DEATH: the engine keeps firing (§3.6-A), the notifier presents nothing.
                suppressed = { container.deathModeSnapshot },
                // Presentation silence only (§3.6 C1–C5), read live; the engine still fires.
                snoozeState = { container.snoozeSnapshot },
                snoozeIntent = { alarm -> alarmActionIntent(alarm, AlarmActionReceiver.ACTION_ALARM_SNOOZE) },
                dismissIntent = { alarm -> alarmActionIntent(alarm, AlarmActionReceiver.ACTION_ALARM_DISMISS) },
                snoozeMinutes = { container.snoozeMinSnapshot },
                minActuationIntervalMs = { container.alarmConfig.minActuationIntervalMin * 60_000L },
            )
            alarmNotifier = notifier
            // The over-temp alarm's input is the battery sensor's °C (§3.6-A).
            alarmController = AlarmController(
                alarmEngine, notifier, container.alarmConfig,
                temperatureC = { container.readDeviceTempC() },
            )
            alarmController.launchIn(alarmScope, readingBus)
            // A Settings save pushes the new config into the ALREADY-running engine on this same
            // slice — no restart. It never clears an active breach; the engine re-classifies on the
            // next reading.
            container.setAlarmConfigSink { cfg ->
                alarmScope.launch {
                    alarmEngine.updateConfig(cfg)
                    if (::alarmController.isInitialized) alarmController.updateConfig(cfg)
                }
            }
            alarmController.state.collect { st ->
                // §3.6 C1/C3 — drop snoozes whose kind cleared or whose window lapsed.
                container.pruneSnooze(st)
                container.alarmState.value = st
                Timber.tag(TAG).i(
                    "ALARM active=%b threshold=%s loss=%s primarySeverity=%s",
                    st.isActive, st.threshold?.band, st.signalLoss?.windowMin, st.primary?.severity,
                )
                // Edge-triggered, so the repeat timer is not pushed out on every state emit.
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

        // Only the AUTHORITATIVE source reaches the bus: the engine downstream is one state machine
        // over an undifferentiated stream (§3.6-A), so two sensors would interleave threshold
        // hysteresis and staleness. Re-collected on promotion so the bus follows authority.
        lifecycleScope.launch {
            container.registry.authoritative.collectLatest {
                val src = container.registry.authoritativeSource() ?: return@collectLatest
                src.readings().collect { readingBus.emit(it) }
            }
        }

        // A promotion is a transition between two non-null ids; hydration (null → the persisted id)
        // is not one, and `drop(1)` eats only the initial null. `isInitialized`: the alarm init
        // coroutine suspends on Room reads before assigning it and shares this dispatcher slice.
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

        startScan()

        startSteps()

        lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                runCatching { container.repository.putKv(KV_LAST_ALIVE, now.toString(), now) }
                delay(HEARTBEAT_MS)
            }
        }

        // 5-min housekeeping, structurally independent of the alarm path (§2.3, §3.6-A): a failed
        // drain, push or sweep never touches the alarm.
        lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(GRID_MS - (now % GRID_MS)) // sleep to the next 5-min boundary
                // Opportunistic; the periodic drain and WorkManager are the fallbacks.
                runCatching { container.syncManager.drainNow() }
                    .onFailure { Timber.tag(TAG).w(it, "post-tick drain failed (independent of inference)") }
                runCatching { container.pushToWatch(System.currentTimeMillis()) }
                    .onFailure { Timber.tag(TAG).w(it, "watch push failed (independent of alarm/inference)") }
                runCatching { container.repository.pruneRawSamples(System.currentTimeMillis()) }
                    .onFailure { Timber.tag(TAG).w(it, "raw sample prune failed (independent of alarm/inference)") }
            }
        }

        // The forecast driver, independent of the alarm path (§2.3, §3.6-A). ADAPTIVE fires one
        // forecast per incoming reading; TIMED on a wall-clock grid. Merged and CONFLATED so a burst
        // collapses to the latest `nowMs` rather than queueing. Drives ONLY runFromHistory — the
        // chokepoint the §3.6 gate and the warmup latch guard.
        lifecycleScope.launch(container.dispatchers.default) {
            container.inferenceController.refreshModels()
            val adaptiveTicks = readingBus
                .filter { container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_ADAPTIVE }
                .map { System.currentTimeMillis() }
            val timedTicks = flow {
                while (isActive) {
                    if (container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_TIMED) {
                        // coerceAtLeast(1): a 0-minute period would divide by zero below.
                        val periodMs = container.forecastPeriodMin().coerceAtLeast(1) * 60_000L
                        val now = System.currentTimeMillis()
                        delay((periodMs - now % periodMs).coerceAtLeast(1L)) // to the next period boundary
                        // The user may have left TIMED while we slept.
                        if (container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_TIMED) {
                            emit(System.currentTimeMillis())
                        }
                    } else {
                        delay(MODE_POLL_MS) // idle-poll until the mode flips back to TIMED
                    }
                }
            }
            merge(adaptiveTicks, timedTicks).conflate().collect { nowMs ->
                runCatching { container.inferenceController.runFromHistory(InferenceCause.GRID_TICK, nowMs) }
                    .onFailure { Timber.tag(TAG).w(it, "inference cycle failed (alarm path unaffected)") }
            }
        }

        container.syncManager.launch(lifecycleScope)

        container.watchLink.start(lifecycleScope)

        // Off-main: a LifecycleService's lifecycleScope is Main, and compute/DB/updateAll must not run
        // there. The ticker forces a re-emit so the "updated N min ago" ages between readings.
        lifecycleScope.launch(container.dispatchers.default) {
            val ticker = kotlinx.coroutines.flow.flow {
                while (isActive) { emit(Unit); delay(NOTIF_TICK_MS) }
            }
            val theme = combine(
                container.settingsStore.themeId,
                container.settingsStore.customThemeJson,
            ) { id, json -> id to json }
            combine(
                container.glanceReadings.onStart { emit(GlanceReadings.EMPTY) },
                container.inferenceState,
                container.statsRepository.unitSpace.onStart { emit(UnitSpace.MgDl) },
                ticker,
                theme,
            ) { readings, state, unit, _, themeSig ->
                GlanceInputs(readings, state, unit, themeSig)
            }.collectLatest { (readings, state, unit, themeSig) ->
                runCatching { refreshGlanceSurfaces(readings, state, unit, themeSig) }
                    .onFailure { Timber.tag(TAG).w(it, "glance refresh failed (alarm path unaffected)") }
            }
        }

        // DEATH: tear down a showing alarm and predictive alert; the ongoing monitoring surface stays.
        lifecycleScope.launch {
            container.deathMode.collect { on -> if (on) { alarmNotifier?.clear(); predictiveAlerts.clear() } }
        }
    }

    /** The §3.6 gate lives inside [BgGlanceComputer]; this only renders. The predictive alert is
     *  suppressed while a deterministic critical breach fires, so it can only ever add an EARLIER
     *  warning. */
    private suspend fun refreshGlanceSurfaces(
        readings: GlanceReadings,
        state: InferenceState,
        unit: UnitSpace,
        themeSig: Pair<String, String?>,
    ) {
        val glance: BgGlance = BgGlanceComputer.compute(
            readings = readings,
            state = state,
            thresholds = container.alarmConfig.thresholds,
            lossMin = container.alarmConfig.lossMin,
            staleMin = 15,
            nowMs = System.currentTimeMillis(),
        )
        // From the SAME (id, json) that drove this refresh, not the container's own snapshot, so a
        // theme change repaints both surfaces at once and cannot race it. ONE resolution feeds both:
        // two decodes parsed an imported theme's JSON twice on every refresh.
        val (themeId, customJson) = themeSig
        val style = iconStyleForTheme(themeId)
        val palette = resolvePalette(themeId, customJson)
        val accent = palette.primary.toArgb()
        // Push only what CHANGED: `SessionWorker` composes Glance on the MAIN thread and one widget
        // push parcels ~2 MB per instance, and unguarded this ran at the rate `cgm_reading` was
        // written. A list, not a class, so a rendered input cannot silently fall out of the
        // comparison; `nowMs` staleness lives inside `glance`, so the ticker still gets through.
        val pushSig: List<Any?> =
            listOf(glance, unit, style, accent, state.selectedPredictedTime, themeId, customJson)
        val surfacesChanged = pushSig != lastGlancePushSig
        if (surfacesChanged) lastGlancePushSig = pushSig

        val nm = getSystemService(NotificationManager::class.java)
        if (surfacesChanged) {
            runCatching {
                nm.notify(NOTIF_ID, livePresenter.build(glance, unit, style, accent, state.selectedPredictedTime))
            }
        }

        val deterministicCriticalActive = ::alarmController.isInitialized &&
            alarmController.state.value.threshold?.severity == AlarmSeverity.CRITICAL
        // Mirrored for the minigame's interlock: a predicted urgent crossing buzzes the SAME actuator
        // the deterministic alarm does, so a cosmetic surface holding an effect must yield to it.
        container.predictiveAlertRaised.value = if (container.deathModeSnapshot) {
            predictiveAlerts.clear()
            false
        } else {
            predictiveAlerts.update(
                glance, container.alertActuatorSnapshot, deterministicCriticalActive, style, accent,
            )
        }

        // Seed the palette globals BEFORE pushing, so the widget renders the persisted theme
        // headlessly rather than waiting on an Activity composition.
        if (surfacesChanged) {
            applyWidgetPalette(palette)
            runCatching { GlucoseWidget().updateAll(this) }
        }
        // Freshness blink: a fresh reading renders bright, and ONE delayed re-render settles it — two
        // updates per reading, never a loop. Age first, toggle second: the age rules the blink out
        // without the toggle's Room read.
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

    /** Distinct request codes per (action, kind), so threshold and signal buttons never collide. */
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

    /** Snooze (timed) or Dismiss (until-clear) on the live breach of the tapped KIND (§3.6 C1–C5).
     *  Reads the LIVE engine state, so the snooze records the band actually firing now and escalation
     *  still pierces (C2). */
    private fun handleAlarmAction(intent: Intent, dismiss: Boolean) {
        val kindName = intent.getStringExtra(AlarmActionReceiver.EXTRA_ALARM_KIND) ?: return
        val kind = runCatching { AlarmKind.valueOf(kindName) }.getOrNull() ?: return
        if (!::alarmController.isInitialized) return
        val st = alarmController.state.value
        val alarm: ActiveAlarm? = when (kind) {
            AlarmKind.THRESHOLD -> st.threshold
            AlarmKind.SIGNAL_LOSS -> st.signalLoss
            AlarmKind.WEAK_SIGNAL -> st.weakSignal
            AlarmKind.OVER_TEMPERATURE -> null // C5
        }
        if (alarm == null) return
        // A Dismiss on an urgent tier is a no-op — those are Snooze-only, so no forged intent can win
        // an unbounded silence. `SnoozeState.dismiss` refuses it too; this bails before the gate.
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

    private fun startScan() {
        val adapter = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull()
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.tag(TAG).w("No BluetoothLeScanner (adapter off / no BLE / permission); scan idle")
            return
        }
        // Screen ON → real-time (reportDelay 0): maximum capture sensitivity for a marginal advert.
        // Screen OFF → offloaded batching, the only mode HyperOS does not suspend while locked.
        // Batching's lower duty cycle can drop a marginal signal, so it is used ONLY screen-off.
        screenOffReportDelayMs =
            if (runCatching { adapter.isOffloadedScanBatchingSupported }.getOrDefault(false)) {
                BATCH_REPORT_DELAY_MS
            } else {
                0L
            }
        val interactive = runCatching { getSystemService(PowerManager::class.java)?.isInteractive == true }
            .getOrDefault(true)
        // Initial mode only: isKeyguardLocked() is reliable here because a normal start is not behind
        // the AOD. Once running, ACTION_USER_PRESENT re-arms real-time on every genuine unlock.
        reportDelayFlow.value = if (interactive && !isKeyguardLocked()) 0L else screenOffReportDelayMs
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Timber.tag(TAG).w(it, "screen receiver registration failed; scan mode stays fixed") }
        Timber.tag(TAG).i(
            "BLE scan adaptive: screenOn=0 screenOff=%d (batching=%b) interactive=%b",
            screenOffReportDelayMs, screenOffReportDelayMs > 0, interactive,
        )
        runCatching {
            container.registry.start(reportDelayFlow) { d -> BleAdvertScanner(scanner, container.dispatchers, d) }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to start BLE scan") }
        // (Re)started while already locked: engage now, not at the next screen-off edge.
        if (!interactive && aggressiveEngaged()) mainHandler.postDelayed(reengageRunnable, AOD_REENGAGE_GRACE_MS)
    }

    private fun startSteps() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val source = StepSource(sm)
        if (!source.isAvailable()) {
            Timber.tag(TAG).i("No TYPE_STEP_COUNTER; step recorder idle")
            return
        }
        val writer = RoomStepSampleWriter(container.database.sampleDao(), container.dispatchers)
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
                // Backdated past the loss window, so the next engine evaluation fires it.
                ageMin = container.alarmConfig.lossMin + 5,
                warmup = false,
                trendTenths = 0,
            )
            ACTION_INJECT_STEPS -> injectStepCounter(intent.getLongExtra(EXTRA_CUMULATIVE, 0L))
            // Through the REAL settings path: kv → flow → snapshot.
            ACTION_SET_AGGRESSIVE -> lifecycleScope.launch {
                container.setAggressiveScanEnabled(intent.getIntExtra("on", 1) != 0)
                if (intent.hasExtra("showBg")) container.setAggressiveShowGlucose(intent.getIntExtra("showBg", 1) != 0)
                if (intent.hasExtra("onlyCharging")) container.setAggressiveOnlyCharging(intent.getIntExtra("onlyCharging", 0) != 0)
                Timber.tag(TAG).i("SET_AGGRESSIVE on=%d", intent.getIntExtra("on", 1))
            }
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
            // Presentation only (§3.6 C1–C5) — the engine is untouched.
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
            ACTION_SET_SERVER -> configureServer(
                url = intent.getStringExtra(EXTRA_URL) ?: "http://127.0.0.1:8443",
                token = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
                label = intent.getStringExtra(EXTRA_LABEL) ?: "local",
            )
            // Pages GET /v1/series from the start and LWW-merges into `sample`.
            ACTION_RESYNC -> lifecycleScope.launch {
                val merged = container.resyncFromServer()
                Timber.tag(TAG).i("RESYNC merged=%d rows", merged)
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
            ACTION_WATCH_CONFIRM -> { container.confirmWatchSas(); Timber.tag(TAG).i("WATCH_CONFIRM") }
            ACTION_WATCH_ROTATE -> { container.rotateWatchKeys(); Timber.tag(TAG).i("WATCH_ROTATE") }
            ACTION_WATCH_UNPAIR -> { container.unpairWatch(); Timber.tag(TAG).i("WATCH_UNPAIR") }
            ACTION_WATCH_PUSH -> lifecycleScope.launch {
                container.pushToWatch(System.currentTimeMillis())
                Timber.tag(TAG).i("WATCH_PUSH state=%s", container.watchSecurity.value.phase)
            }
        }
        return START_STICKY
    }

    /** `ageMin == 0` drives the REAL [AppContainer.logCarb]; `ageMin > 0` backdates the `logged_meal`
     *  row so its appearance curve sits INSIDE the context window. */
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

    /** `ageMin == 0` drives the REAL [AppContainer.logBolus]; `ageMin > 0` backdates the `logged_dose`
     *  so its PK action pulls down inside the context window. */
    private fun logBolus(units: Double, ageMin: Int) {
        lifecycleScope.launch {
            if (ageMin <= 0) {
                // No picker here, so the write inherits the last-logged insulin.
                container.logBolus(units)
            } else {
                val now = System.currentTimeMillis()
                val ts = snapToGrid(now - ageMin * 60_000L)
                val curve = container.curveEngine.expAction(units, 75.0, 360.0)
                container.repository.logLoggedDose(
                    LoggedDoseEntity(
                        clientId = "", tsMs = ts, kind = DoseKind.BOLUS, units = units, durationMin = 360.0,
                        k = null, theta = null, kaPerHour = null, kePerHour = null,
                        customCurve = if (curve.isEmpty()) null else curve.toList().toBlob(),
                        tzOffsetMin = TimeZone.getDefault().getOffset(ts) / 60_000,
                        note = "backdated", updatedAt = now,
                    ),
                )
            }
            Timber.tag(TAG).i("LOG_BOLUS units=%.1f ageMin=%d", units, ageMin)
        }
    }

    /** The brand is matched against the catalogue's own labels, so a renamed or dropped preset
     *  degrades to the sticky last-logged basal rather than a curve invented here. */
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

    /** Bulk-seeds [hours] of MEASURED, NORMAL grid-aligned readings on the active source, so the
     *  warmup numerator and the context history have signal without a sensor. */
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

    /** Drives the REAL [com.t1dm.app.di.AppContainer.saveServerProfile] → health → drain path. */
    private fun configureServer(url: String, token: String, label: String) {
        lifecycleScope.launch {
            container.saveServerProfile(label, url, token)
            val health = container.checkServerHealth()
            Timber.tag(TAG).i("SET_SERVER url=%s label=%s health=%s", url, label, health)
            container.syncManager.drainNow()
        }
    }

    /** One full cycle on a synthetic 24 h series, through the REAL controller path. */
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

    /** An injected reading contests its grid slot like any other (`data/GridSlotSelection.kt`): a
     *  second inject into a slot already holding a nearer sample is stored as a sub-grid sample and
     *  does not change the grid row. It still reaches `readingBus`. */
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

    /** Guarantee an active source exists so injected readings project into `sample` and render. */
    private suspend fun ensureActiveSource(): CgmSourceId {
        container.repository.authoritativeSourceId()?.let { return it }
        val now = System.currentTimeMillis()
        container.repository.upsertSource(
            CgmSourceDescriptor(
                id = DEBUG_SOURCE,
                vendorId = "aidexx",
                // Its OWN model class, not the real one: injected readings appear only under it.
                sensorModelId = CgmSensorModelId.AIDEX_DEBUG,
                // Nothing advertised it, so there is no name.
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
        val restart = Intent(applicationContext, CgmScanService::class.java)
        startForegroundService(restart)
        CgmWatchdog.enqueue(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        container.serviceRunning.value = false
        // Drop the live-config seam, so a later Settings save cannot touch a dead engine.
        runCatching { container.setAlarmConfigSink(null) }
        mainHandler.removeCallbacks(reengageRunnable)
        runCatching { unregisterReceiver(screenReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun startForegroundNotified() {
        val notif: Notification = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(NotificationIcons.res(NotificationIcons.Glyph.MONITOR, container.iconStyle))
            .setColor(container.notificationAccentArgb)
            .setContentTitle("Monitoring active")
            .setContentText("Watching CGM, steps, and alarms")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "CGM monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing CGM scan, steps, and alarm"
                setShowBadge(false)
            },
        )
        // A retired channel; harmless no-op once already deleted.
        runCatching { nm.deleteNotificationChannel("t1dm.glance.bg") }
        // HIGH importance is required for a full-screen intent to launch rather than merely post;
        // kept silent, since its only job is to raise the AOD surface.
        nm.createNotificationChannel(
            NotificationChannel(CH_AOD, "Background scan wake", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Raises the dark keep-screen-on view that keeps the CGM scan alive while locked."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    @Suppress("WakelockTimeout") // Advisory monitor must stay awake across Doze; released in onDestroy.
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
        private const val CH_AOD = "t1dm.aod.scan"
        private const val NOTIF_ID = 4100
        private const val NOTIF_ID_AOD = 4104
        /** Any non-zero value engages offloaded batching; HyperOS overrides it to ~5 min while locked. */
        private const val BATCH_REPORT_DELAY_MS = 10_000L
        private const val HEARTBEAT_MS = 60_000L
        /** Long enough that an off → on double-press to check the phone cancels the AOD. */
        private const val AOD_REENGAGE_GRACE_MS = 2_500L
        /** How often the always-on notification re-renders, so its "updated N ago" stays honest. */
        private const val NOTIF_TICK_MS = 30_000L
        const val KV_LAST_ALIVE = "last_alive_ts"

        /** Spelled once in `:core:model`: the archive restore and `MIGRATION_10_11` both must
         *  recognise this id to put it in the debug model class. */
        private val DEBUG_SOURCE = CgmSourceId.DEBUG

        const val ACTION_INJECT_READING = "com.t1dm.app.INJECT_READING"
        const val ACTION_FORCE_SIGNAL_LOSS = "com.t1dm.app.FORCE_SIGNAL_LOSS"
        const val ACTION_INJECT_STEPS = "com.t1dm.app.INJECT_STEPS"
        const val ACTION_SET_AGGRESSIVE = "com.t1dm.app.SET_AGGRESSIVE"
        const val ACTION_RUN_CYCLE = "com.t1dm.app.RUN_CYCLE"
        const val ACTION_FORCE_DEGENERATE = "com.t1dm.app.FORCE_DEGENERATE"
        const val ACTION_FORCE_PREDICT = "com.t1dm.app.FORCE_PREDICT"
        /** Delivered by [AlertRepeatScheduler] via [com.t1dm.app.notify.AlertRepeatReceiver]. */
        const val ACTION_ALERT_REPEAT = AlertRepeatScheduler.ACTION_ALERT_REPEAT
        const val ACTION_SET_SERVER = "com.t1dm.app.SET_SERVER"
        const val ACTION_RESYNC = "com.t1dm.app.RESYNC"
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
        const val EXTRA_TRESIBA = "tresiba"
        const val EXTRA_AGE_MIN = "ageMin"
        const val EXTRA_WARMUP = "warmup"
        const val EXTRA_TREND = "trend"
        const val EXTRA_CUMULATIVE = "cumulative"
        const val EXTRA_URL = "url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_LABEL = "label"
        const val EXTRA_HOURS = "hours"
        const val EXTRA_GRAMS = "grams"
        const val EXTRA_GI = "gi"
        const val EXTRA_UNITS = "units"
        const val EXTRA_MOOD = "mood"
        const val EXTRA_START_BG = "startBg"
        const val EXTRA_END_BG = "endBg"

        private const val GRID_MS = 300_000L
        /** How often the TIMED forecast driver wakes to notice a flip back into TIMED mode. */
        private const val MODE_POLL_MS = 30_000L
        private fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CgmScanService::class.java))
        }
    }
}
