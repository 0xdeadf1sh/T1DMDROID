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
 * Foreground host for the dose calculator's rolled grid search (§5) — seconds of CPU that must
 * survive the Activity going to the background. Advisory only: it never actuates insulin, and
 * acceptance separately logs the dose the human says they administered.
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
                // Int extras — `am` has no double flag; BG targets are whole mg/dL anyway.
                val grams = intent.getIntExtra(EXTRA_CARB_G, 0).toDouble()
                val gi = intent.getIntExtra(EXTRA_GI, DEFAULT_GI).toDouble()
                // Absent or sentinel means no override, so the persisted objective stands.
                val target = intent.getIntExtra(EXTRA_TARGET_MGDL, TARGET_NONE).takeIf { it > 0 }?.toDouble()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    runCatching { container.runBolusAdvice(grams, gi, manualTargetMgdl = target) }
                        .onFailure { Timber.tag(TAG).w(it, "bolus advice failed") }
                    // runCatching swallows a superseded job's CancellationException, so stop only if THIS
                    // job is still current — else our stopSelf tears down the search that cancelled us.
                    if (coroutineContext[Job] === searchJob) stopSelf(startId)
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
            .setSmallIcon(com.t1dm.app.notify.NotificationIcons.res())
            .setColor(container.notificationAccentArgb)
            .setContentTitle("Bolus advisor")
            .setContentText("Rolling candidate doses…")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_CALC, "Bolus advisor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Off-thread bolus dose search"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "DoseCalc"
        private const val CH_CALC = "t1dm.service.calc"
        private const val NOTIF_ID = 4200
        private const val DEFAULT_GI = 55

        /** [EXTRA_TARGET_MGDL] sentinel: no scalar-target override. */
        const val TARGET_NONE = -1

        const val ACTION_RECOMMEND = "com.t1dm.app.RECOMMEND_BOLUS"
        const val ACTION_CANCEL = "com.t1dm.app.CANCEL_BOLUS"
        const val EXTRA_CARB_G = "carbG"
        const val EXTRA_GI = "gi"
        const val EXTRA_TARGET_MGDL = "targetMgdl"

        /**
         * [targetMgdl] is mg/dL; null leaves the persisted objective in force. It is UNBOUNDED — the
         * slider's own bounds are the only limit.
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
