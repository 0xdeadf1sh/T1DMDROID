package com.t1dm.app.service

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
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.t1dm.alerts.AlarmController
import com.t1dm.alerts.AlarmEngine
import com.t1dm.alerts.AlarmSeverity
import com.t1dm.alerts.AlertActuatorConfig
import com.t1dm.alerts.AndroidAlarmNotifier
import com.t1dm.app.MainActivity
import com.t1dm.app.T1dmApplication
import com.t1dm.app.di.AppContainer
import com.t1dm.app.notify.AlertRepeatScheduler
import com.t1dm.app.notify.BgGlance
import com.t1dm.app.notify.BgGlanceComputer
import com.t1dm.app.notify.LiveNotificationPresenter
import com.t1dm.app.notify.NotificationIcons
import com.t1dm.app.notify.PredictiveAlertPresenter
import com.t1dm.app.settings.SettingsStore
import androidx.glance.appwidget.updateAll
import com.t1dm.app.widget.GlucoseWidget
import com.t1dm.core.design.applyWidgetPalette
import com.t1dm.core.design.iconStyleForTheme
import com.t1dm.core.design.resolvePalette
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import com.t1dm.cgm.BleAdvertScanner
import com.t1dm.core.model.BackendComparison
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.BasalPreset
import com.t1dm.core.model.BolusPreset
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.InferenceCause
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

/** The four live inputs the glance refresh combines: the latest reading, inference state, unit space,
 *  and the active theme as an (id, customJson) pair (folded in so a theme change repaints at once). */
private data class GlanceInputs(
    val latest: CgmReading?,
    val state: InferenceState,
    val unit: UnitSpace,
    val theme: Pair<String, String?>,
)

/**
 * The always-on Phase-1 foreground service (SPEC.private.md §2.3, Phase 1). It hosts, in one place
 * and with **no `:inference` dependency**: the passive BLE scan, the step counter, the 5-min grid
 * heartbeat, and the deterministic model-free alarm path (§3.6-A). Typed **`connectedDevice`**
 * (NOT `dataSync` — that carries an Android-15+ 6 h/24 h cap and a `BOOT_COMPLETED`-start ban that
 * would break a 24/7 monitor), `START_STICKY`, restarted from [onTaskRemoved] + a WorkManager
 * watchdog + a `BOOT_COMPLETED` receiver, holding a partial wake-lock across its lifetime and
 * writing a `kv.last_alive_ts` heartbeat.
 *
 * Debug intents (sensor-free verification of the exit criteria) inject spoofed readings and step
 * deltas; they exercise the very same alarm engine, Room projection, and graph feed the real sensor
 * would, so an urgent-low alarm, a loss-of-signal alarm, a graph render, and step accumulation are
 * all provable without a live CGM.
 */
class CgmScanService : LifecycleService() {

