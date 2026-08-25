package com.t1dm.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.LocationManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.t1dm.app.MainActivity
import com.t1dm.app.T1dmApplication
import com.t1dm.app.di.AppContainer
import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.EXERCISE_MAX_BOUT_MS
import com.t1dm.core.model.ExerciseKind
import com.t1dm.feature.exercise.distanceLabel
import com.t1dm.sensors.ControllerExerciseTrackWriter
import com.t1dm.sensors.ExerciseRecorder
import com.t1dm.sensors.LocationSource
import com.t1dm.sensors.RepositoryExerciseSampleWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A `location`-typed service of its own: OR-ing the type into [CgmScanService] would make the
 * monitor refuse to start on 34+ for anyone who declines location. It joins no auto-restart path,
 * deliberately. The final flush runs on [AppContainer.appScope] to survive this service's death.
 */
class ExerciseService : LifecycleService() {

    private lateinit var container: AppContainer
    private var recorder: ExerciseRecorder? = null
    private var recordJob: Job? = null
    private var sessionId: Long? = null
    private var refused = false

    private val gate = BoutGate()

    override fun onCreate() {
        super.onCreate()
        container = (application as T1dmApplication).container
        createChannel()
        // Fail closed: a `location` foreground service cannot start without a location permission.
        val granted = LOCATION_PERMISSIONS.any {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) {
            Timber.tag(TAG).w("no location permission — the exercise track cannot start; stopping")
            container.exerciseRefusal.value = NO_PERMISSION
            refused = true
            stopSelf()
            return
        }
        container.exerciseRefusal.value = null
        startForegroundNotified()
        observeProgress()
        observeBoutLimit()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A refusal in onCreate must stop this too: a START would open a row with no recorder.
        if (refused) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START -> startBout(kindOf(intent))
            ACTION_STOP -> stopBout()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recordJob?.cancel()
        super.onDestroy()
    }

