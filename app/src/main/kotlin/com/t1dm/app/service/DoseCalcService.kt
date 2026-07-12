package com.t1dm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.t1dm.app.T1dmApplication
import com.t1dm.app.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The short-lived foreground service that hosts the dose calculator's rolled grid-search (SPEC.private.md
 * Phase 4 §5). The search fans a candidate grid through the selected fp32 model, rolling each to the full
 * ~5 h window — seconds of CPU/APU work that must survive the Activity going to the background, hence a
 * `dataSync` foreground service rather than an in-composition coroutine.
 *
 * **Cancellable.** [ACTION_RECOMMEND] launches the search on the service scope and stops the service when
 * it settles; [ACTION_CANCEL] cancels the in-flight [Job] (the [com.t1dm.calc.BolusCalculator] yields per
 * candidate, so cancellation is prompt) and clears the advisory back to Idle. Only one search runs at a
 * time — a fresh RECOMMEND supersedes the previous.
 *
 * **Advisory-only.** The service produces an [com.t1dm.calc.AdviceResult] into
 * [AppContainer.bolusAdvice]; it never actuates insulin. Acceptance is a separate, explicit user action
 * that only *logs* the dose the human says they administered.
 */
class DoseCalcService : LifecycleService() {

    private lateinit var container: AppContainer
    private var searchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as T1dmApplication).container
        createChannel()
        startForegroundNotified()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_RECOMMEND -> {
                // Int extras so `adb shell am ... --ei carbG 60 --ei gi 70 --ei targetMgdl 110` works
                // (am has no double flag). BG targets are whole mg/dL, the display grain.
                val grams = intent.getIntExtra(EXTRA_CARB_G, 0).toDouble()
                val gi = intent.getIntExtra(EXTRA_GI, DEFAULT_GI).toDouble()
                // The user-set scalar target the calculator scores toward; a sentinel (< 0 / absent) means
                // "no override" so the persisted objective stands. Unbounded — never clamped here.
                val target = intent.getIntExtra(EXTRA_TARGET_MGDL, TARGET_NONE).takeIf { it > 0 }?.toDouble()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    runCatching { container.runBolusAdvice(grams, gi, manualTargetMgdl = target) }
                        .onFailure { Timber.tag(TAG).w(it, "bolus advice failed") }
                    stopSelf()
                }
            }
            ACTION_CANCEL -> {
                searchJob?.cancel()
                container.clearBolusAdvice()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        searchJob?.cancel()
        super.onDestroy()
    }

    private fun startForegroundNotified() {
        val notif: Notification = Notification.Builder(this, CH_CALC)
            .setSmallIcon(
                com.t1dm.app.notify.NotificationIcons.res(
                    com.t1dm.app.notify.NotificationIcons.Glyph.DOSE, container.iconStyle,
                ),
            )
            .setColor(container.notificationAccentArgb)
            .setContentTitle("Bolus advisor")
            .setContentText("Rolling the forecast over candidate doses…")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_CALC, "Bolus advisor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Runs the fail-closed bolus dose search off the UI thread."
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "DoseCalc"
        private const val CH_CALC = "t1dm.service.calc"
        private const val NOTIF_ID = 4200
        private const val DEFAULT_GI = 55

        /** Sentinel for [EXTRA_TARGET_MGDL] meaning "no scalar-target override" (use the persisted objective). */
        const val TARGET_NONE = -1

        const val ACTION_RECOMMEND = "com.t1dm.app.RECOMMEND_BOLUS"
        const val ACTION_CANCEL = "com.t1dm.app.CANCEL_BOLUS"
        const val EXTRA_CARB_G = "carbG"
        const val EXTRA_GI = "gi"
        const val EXTRA_TARGET_MGDL = "targetMgdl"

        /**
         * Kick off a (cancellable) bolus recommendation; [carbG] > 0 announces a meal to condition on.
         * [targetMgdl] is the user-set scalar target BG the calculator scores toward (mg/dL); null leaves
         * the persisted objective in force. It is UNBOUNDED — the slider's own bounds are the only limit.
         */
        fun recommend(context: Context, carbG: Int = 0, gi: Int = DEFAULT_GI, targetMgdl: Double? = null) {
            val i = Intent(context, DoseCalcService::class.java).apply {
                action = ACTION_RECOMMEND
                putExtra(EXTRA_CARB_G, carbG)
                putExtra(EXTRA_GI, gi)
                putExtra(EXTRA_TARGET_MGDL, targetMgdl?.roundToInt() ?: TARGET_NONE)
            }
            context.startForegroundService(i)
        }

        fun cancel(context: Context) {
            val i = Intent(context, DoseCalcService::class.java).apply { action = ACTION_CANCEL }
            context.startForegroundService(i)
        }
    }
}