    private lateinit var container: AppContainer
    private lateinit var alarmEngine: AlarmEngine
    private lateinit var alarmController: AlarmController
    private var alarmNotifier: AndroidAlarmNotifier? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /** The merged reading bus feeding the alarm engine: real active-source readings + injected. */
    private val readingBus = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = 128)

    // ── Phase-7B glanceable surfaces (all additive; never gate the deterministic alarm) ──────────
    private lateinit var livePresenter: LiveNotificationPresenter
    private val predictiveAlerts by lazy {
        PredictiveAlertPresenter(this, ::fullScreenIntent, ::contentIntent)
    }
    private val repeatScheduler by lazy { AlertRepeatScheduler(this) }
    @Volatile private var alertActuatorCfg = AlertActuatorConfig.SILENT
    @Volatile private var repeatArmed = false

    /** Debug step folding so injected TYPE_STEP_COUNTER cumulatives bucket exactly as the sensor's. */
    private val debugBucketer = StepBucketer()

    /** Adaptive scan report-delay: 0 (real-time, maximum capture sensitivity) while the screen is on;
     *  [screenOffReportDelayMs] (offloaded batching) while it is off so HyperOS doesn't suspend the scan.
     *  Emitting a new value restarts the scan in that mode (see [AidexXSourceRegistry.start]). */
    private val reportDelayFlow = MutableStateFlow(0L)
    @Volatile private var screenOffReportDelayMs = 0L
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reportDelayFlow.value = if (intent.action == Intent.ACTION_SCREEN_OFF) screenOffReportDelayMs else 0L
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as T1dmApplication).container

        createChannel()
        // Fail closed on a cold start before permissions are granted (a boot-time or watchdog
        // restart, or a grant/launch race): the connectedDevice foreground service legally cannot
        // start without BLUETOOTH_SCAN, so bail gracefully rather than crash with a
        // SecurityException. MainActivity restarts the service once the user grants the permission.
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

        // 1) Deterministic alarm path — the reading bus + a wall-clock ticker drive the engine.
        //    Confined to a single-threaded `default` slice (§2.3): keeps the notifier's Room-free
        //    posting off the main thread AND serialises the two collectors driving the engine. The
        //    actuator config (per-band sound + K90 vibration) + the full-screen/content intents are
        //    read once here and handed to the notifier — the alert-polish wiring is ADDITIVE and can
        //    never change WHEN the engine fires (§3.6-A), only how it is announced.
        val alarmScope = CoroutineScope(
            lifecycleScope.coroutineContext + container.dispatchers.default.limitedParallelism(1),
        )
        alarmScope.launch {
            val cfg = runCatching { container.alertActuatorConfig() }.getOrDefault(AlertActuatorConfig.SILENT)
            alertActuatorCfg = cfg
            alarmEngine = AlarmEngine(container.alarmConfig)
            val notifier = AndroidAlarmNotifier(
                context = this@CgmScanService,
                actuatorConfig = cfg,
                fullScreenIntent = ::fullScreenIntent,
                contentIntent = ::contentIntent,
                // Per-theme alarm glyph (issue I1): urgent tiers get the DISTINCT bell (ALARM); the
                // plain tier the warning triangle. Geometry follows the active theme snapshot.
                smallIcon = { critical ->
                    NotificationIcons.icon(
                        this@CgmScanService,
                        if (critical) NotificationIcons.Glyph.ALARM else NotificationIcons.Glyph.WARNING,
                        container.iconStyle,
                    )
                },
                accentColor = { container.notificationAccentArgb },
                // DEATH mode: the pure engine keeps firing (§3.6-A), the notifier presents nothing.
                suppressed = { container.deathModeSnapshot },
                // Rate-limit sound+vibration to at most once per configured interval while an alarm
                // holds the same band, read live so a Settings change applies without a rebuild.
                minActuationIntervalMs = { container.alarmConfig.minActuationIntervalMin * 60_000L },
            )
            alarmNotifier = notifier
            // F7: feed the battery-sensor °C into the engine so the deterministic over-temp alarm
            // (§3.6-A) latches/clears with hysteresis; read live so a Settings change applies at once.
            alarmController = AlarmController(
                alarmEngine, notifier, container.alarmConfig,
                temperatureC = { container.readDeviceTempC() },
            )
            alarmController.launchIn(alarmScope, readingBus)
            alarmController.state.collect { st ->
                Timber.tag(TAG).i(
                    "ALARM active=%b threshold=%s loss=%s primarySeverity=%s",
                    st.isActive, st.threshold?.band, st.signalLoss?.windowMin, st.primary?.severity,
                )
                // Exact-alarm repeat cadence for a persisting CRITICAL alarm (edge-triggered so the
                // timer is not pushed out on every state emit). Doze-resilient backstop to the
                // in-process tick; both only RE-announce an already-active alarm.
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

        // 2) Route the active source's readings onto the bus (they are persisted by the source).
        lifecycleScope.launch {
            container.registry.active.collectLatest {
                val src = container.registry.activeSource() ?: return@collectLatest
                src.readings().collect { readingBus.emit(it) }
            }
        }

        // 3) The shared passive BLE scan; the registry adopts/routes recognized adverts.
        startScan()

        // 4) Step counter → sample.steps.
        startSteps()

        // 5) kv heartbeat / grid liveness (§2.3 — the FGS owns the 5-min tick).
        lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                runCatching { container.repository.putKv(KV_LAST_ALIVE, now.toString(), now) }
                delay(HEARTBEAT_MS)
            }
        }

        // 6a) 5-min housekeeping — the sync drain + watch push, kept on the exact grid boundary they
        //     have always ridden. A SIBLING scope, structurally independent of the model-free alarm
        //     path above (§2.3, §3.6-A): a failed drain or push never touches the alarm. Inference no
        //     longer rides this loop — it has moved to its own forecast-cadence driver (6b) — but the
        //     sync+watch cadence is deliberately UNCHANGED (still one drain + one push per 5-min tick).
        lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(GRID_MS - (now % GRID_MS)) // sleep to the next 5-min boundary
                // Flush the enqueued PREDICTIONS/INGEST promptly when the tailnet is up
                // (opportunistic; the periodic drain + WorkManager are the fallbacks).
                runCatching { container.syncManager.drainNow() }
                    .onFailure { Timber.tag(TAG).w(it, "post-tick drain failed (independent of inference)") }
                // Watch push (Phase 5): seal + write one glance to the ESP32-C3 on the same 5-min
                // boundary. A SIBLING of inference/sync — a watch failure never touches the alarm
                // path, and it self-suspends in low-power mode. Off-main inside pushNow.
                runCatching { container.pushToWatch(System.currentTimeMillis()) }
                    .onFailure { Timber.tag(TAG).w(it, "watch push failed (independent of alarm/inference)") }
            }
        }

        // 6b) The inference forecast-cadence driver — a SINGLE consumer, structurally independent of
        //     the model-free alarm path (§2.3, §3.6-A): a failed cycle never touches the alarm.
        //     ADAPTIVE (the default) fires one forecast per incoming reading — the model tracks the
        //     sensor's own irregular cadence; TIMED fires on a wall-clock grid aligned to the
        //     user-configured period. The two tick sources are merged and CONFLATED so a burst can
        //     only ever collapse to the latest `nowMs`, never queue a backlog. The mode is read live
        //     off the snapshot every emit, so a Settings flip takes effect without a service restart.
        //     CRITICAL: this drives ONLY runFromHistory (never runCycle/publish) — the history-fed
        //     chokepoint that the §3.6 gate and warmup latch already guard.
        lifecycleScope.launch(container.dispatchers.default) {
            container.inferenceController.refreshModels()
            val adaptiveTicks = readingBus
                .filter { container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_ADAPTIVE }
                .map { System.currentTimeMillis() }
            val timedTicks = flow {
                while (isActive) {
                    if (container.forecastModeSnapshot == SettingsStore.FORECAST_MODE_TIMED) {
                        val periodMs = container.forecastPeriodMin() * 60_000L
                        val now = System.currentTimeMillis()
                        delay((periodMs - now % periodMs).coerceAtLeast(1L)) // to the next period boundary
                        // Re-check after the sleep: the user may have left TIMED while we waited.
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

        // 7) Server sync (Phase 3): the durable-outbox drainer + WS stream + catch-up, hosted in the
        //    FGS scope. A SIBLING of the alarm + inference paths — a sync failure never touches them.
        container.syncManager.launch(lifecycleScope)

        // 8) Watch link (Phase 5): resume an existing pairing + reconnect/backoff, hosted in the FGS
        //    scope. Dormant until the user pairs; the 5-min push above drives the glance cadence.
        container.watchLink.start(lifecycleScope)

        // 9) Phase-7B glanceable surfaces: the always-on BG notification (item 15), the §3.6-gated
        //    predictive urgent alert (item 2), and the home/lock widgets. All fed by the ONE shared
        //    BgGlanceComputer so notification, widgets, and watch agree. Off-main (default) — the
        //    lifecycleScope of a LifecycleService is Main, so compute/DB/updateAll must not run there.
        //    A ticker forces re-emit so the "updated N min ago" ages between readings.
        lifecycleScope.launch(container.dispatchers.default) {
            val ticker = kotlinx.coroutines.flow.flow {
                while (isActive) { emit(Unit); delay(NOTIF_TICK_MS) }
            }
            val theme = combine(
                container.settingsStore.themeId,
                container.settingsStore.customThemeJson,
            ) { id, json -> id to json }
            combine(
                container.latestReading.onStart { emit(null) },
                container.inferenceState,
                container.statsRepository.unitSpace.onStart { emit(UnitSpace.MgDl) },
                ticker,
                theme,
            ) { latest, state, unit, _, themeSig ->
                GlanceInputs(latest, state, unit, themeSig)
            }.collectLatest { (latest, state, unit, themeSig) ->
                runCatching { refreshGlanceSurfaces(latest, state, unit, themeSig) }
                    .onFailure { Timber.tag(TAG).w(it, "glance refresh failed (alarm path unaffected)") }
            }
        }

        // 10) DEATH mode: when the flag flips on, tear down any showing active alarm + predictive
        //     alert immediately (the snapshot gate already suppresses future emissions). The ongoing
        //     LiveNotificationPresenter monitoring surface is deliberately left intact.
        lifecycleScope.launch {
            container.deathMode.collect { on -> if (on) { alarmNotifier?.clear(); predictiveAlerts.clear() } }
        }
    }

    /**
     * Recompute the shared glance and push it to the three additive surfaces: the ongoing FGS
     * notification (updated in place on the same id), the model-PREDICTIVE urgent alert (suppressed
     * while a deterministic critical breach is already firing, so it can only ever add an EARLIER
     * warning), and the widgets. The §3.6 gate lives inside [BgGlanceComputer]; here we only render.
     */
    private suspend fun refreshGlanceSurfaces(
        latest: CgmReading?,
        state: InferenceState,
        unit: UnitSpace,
        themeSig: Pair<String, String?>,
    ) {
        val glance: BgGlance = BgGlanceComputer.compute(
            latest = latest,
            state = state,
            thresholds = container.alarmConfig.thresholds,
            lossMin = container.alarmConfig.lossMin,
            staleMin = 15,
            nowMs = System.currentTimeMillis(),
        )
        // Derive the notification geometry/accent AND the widget palette from the SAME (id, json) that
        // drove this refresh — not the container's independently-collected snapshot — so a theme change
        // repaints both surfaces at once and can never race the snapshot.
        val (themeId, customJson) = themeSig
        val style = iconStyleForTheme(themeId)
        val accent = NotificationIcons.accentArgb(themeId, customJson)
        val nm = getSystemService(NotificationManager::class.java)
        runCatching {
            nm.notify(NOTIF_ID, livePresenter.build(glance, unit, style, accent, state.selectedPredictedTime))
        }

        val deterministicCriticalActive = ::alarmController.isInitialized &&
            alarmController.state.value.threshold?.severity == AlarmSeverity.CRITICAL
        if (container.deathModeSnapshot) predictiveAlerts.clear()
        else predictiveAlerts.update(glance, alertActuatorCfg, deterministicCriticalActive, style, accent)

        // Seed the palette globals the widget reads headlessly BEFORE pushing it, so it renders the
        // persisted theme deterministically (independent of whether an Activity composition has run) and
        // updates the instant the theme changes rather than on the next reading/ticker.
        applyWidgetPalette(resolvePalette(themeId, customJson))
        runCatching { GlucoseWidget().updateAll(this) }
        // Freshness blink (tasteful, free motion): a just-arrived reading renders the accent bright;
        // settle it a beat later with ONE delayed re-render so a new value reads as a brief pulse rather
        // than a static jump. Gated by the global animations toggle and fired only for a fresh reading —
        // two updates per reading, never a loop.
        val animate = runCatching { container.settingsStore.animationsEnabled.first() }.getOrDefault(true)
        val ageMs = latest?.let { System.currentTimeMillis() - it.rxWallMs } ?: Long.MAX_VALUE
        if (animate && ageMs in 0 until GlucoseWidget.FRESH_WINDOW_MS) {
            lifecycleScope.launch(container.dispatchers.default) {
                delay(GlucoseWidget.FRESH_WINDOW_MS - ageMs + 150L)
                runCatching { GlucoseWidget().updateAll(this@CgmScanService) }
            }
        }
    }

    /** Content tap → open the app. */
    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Full-screen intent for urgent alarms — launches the app over the lock screen (item 2). */
    private fun fullScreenIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
        // ADAPTIVE scan mode. Screen ON → real-time (reportDelay 0, continuous LOW_LATENCY): maximum
        // capture sensitivity for a weak/marginal advert, which is what the user needs while looking at
        // the app. Screen OFF → offloaded batching: the only mode HyperOS doesn't suspend when locked
        // (the controller buffers and flushes ~every 5 min). Batching's lower duty cycle can drop a
        // marginal signal, so it is used ONLY while the screen is off. Where the controller can't batch,
        // stay real-time throughout (best effort). The receiver flips the mode; the registry restarts it.
        screenOffReportDelayMs =
            if (runCatching { adapter.isOffloadedScanBatchingSupported }.getOrDefault(false)) {
                BATCH_REPORT_DELAY_MS
            } else {
                0L
            }
        val interactive = runCatching { getSystemService(PowerManager::class.java)?.isInteractive == true }
            .getOrDefault(true)
        reportDelayFlow.value = if (interactive) 0L else screenOffReportDelayMs
        runCatching {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
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
            // Debug: drive the POSITIVE predictive path HyperOS blocked in 7A — inject a fresh
            // MEASURED anchor + publish an eligible forecast ramping to a target, so the notification's
            // "approaching …" line + the full-screen predictive-urgent alert reach their fired state.
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
            // Exact-alarm repeat tick: re-announce a still-active CRITICAL alarm and re-arm.
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
            // Issue-5 verify: drive the REST catch-up (download) direction — the reset→re-add-profile→
            // RE-DOWNLOAD round-trip. Pages GET /v1/series from the start and LWW-merges into `sample`.
            ACTION_RESYNC -> lifecycleScope.launch {
                val merged = container.resyncFromServer()
                Timber.tag(TAG).i("RESYNC merged=%d rows", merged)
            }
            // ── Phase-4 verification hooks (drive the REAL container writers / gate paths) ──
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
            // ── Issue-20 Vulkan verification hooks (drive the REAL backend switcher / probe) ──
            ACTION_SET_BACKEND -> lifecycleScope.launch {
                val name = intent.getStringExtra(EXTRA_BACKEND).orEmpty()
                val pref = name.takeIf { it.isNotBlank() }?.let { runCatching { BackendId.valueOf(it) }.getOrNull() }
                val modelId = container.inferenceController.state.value.running.firstOrNull { it.selected }?.modelId
                    ?: container.inferenceController.state.value.running.firstOrNull()?.modelId
                if (modelId == null) {
                    Timber.tag(TAG).w("SET_BACKEND ignored — no running model to target")
                } else {
                    val active = container.setForecastBackend(modelId, pref)
                    Timber.tag(TAG).i("SET_BACKEND model=%s requested=%s active=%s", modelId, pref, active)
                }
            }
            ACTION_BACKEND_COMPARE -> lifecycleScope.launch {
                container.inferenceController.refreshModels()
                val cmp = container.runBackendComparison(intent.getIntExtra(EXTRA_RUNS, 20))
                writeBackendReport(cmp)
            }
            ACTION_DUMP_HEADRAW -> lifecycleScope.launch {
                val name = intent.getStringExtra(EXTRA_BACKEND) ?: BackendId.EXECUTORCH_XNNPACK_FP32.name
                val bid = runCatching { BackendId.valueOf(name) }.getOrNull() ?: BackendId.EXECUTORCH_XNNPACK_FP32
                container.inferenceController.refreshModels()
                val head = container.inferenceController.debugHeadRaw(bid)
                dumpHeadRaw(bid, head)
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
            ACTION_LOG_NOTE -> lifecycleScope.launch {
                container.saveNote(intent.getStringExtra(EXTRA_TEXT) ?: "verify note")
            }
            // ── Phase-5 watch verification hooks (drive the REAL WatchLink against the emulator) ──
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

    /**
     * Debug: log a meal. `ageMin == 0` drives the REAL [AppContainer.logCarb] (curve reconstruction
     * + carbs series enqueue), matching a Meals-screen tap. `ageMin > 0` backdates the `logged_meal`
     * row so its appearance (Ra) curve sits INSIDE the context window — used to make the dashboard
     * forecast reshape visibly for the conditioning check (§3.3).
     */
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
                        tsMs = ts, grams = grams, gi = gi, k = k, theta = theta,
                        durationMin = dur, customCurve = null,
                        tzOffsetMin = TimeZone.getDefault().getOffset(ts) / 60_000,
                        note = "backdated", updatedAt = now,
                    ),
                )
            }
            Timber.tag(TAG).i("LOG_MEAL grams=%.0f gi=%.0f ageMin=%d", grams, gi, ageMin)
        }
    }

    /**
     * Debug: log a bolus. `ageMin == 0` drives the REAL [AppContainer.logBolus] (enqueue included);
     * `ageMin > 0` backdates the `logged_dose` so its PK action pulls down inside the context window.
     */
    private fun logBolus(units: Double, ageMin: Int) {
        lifecycleScope.launch {
            if (ageMin <= 0) {
                container.logBolus(units, BolusPreset.NOVORAPID)
            } else {
                val now = System.currentTimeMillis()
                val ts = snapToGrid(now - ageMin * 60_000L)
                val curve = container.curveEngine.expAction(units, 75.0, 360.0)
                container.repository.logLoggedDose(
                    LoggedDoseEntity(
                        tsMs = ts, kind = DoseKind.BOLUS, units = units, durationMin = 360.0,
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

    /**
     * Debug: log a discrete long-acting basal injection (issue 18 verify), driving the REAL
     * [AppContainer.logBasal] so any selected clinical basal preset (issue 19) applies too.
     */
    private fun logBasalDebug(units: Double, tresiba: Boolean) {
        lifecycleScope.launch {
            container.logBasal(units, if (tresiba) BasalPreset.TRESIBA else BasalPreset.LANTUS)
            Timber.tag(TAG).i("LOG_BASAL units=%.1f tresiba=%b", units, tresiba)
        }
    }

    /**
     * Debug: bulk-seed [hours] of MEASURED, NORMAL grid-aligned readings on the active source so the
     * WARMUP numerator ([RoomBgHistoryProvider.measuredStepsInWindow]) and the context history both
     * have real signal to work with (sensor-free). Deterministic diurnal wave in a sane band.
     */
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

    /**
     * Debug: configure + activate the server profile and flush the durable outbox — the sensor-free
     * equivalent of the Settings → Server screen (Phase 3 verify). Drives the REAL
     * [com.t1dm.app.di.AppContainer.saveServerProfile] → health → drain path, not a bypass.
     */
    private fun configureServer(url: String, token: String, label: String) {
        lifecycleScope.launch {
            container.saveServerProfile(label, url, token)
            val health = container.checkServerHealth()
            Timber.tag(TAG).i("SET_SERVER url=%s label=%s health=%s", url, label, health)
            container.syncManager.drainNow()
        }
    }

    /**
     * Debug: run one full inference cycle on a synthetic 24 h BG series (cold-start verification —
     * the sensor is not needed). Exercises build_context → backend → assemble_decode → degeneracy
     * guard → overlay/panels end-to-end, driving the REAL controller path (not a bypass).
     */
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

    // ─── Debug injection (sensor-free exit-criteria verification) ─────────────────────────────

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
        container.repository.activeSourceId()?.let { return it }
        val now = System.currentTimeMillis()
        container.repository.upsertSource(
            CgmSourceDescriptor(
                id = DEBUG_SOURCE,
                vendorId = "aidexx",
                displayName = "AiDEX X DEBUG",
                serialSuffix = "DEBUG",
                warmupWindowMin = 60,
                passiveOnly = true,
            ),
            active = true,
            nowMs = now,
        )
        return DEBUG_SOURCE
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Re-arm ourselves: START_STICKY covers process death, this covers a swipe-away.
        val restart = Intent(applicationContext, CgmScanService::class.java)
        startForegroundService(restart)
        CgmWatchdog.enqueue(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        container.serviceRunning.value = false
        runCatching { unregisterReceiver(screenReceiver) }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ─── Foreground plumbing ──────────────────────────────────────────────────────────────────

    private fun startForegroundNotified() {
        val notif: Notification = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(NotificationIcons.res(NotificationIcons.Glyph.MONITOR, container.iconStyle))
            .setColor(container.notificationAccentArgb)
            .setContentTitle("T1DM monitoring active")
            .setContentText("Scanning for CGM, counting steps, watching alarms.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        // connectedDevice ONLY — see the manifest note: dataSync's 6 h/24 h cap + BOOT_COMPLETED ban
        // (Android 15+) would kill an always-on monitor. The BLE scan is the standing justification.
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "CGM monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing passive CGM scan, steps, and the model-free alarm."
                setShowBadge(false)
            },
        )
        // Retire the short-lived separate lock-screen glance channel: the single ongoing notification
        // now surfaces on the keyguard on its own once "show silent notifications on lock screen" is on,
        // so the second channel is redundant. Harmless no-op once already deleted.
        runCatching { nm.deleteNotificationChannel("t1dm.glance.bg") }
    }

    @Suppress("WakelockTimeout") // Advisory monitor must stay awake across Doze; released in onDestroy.
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "t1dm:cgm-scan").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Debug (issue 20): write the GPU-vs-CPU comparison to filesDir/backend_report.txt for adb read. */
    private fun writeBackendReport(cmp: BackendComparison?) {
        val f = java.io.File(filesDir, "backend_report.txt")
        val text = if (cmp == null) {
            "backend comparison: UNAVAILABLE (no non-authoritative backend loaded to compare)\n"
        } else buildString {
            appendLine("backend comparison (${cmp.backend} vs ${cmp.authority}), runs=${cmp.runs}")
            appendLine("warm median ms:  backend=%.3f  authority=%.3f".format(cmp.warmMedianMsBackend, cmp.warmMedianMsAuthority))
            appendLine("cold ms:         backend=%.3f  authority=%.3f".format(cmp.coldMsBackend, cmp.coldMsAuthority))
            appendLine("max|Δ| head_raw (risk):   %.6e".format(cmp.maxAbsHeadRawDelta))
            appendLine("max|Δ| decoded mg/dL:     %.6f  (tol %.2f)".format(cmp.maxAbsDecodedMgdlDelta, cmp.toleranceMgdl))
            appendLine("AGREEMENT: ${if (cmp.agreementOk) "PASS" else "FAIL"}")
            appendLine("load RSS growth KB: ${cmp.loadRssGrowthKb ?: "n/a"}")
        }
        runCatching { f.writeText(text) }
        Timber.tag(TAG).i("BACKEND_COMPARE →\n%s", text)
    }

    /** Debug (issue 20): dump one backend's head_raw (168 floats) to filesDir for a byte-exact
     *  stock-vs-custom-AAR CPU-unchanged diff. */
    private fun dumpHeadRaw(bid: BackendId, head: FloatArray?) {
        val f = java.io.File(filesDir, "headraw_$bid.txt")
        val text = if (head == null) "head_raw UNAVAILABLE for $bid (variant not loaded)\n"
        else buildString {
            appendLine("head_raw $bid n=${head.size} bitsum=${head.fold(0L) { a, v -> a + java.lang.Float.floatToRawIntBits(v) }}")
            head.forEach { appendLine("%.9e".format(it)) }
        }
        runCatching { f.writeText(text) }
        Timber.tag(TAG).i("DUMP_HEADRAW %s size=%s", bid, head?.size)
    }

    companion object {
        private const val TAG = "CgmScan"
        private const val CH_SERVICE = "t1dm.service.cgm"
        private const val NOTIF_ID = 4100
        /** Batch-scan flush cadence when awake; HyperOS overrides this to ~5 min while locked. Any
         *  non-zero value engages offloaded batching, which is what survives screen-off. */
        private const val BATCH_REPORT_DELAY_MS = 10_000L
        private const val HEARTBEAT_MS = 60_000L
        /** How often the always-on notification re-renders so its "updated N ago" age stays honest. */
        private const val NOTIF_TICK_MS = 30_000L
        const val KV_LAST_ALIVE = "last_alive_ts"

        private val DEBUG_SOURCE = CgmSourceId("aidexx:DEBUG")

        const val ACTION_INJECT_READING = "com.t1dm.app.INJECT_READING"
        const val ACTION_FORCE_SIGNAL_LOSS = "com.t1dm.app.FORCE_SIGNAL_LOSS"
        const val ACTION_INJECT_STEPS = "com.t1dm.app.INJECT_STEPS"
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
        const val ACTION_LOG_NOTE = "com.t1dm.app.LOG_NOTE"
        const val ACTION_WATCH_PAIR = "com.t1dm.app.WATCH_PAIR"
        const val ACTION_WATCH_CONFIRM = "com.t1dm.app.WATCH_CONFIRM"
        const val ACTION_WATCH_ROTATE = "com.t1dm.app.WATCH_ROTATE"
        const val ACTION_WATCH_UNPAIR = "com.t1dm.app.WATCH_UNPAIR"
        const val ACTION_WATCH_PUSH = "com.t1dm.app.WATCH_PUSH"
        const val ACTION_SET_BACKEND = "com.t1dm.app.SET_BACKEND"
        const val ACTION_BACKEND_COMPARE = "com.t1dm.app.BACKEND_COMPARE"
        const val ACTION_DUMP_HEADRAW = "com.t1dm.app.DUMP_HEADRAW"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_RUNS = "runs"
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
        const val EXTRA_TEXT = "text"
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