    private fun startBout(kind: ExerciseKind) {
        if (recordJob != null) return
        val generation = gate.start()
        container.exerciseRefusal.value = null
        val locationManager = getSystemService(LocationManager::class.java)
        if (locationManager == null) {
            Timber.tag(TAG).w("no LocationManager; the exercise track cannot start")
            container.exerciseRefusal.value = NO_PERMISSION
            refused = true
            stopSelf()
            return
        }
        // LAZY: `lifecycleScope` is `Main.immediate` and would run the body inline, before the
        // assignment to `recordJob` the failure path clears.
        recordJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                val session = container.exerciseController.start(kind)
                sessionId = session.id
                val rec = ExerciseRecorder(
                    session = session,
                    source = LocationSource(
                        locationManager = locationManager,
                        // Live: the grant can go Approximate to Precise mid-bout.
                        precise = { hasPreciseLocation(this@ExerciseService) },
                    ),
                    samples = RepositoryExerciseSampleWriter(
                        repository = container.repository,
                        curves = container.curveEngine,
                        // Read per write: the slider can move mid-bout.
                        carbEquivPerMin = { container.settingsStore.currentCarbEquivPerMin() },
                    ),
                    track = ControllerExerciseTrackWriter(container.exerciseController),
                    dispatchers = container.dispatchers,
                    active = container.activeExercise,
                    bodyMassKg = { container.settingsStore.currentBodyMassKg() },
                    lowPower = container.lowPowerActive,
                )
                recorder = rec
                runCatching { rec.run() }
                    .onFailure {
                        // Rethrown: swallowing it completes the job under the cancel `stopBout` awaits.
                        if (it is CancellationException) throw it
                        Timber.tag(TAG).w(it, "exercise recording stopped")
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "the exercise bout could not be opened")
                failStart(generation, e)
            }
        }
        recordJob?.start()
    }

    /**
     * No row to close; the open is what failed. [recordJob] must be cleared — `startBout`'s guard
     * reads it, so left set it refuses every later ACTION_START. Silent once superseded or stopping.
     */
    private fun failStart(generation: Int, cause: Throwable) {
        if (!gate.isCurrent(generation) || gate.stopping) return
        recordJob = null
        recorder = null
        sessionId = null
        container.activeExercise.value = null
        container.exerciseRefusal.value = startFailureText(cause)
        stopSelf()
    }

    /**
     * The job is joined, not just cancelled: [com.t1dm.sensors.ExerciseBucketer] is single-collector
     * and `finish` drives it. [stopSelf] runs last, so the process stays foreground across the flush.
     * [interrupted] marks a bout ended at [EXERCISE_MAX_BOUT_MS] rather than by the user.
     */
    private fun stopBout(interrupted: Boolean = false) {
        if (!gate.beginStop()) return
        val endMs = System.currentTimeMillis()
        val rec = recorder
        val id = sessionId
        val job = recordJob
        val generation = gate.generation
        recordJob = null
        recorder = null
        sessionId = null
        container.appScope.launch {
            job?.cancelAndJoin()
            if (rec != null && id != null) {
                val summary = runCatching { rec.finish(endMs) }
                    .onFailure { Timber.tag(TAG).w(it, "final exercise flush failed") }
                    .getOrNull()
                runCatching {
                    container.exerciseController.stop(
                        id = id,
                        endMs = endMs,
                        activeSec = summary?.activeSec ?: 0,
                        distanceM = summary?.distanceM,
                        kcal = summary?.kcal,
                        interrupted = interrupted,
                    )
                }.onFailure { Timber.tag(TAG).w(it, "closing the exercise bout failed") }
            }
            // A Start during the flush owns the service; clearing or stopping now would end that bout.
            if (gate.isCurrent(generation)) {
                container.activeExercise.value = null
                stopSelf()
            }
        }
    }

    /**
     * The backstop on a forgotten bout: nothing else here ends a recording, and GNSS at a four-second
     * cadence drains. The row it closes is marked interrupted.
     */
    private fun observeBoutLimit() {
        lifecycleScope.launch {
            container.activeExercise.collect { active ->
                if (active != null && active.elapsedMs >= EXERCISE_MAX_BOUT_MS) {
                    Timber.tag(TAG).i("bout past the %d h limit; closing it", EXERCISE_MAX_BOUT_MS / 3_600_000L)
                    stopBout(interrupted = true)
                }
            }
        }
    }

    /** Rebuilt when [progressKey] moves, not once per fix. */
    private fun observeProgress() {
        lifecycleScope.launch {
            container.activeExercise
                .distinctUntilChangedBy { it?.let(::progressKey) }
                .collect { active -> if (active != null) notify(progressText(active)) }
        }
    }

    /** The Stop action is the only way to end a bout without opening the app. */
    private fun notification(contentText: String?): Notification =
        Notification.Builder(this, CH_EXERCISE)
            .setSmallIcon(
                com.t1dm.app.notify.NotificationIcons.res(
                    com.t1dm.app.notify.NotificationIcons.Glyph.MONITOR, container.iconStyle,
                ),
            )
            .setColor(container.notificationAccentArgb)
            .setContentTitle("Exercise")
            .apply { if (contentText != null) setContentText(contentText) }
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent())
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stopIntent()).build())
            .build()

    /** Its own request code: this intent is `filterEquals` to the monitor's, and a shared code would
     *  have the two update each other. */
    private fun openIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, RQ_OPEN, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** `getForegroundService`: a plain service start from the background is refused on 26+. */
    private fun stopIntent(): PendingIntent {
        val i = Intent(this, ExerciseService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getForegroundService(
            this, RQ_STOP, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notify(contentText: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(contentText))
    }

    private fun startForegroundNotified() {
        startForeground(NOTIF_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_EXERCISE, "Exercise", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Location tracking while a bout is recording"
                setShowBadge(false)
            },
        )
    }

    private fun kindOf(intent: Intent): ExerciseKind {
        val raw = intent.getStringExtra(EXTRA_KIND) ?: return ExerciseKind.OTHER
        return runCatching { ExerciseKind.valueOf(raw) }.getOrDefault(ExerciseKind.OTHER)
    }

    companion object {
        private const val TAG = "Exercise"
        private const val CH_EXERCISE = "t1dm.service.exercise"
        private const val NOTIF_ID = 4300
        private const val RQ_OPEN = 4301
        private const val RQ_STOP = 4302

        /** Either grant gives the service its `location` type; a track needs FINE — see
         *  [hasPreciseLocation]. */
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** A coarse-only client still gets fixes, but fuzzed to roughly 2 km, which
         *  `ExerciseBucketer` refuses at its 50 m ceiling. */
        fun hasPreciseLocation(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        const val ACTION_START = "com.t1dm.app.START_EXERCISE"
        const val ACTION_STOP = "com.t1dm.app.STOP_EXERCISE"
        const val EXTRA_KIND = "kind"

        const val NO_PERMISSION = "Location denied — no track"

        fun start(context: Context, kind: ExerciseKind) {
            val i = Intent(context, ExerciseService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_KIND, kind.name)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ExerciseService::class.java).apply { action = ACTION_STOP }
            context.startForegroundService(i)
        }
    }
}

/**
 * Which bout [ExerciseService] is on, and whether that bout's close-out has begun. [generation] is
 * written on the main thread and read from [AppContainer.appScope]; [stopping] is main-thread only.
 */
internal class BoutGate {

    @Volatile
    var generation: Int = 0
        private set

    var stopping: Boolean = false
        private set

    fun start(): Int {
        stopping = false
        return ++generation
    }

    fun beginStop(): Boolean {
        if (stopping) return false
        stopping = true
        return true
    }

    fun isCurrent(generation: Int): Boolean = generation == this.generation
}

internal fun progressText(active: ActiveExercise): String {
    val minutes = "${active.elapsedMs / 60_000L} min"
    val distance = distanceLabel(active.distanceM) ?: return minutes
    return "$minutes · $distance"
}

/**
 * Coarser than what [progressText] renders: keying the rebuild on the metres would be roughly 75
 * `notify` calls per five-minute bucket.
 */
internal fun progressKey(active: ActiveExercise): String =
    "${active.session.id}|${active.elapsedMs / 60_000L}|${distanceLabel(active.distanceM) != null}"

internal fun startFailureText(cause: Throwable): String =
    "Didn't start — ${cause.message ?: cause::class.simpleName}"
