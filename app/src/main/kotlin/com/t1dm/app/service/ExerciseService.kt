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
 * The foreground service that holds a logged exercise bout open while the phone records its track.
 *
 * A `location`-typed service of its own, never a type OR-ed into [CgmScanService]: on 34+
 * `startForeground` throws when a declared type's permissions are not held, so folding `location`
 * into the monitor would make the one service that must never fail refuse to start for anyone who
 * declines location. [DoseCalcService] is the standing proof that a second, short-lived foreground
 * service coexists with the monitor.
 *
 * **It joins none of the four auto-restart paths** — [START_NOT_STICKY], no `onTaskRemoved` re-arm,
 * no `CgmWatchdog` enqueue, absent from [BootReceiver]. A bout killed with the process is closed at
 * the last thing actually recorded and marked interrupted on next launch, because a location service
 * restarted from the background would silently resume recording a session the user believes ended.
 *
 * **A bout is bounded and stoppable from the shade.** Nothing else here ends a recording: the
 * recorder deliberately keeps going through a degraded or absent signal. So the notification carries
 * a Stop action of its own, and a bout that reaches [EXERCISE_MAX_BOUT_MS] is closed as interrupted —
 * the two together are what stop a forgotten bout holding the receiver open indefinitely.
 *
 * **No wakelock.** `t1dm:cgm-scan` is already held PARTIAL and un-timed for the whole life of the
 * monitor, so the CPU is awake; a second lock buys nothing and is the shape of the drain regression
 * that work removed.
 *
 * The bout ROW is opened and closed here, beside the recorder that fills it, so a row cannot exist
 * with nothing recording into it. The final flush runs on [AppContainer.appScope] rather than this
 * service's own scope: it has to survive both the cancellation that ended the recording and this
 * service's death.
 */
class ExerciseService : LifecycleService() {

    private lateinit var container: AppContainer
    private var recorder: ExerciseRecorder? = null
    private var recordJob: Job? = null
    private var sessionId: Long? = null
    private var refused = false

    /** Which bout this service is on and whether its close-out has begun — see [BoutGate]. */
    private val gate = BoutGate()

