package com.t1dm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.t1dm.alerts.AlarmController
import com.t1dm.alerts.AlarmEngine
import com.t1dm.alerts.AndroidAlarmNotifier
import com.t1dm.app.T1dmApplication
import com.t1dm.app.di.AppContainer
import com.t1dm.cgm.BleAdvertScanner
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.InferenceCause
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.inference.SyntheticContext
import com.t1dm.sensors.RoomStepSampleWriter
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

/**
 * The always-on Phase-1 foreground service (PLAN.private.md §2.3, Phase 1). It hosts, in one place
 * and with **no `:inference` dependency**: the passive BLE scan, the step counter, the 5-min grid
 * heartbeat, and the deterministic model-free alarm path (§3.6-A). Typed
 * `connectedDevice|dataSync`, `START_STICKY`, restarted from [onTaskRemoved] + a WorkManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    /** The merged reading bus feeding the alarm engine: real active-source readings + injected. */
    private val readingBus = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = 128)

    /** Debug step folding so injected TYPE_STEP_COUNTER cumulatives bucket exactly as the sensor's. */
    private val debugBucketer = StepBucketer()

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

        alarmEngine = AlarmEngine(container.alarmConfig)
        alarmController = AlarmController(
            engine = alarmEngine,
            notifier = AndroidAlarmNotifier(this),
            config = container.alarmConfig,
        )

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
        //    posting off the main thread AND serialises the two collectors driving the engine.
        val alarmScope = CoroutineScope(
            lifecycleScope.coroutineContext + container.dispatchers.default.limitedParallelism(1),
        )
        alarmController.launchIn(alarmScope, readingBus)
        alarmScope.launch {
            alarmController.state.collect { st ->
                Timber.tag(TAG).i(
                    "ALARM active=%b threshold=%s loss=%s primarySeverity=%s",
                    st.isActive, st.threshold?.band, st.signalLoss?.windowMin, st.primary?.severity,
                )
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

        // 6) The 5-min inference GridTick — a SIBLING scope, structurally independent of the
        //    model-free alarm path above (§2.3, §3.6-A): a failed cycle never touches the alarm.
        //    Aligned to the grid; each tick fans the shared context out over the running set.
        lifecycleScope.launch {
            container.inferenceController.refreshModels()
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(GRID_MS - (now % GRID_MS)) // sleep to the next 5-min boundary
                runCatching {
                    container.inferenceController.runFromHistory(
                        cause = InferenceCause.GRID_TICK,
                        nowMs = System.currentTimeMillis(),
                    )
                }.onFailure { Timber.tag(TAG).w(it, "inference GridTick failed (alarm path unaffected)") }
                // Flush the freshly-enqueued PREDICTIONS/INGEST promptly when the tailnet is up
                // (opportunistic; the periodic drain + WorkManager are the fallbacks).
                runCatching { container.syncManager.drainNow() }
                    .onFailure { Timber.tag(TAG).w(it, "post-tick drain failed (independent of inference)") }
            }
        }

        // 7) Server sync (Phase 3): the durable-outbox drainer + WS stream + catch-up, hosted in the
        //    FGS scope. A SIBLING of the alarm + inference paths — a sync failure never touches them.
        container.syncManager.launch(lifecycleScope)
    }

    private fun startScan() {
        val scanner = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        }.getOrNull()
        if (scanner == null) {
            Timber.tag(TAG).w("No BluetoothLeScanner (adapter off / no BLE / permission); scan idle")
            return
        }
        runCatching {
            container.registry.start(BleAdvertScanner(scanner, container.dispatchers))
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
            ACTION_SET_SERVER -> configureServer(
                url = intent.getStringExtra(EXTRA_URL) ?: "http://127.0.0.1:8443",
                token = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
                label = intent.getStringExtra(EXTRA_LABEL) ?: "local",
            )
        }
        return START_STICKY
    }

    /**
     * Debug: configure + activate the server profile and flush the durable outbox — the sensor-free
     * equivalent of the Settings → Server screen (PLAN.private.md Phase 3 verify). Drives the REAL
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
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ─── Foreground plumbing ──────────────────────────────────────────────────────────────────

    private fun startForegroundNotified() {
        val notif: Notification = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("T1DM monitoring active")
            .setContentText("Scanning for CGM, counting steps, watching alarms.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIF_ID, notif, type)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "CGM monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing passive CGM scan, steps, and the model-free alarm."
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
        private const val NOTIF_ID = 4100
        private const val HEARTBEAT_MS = 60_000L
        const val KV_LAST_ALIVE = "last_alive_ts"

        private val DEBUG_SOURCE = CgmSourceId("aidexx:DEBUG")

        const val ACTION_INJECT_READING = "com.t1dm.app.INJECT_READING"
        const val ACTION_FORCE_SIGNAL_LOSS = "com.t1dm.app.FORCE_SIGNAL_LOSS"
        const val ACTION_INJECT_STEPS = "com.t1dm.app.INJECT_STEPS"
        const val ACTION_RUN_CYCLE = "com.t1dm.app.RUN_CYCLE"
        const val ACTION_FORCE_DEGENERATE = "com.t1dm.app.FORCE_DEGENERATE"
        const val ACTION_SET_SERVER = "com.t1dm.app.SET_SERVER"
        const val EXTRA_BG = "bg"
        const val EXTRA_AGE_MIN = "ageMin"
        const val EXTRA_WARMUP = "warmup"
        const val EXTRA_TREND = "trend"
        const val EXTRA_CUMULATIVE = "cumulative"
        const val EXTRA_URL = "url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_LABEL = "label"

        private const val GRID_MS = 300_000L
        private fun snapToGrid(ts: Long): Long =
            Math.floorDiv(ts + GRID_MS / 2, GRID_MS) * GRID_MS

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CgmScanService::class.java))
        }
    }
}