    override fun onCreate() {
        super.onCreate()
        container = (application as T1dmApplication).container
        createChannel()
        // Fail closed before anything starts: a `location` foreground service legally cannot start
        // without a location permission, and a bout that runs with neither is a walk that records
        // nothing and looks like one that went nowhere. The panel requests them at Start.
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
        // onCreate runs before the first onStartCommand, so a refusal there must stop this one too —
        // otherwise a START would open a bout row with no recorder behind it.
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
        // A retry supersedes whatever refused the last attempt; left standing, the panel would go on
        // explaining a bout that is now running.
        container.exerciseRefusal.value = null
        val locationManager = getSystemService(LocationManager::class.java)
        if (locationManager == null) {
            Timber.tag(TAG).w("no LocationManager; the exercise track cannot start")
            container.exerciseRefusal.value = NO_PERMISSION
            refused = true
            stopSelf()
            return
        }
        // LAZY so the body cannot run before the field it clears on failure has been assigned:
        // `lifecycleScope` is `Main.immediate`, which starts a coroutine launched from the main thread
        // INLINE, and a failure that beat the assignment would leave a completed job in `recordJob` —
        // which is the state that refuses every later Start.
        recordJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            // `start` opens the row over Room and is the one thing here that can fail before there is
            // anything to record. Uncaught it would reach no handler — this scope is the main
            // dispatcher's and carries none — and take the process with it.
            try {
                val session = container.exerciseController.start(kind)
                sessionId = session.id
                val rec = ExerciseRecorder(
                    session = session,
                    source = LocationSource(
                        locationManager = locationManager,
                        // Asked live, not captured: the grant can be upgraded from Approximate to Precise
                        // in Settings while the bout runs, and the next fix should then be believed.
                        precise = { hasPreciseLocation(this@ExerciseService) },
                    ),
                    samples = RepositoryExerciseSampleWriter(
                        repository = container.repository,
                        curves = container.curveEngine,
                        // Read per write, not captured: the patient can move the slider mid-bout, and
                        // what the next write records is what could be justified at that moment.
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
                        // A Stop cancels this job on purpose, so only a real fault is worth a line —
                        // and swallowing the cancellation would leave the job completing normally
                        // under the very cancel that `stopBout` is waiting on.
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
     * Hand the service back after a bout that never opened.
     *
     * There is no row to close — [com.t1dm.data.exercise.ExerciseController.start] is what failed — but
     * the service is in the foreground with a notification and [recordJob] still pointing at the
     * coroutine that failed. That reference is what `startBout`'s own guard reads, so leaving it set
     * refuses every later ACTION_START for as long as the process lives while nothing records. Stopping
     * is the state a retry starts from, and the refusal is what tells the user there is one to make.
     *
     * Silent once the bout has been superseded or stopped. A newer bout owns the service by then, or a
     * Stop is already flushing this one, and cutting either short is the very failure it exists to
     * avoid.
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
     * Close the bout at the instant Stop was pressed — not at whenever the flush happens to settle.
     *
     * A STOP racing a START that has not yet returned its row leaves the row open; nothing here can
     * close a row it has not been told the id of. That is the case `ExerciseController` closes at
     * launch, marked interrupted, and it is why that reconcile exists.
     *
     * **The recording job is JOINED, not merely cancelled, before the flush.** Cancellation is
     * cooperative: the collector is still unwinding for as long as it is suspended inside a sample
     * write or a track append, and `finish` drives the very same [com.t1dm.sensors.ExerciseBucketer]
     * and the same pending-fix list from a different thread. That bucketer is documented single-
     * collector, and the duration it would come out of the overlap holding is what the disposal
     * curve written into `sample.exercise` — and pushed to the server — is scaled by.
     *
     * **[stopSelf] is the LAST thing, not the first.** The join, the final partial bucket and the last
     * batched fixes all take real time, and until this service leaves the foreground nothing else is
     * holding the process up — Stop is most often pressed from the shade, with the app not visible, so
     * the process is a cached one the moment the notification goes. A kill in that window would lose
     * the tail of the bout AND leave `endMs` NULL, and the next launch's reconcile would then report a
     * bout the user did end as one the app cut short. Staying foreground across the flush is what buys
     * the time to finish it.
     *
     * **The first Stop of a bout is the only one.** The panel's button and the shade's action are two
     * deliveries of the same intent, and the second arrives while the first is still flushing — the
     * fields it reads are already nulled, so it would find nothing to join or write and go straight to
     * [stopSelf], dropping the service out of the foreground under the write. [BoutGate] closes that:
     * every later call returns here until the next [startBout] re-arms it.
     *
     * [interrupted] marks a bout this service ended on its own — the [EXERCISE_MAX_BOUT_MS] limit —
     * rather than one the user ended, so the stored row does not claim its end time was a decision.
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
            // A Start arriving during the flush has already taken the service over. Clearing the
            // snapshot or stopping now would be this bout ending that one.
            if (gate.isCurrent(generation)) {
                container.activeExercise.value = null
                stopSelf()
            }
        }
    }

    /**
     * The backstop on a bout nobody stopped.
     *
     * Everything else about this service is deliberately passive — it never stops on a degraded
     * track, because a recording that quietly ended would hand back a truncated route that looks
     * complete. A bout that is simply FORGOTTEN is the other failure, and it is the worse one: GNSS
     * at a four-second cadence is the same always-on-radio shape as the drain regression this
     * project has already paid for once, the notification can be dismissed on 14+ while the service
     * runs on, and swiping the app away does not stop it. So there is one hard bound, and the row it
     * closes is marked interrupted — the user did not end it here.
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

    /**
     * Keep the notification on the bout, at a minute's grain: it is rebuilt when [progressKey] moves
     * and not once per fix, so a 4-second receiver cadence is not 75 shade updates a bucket. The line
     * says minutes because that is the resolution it is actually refreshed at.
     */
    private fun observeProgress() {
        lifecycleScope.launch {
            container.activeExercise
                .distinctUntilChangedBy { it?.let(::progressKey) }
                .collect { active -> if (active != null) notify(progressText(active)) }
        }
    }

    /**
     * The shade entry for a running bout — and the only way to end one without opening the app.
     *
     * The action is not decoration. This notification is what is in front of the user while the
     * receiver runs, and without it the sole stop path was the panel's own button: a bout begun on
     * the way out of the door could only be ended by navigating back to the Exercise panel, which is
     * exactly how one gets left running. It targets this service directly, so the tap is the same
     * [ACTION_STOP] the panel sends.
     */
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

    /** Tap → the app. Its own request code: an `Intent` to [MainActivity] with no action or data is
     *  `filterEquals` to the monitor's, and a shared code would have the two update each other. */
    private fun openIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, RQ_OPEN, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** `getForegroundService`, not `getService`: the tap arrives while the app is in the background,
     *  and a plain service start from there is refused on 26+. */
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

    /** Unknown names decode to [ExerciseKind.OTHER], as the stored `kind` column does: a bout is
     *  never refused for being labelled by a build this one does not know. */
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

        /**
         * What the panel REQUESTS. Either of the two grants this service its `location` type, so
         * either is enough to open a bout — the seconds are recorded whatever the receiver does.
         *
         * A TRACK needs [Manifest.permission.ACCESS_FINE_LOCATION] specifically; see
         * [hasPreciseLocation]. Both are asked for together because the system's own dialog offers
         * Precise and Approximate as one choice, and requesting only FINE would not change what the
         * user can pick.
         *
         * Held here rather than restated at the panel: this is the set the service fails closed on,
         * so it is the set Start has to have obtained.
         */
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /**
         * Whether a bout could draw a route at all.
         *
         * A coarse-only client still receives `GPS_PROVIDER` updates, but the platform fuzzes them to
         * a block with an accuracy circle of roughly 2 km, and `ExerciseBucketer` refuses every one of
         * them at its 50 m ceiling. So this is what the panel checks before Start, and what
         * [LocationSource] checks before it registers the receiver at all.
         */
        fun hasPreciseLocation(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        const val ACTION_START = "com.t1dm.app.START_EXERCISE"
        const val ACTION_STOP = "com.t1dm.app.STOP_EXERCISE"
        const val EXTRA_KIND = "kind"

        /** The panel's read on a bout that could not start at all. */
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
 * Which bout [ExerciseService] is on, and whether that bout's close-out has begun.
 *
 * Its own type because a [android.app.Service] cannot be built off-device, and the two orderings that
 * hang off these fields are precisely the ones worth a test: a second Stop must not cut the first
 * one's flush short, and a flush that outlived its own bout must not tear down, or blank the live
 * snapshot of, the bout that replaced it.
 *
 * [generation] is volatile because it is written on the main thread and read from
 * [AppContainer.appScope], which runs on the default dispatcher. [stopping] is main-thread only —
 * every caller is either `onStartCommand` or a `lifecycleScope` collector.
 */
internal class BoutGate {

    @Volatile
    var generation: Int = 0
        private set

    /** Whether the current bout's close-out has already been entered. */
    var stopping: Boolean = false
        private set

    /** Take the service over for a new bout, superseding any close-out still settling. */
    fun start(): Int {
        stopping = false
        return ++generation
    }

    /** True for the FIRST stop of a bout, false for every repeat until the next [start]. */
    fun beginStop(): Boolean {
        if (stopping) return false
        stopping = true
        return true
    }

    /** Whether work that began at [generation] still belongs to the bout the service is on. */
    fun isCurrent(generation: Int): Boolean = generation == this.generation
}

/**
 * What the running bout's notification SAYS: elapsed minutes, and the distance to the metre once the
 * bout has covered any.
 *
 * Precise on purpose. It is rendered from whichever snapshot crossed a [progressKey] boundary, so
 * rounding it here would give up grain the rebuild has already been paid for.
 */
internal fun progressText(active: ActiveExercise): String {
    val minutes = "${active.elapsedMs / 60_000L} min"
    val distance = distanceLabel(active.distanceM) ?: return minutes
    return "$minutes · $distance"
}

/**
 * What the notification is REBUILT on — deliberately coarser than what it says.
 *
 * [progressText] carries metres and [LocationSource] accepts a fix every
 * [LocationSource.MIN_TIME_MS], so keying the rebuild on the rendered line filters nothing at all
 * while the user is moving: roughly 75 `notify` calls per five-minute bucket and ~10,800 over a bout
 * that runs to [EXERCISE_MAX_BOUT_MS], each one a binder transaction and a shade re-render. An
 * always-on cost of that shape is what once accounted for most of this app's drain.
 *
 * So the key moves at a minute's grain, plus the two steps the user would notice at once: a new bout,
 * and the first metres landing on a track that had none. A bout's END needs no key — the service
 * leaves the foreground and takes the notification with it.
 */
internal fun progressKey(active: ActiveExercise): String =
    "${active.session.id}|${active.elapsedMs / 60_000L}|${distanceLabel(active.distanceM) != null}"

/** The panel's read on a bout whose row could not be opened. Names the fault: the only faults that
 *  reach it are the store's own, and a bare "didn't start" leaves nothing to act on. */
internal fun startFailureText(cause: Throwable): String =
    "Didn't start — ${cause.message ?: cause::class.simpleName}"
