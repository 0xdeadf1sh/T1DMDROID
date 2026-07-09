package com.t1dm.app.watch

import android.content.Context
import android.os.PowerManager
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.InferenceState
import com.t1dm.data.T1dmRepository
import com.t1dm.watch.LowPowerProvider
import com.t1dm.watch.WatchGlanceSource
import com.t1dm.watch.crypto.NonceStore
import com.t1dm.watch.crypto.WatchKeyMaterial
import com.t1dm.watch.crypto.WatchPairingStore
import com.t1dm.watch.proto.WatchPush
import com.t1dm.watch.proto.WatchStatus
import com.t1dm.watch.proto.WatchTrend
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * `:app` bindings for the `:watch` ports (the removable seam — everything the module needs about the
 * rest of the app arrives here, so deleting these three classes + the DI wiring excises the watch).
 * The glance is composed from the repository (BG/trend/age), the threshold bands, and the SELECTED
 * model's latest prediction; nonce + pairing persistence ride the Room `kv` store.
 */

private const val GRID_MS = 300_000L

/**
 * Builds the 5-min glance (watch-link.md, locked contract): current BG + trend + a one-line forecast
 * summary from the selected model + the alert band + status bits. Derives the staleness/signal-loss
 * bits from the last MEASURED reading's age (the freshness/loss windows) since the deterministic
 * alarm engine lives in the FGS, not here; the [WatchStatus.lowPowerSuspending] bit is set later by
 * the link.
 */
class AppWatchGlanceSource(
    private val repository: T1dmRepository,
    private val inferenceState: StateFlow<InferenceState>,
    private val thresholds: AlertThresholds,
    private val lossMin: Int,
    private val staleMin: Int = 15,
) : WatchGlanceSource {

    override suspend fun currentGlance(nowMs: Long): WatchPush? {
        val src = repository.activeSourceId() ?: return null
        val latest = repository.recentReadings(src, 1).firstOrNull() ?: return null
        val bg = latest.bgMgdl
        val ageMs = (nowMs - latest.rxWallMs).coerceAtLeast(0L)
        val band = bg?.let { thresholds.bandFor(it) }

        val state = inferenceState.value
        val sel = state.selectedPrediction
        val eligible = sel?.eligible == true
        val fcEnd = sel?.takeIf { eligible }?.medianBg?.lastOrNull()?.roundToInt()
        val horizon = sel?.horizonSteps ?: 0

        val predLow = eligible && sel!!.medianBg.any { it < thresholds.lowMgdl }
        val predHigh = eligible && sel!!.medianBg.any { it >= thresholds.highMgdl }
        val signalLoss = ageMs > lossMin * 60_000L
        val stale = ageMs > staleMin * 60_000L
        val alarmActive = band == com.t1dm.core.model.AlertBand.URGENT_LOW ||
            band == com.t1dm.core.model.AlertBand.URGENT_HIGH || signalLoss

        return WatchPush(
            bgMgdl = bg,
            trendTenths = latest.trendTenthsPerMin,
            readingAgeMs = ageMs,
            alertBand = band,
            forecastStatus = sel?.status,
            fcEndMgdl = fcEnd,
            fcHorizonSteps = horizon,
            fcTrend = classifyTrend(latest.trendTenthsPerMin, bg, fcEnd),
            summary = summarize(bg, latest.trendTenthsPerMin, eligible, fcEnd, horizon, sel?.status, state.warmup != null),
            status = WatchStatus(
                stale = stale,
                signalLoss = signalLoss,
                warmup = state.warmup != null,
                predictedLowCrossing = predLow,
                predictedHighCrossing = predHigh,
                alarmActive = alarmActive,
                forecastUnavailable = sel == null || !eligible,
            ),
        )
    }

    private fun classifyTrend(trendTenths: Int?, bg: Int?, fcEnd: Int?): WatchTrend {
        // Prefer the measured rate; fall back to the forecast delta.
        val rate = trendTenths?.let { it / 10.0 } ?: run {
            if (bg != null && fcEnd != null) (fcEnd - bg) / 24.0 else 0.0
        }
        return when {
            rate > 2.0 -> WatchTrend.RISING_FAST
            rate > 0.5 -> WatchTrend.RISING
            rate < -2.0 -> WatchTrend.FALLING_FAST
            rate < -0.5 -> WatchTrend.FALLING
            else -> WatchTrend.FLAT
        }
    }

    private fun summarize(
        bg: Int?, trendTenths: Int?, eligible: Boolean, fcEnd: Int?, horizonSteps: Int,
        status: ForecastStatus?, warmup: Boolean,
    ): String = when {
        warmup -> "collecting context"
        eligible && fcEnd != null -> {
            val mins = horizonSteps * 5
            val h = if (mins % 60 == 0) "${mins / 60}h" else "${mins}m"
            val dir = when {
                bg == null -> "to"
                fcEnd - bg > 10 -> "rising to"
                bg - fcEnd > 10 -> "falling to"
                else -> "steady ~"
            }
            "$dir $fcEnd in $h"
        }
        status != null && status != ForecastStatus.OK -> "forecast unavailable"
        bg != null -> {
            val arrow = when {
                (trendTenths ?: 0) > 20 -> "↑↑"
                (trendTenths ?: 0) > 5 -> "↑"
                (trendTenths ?: 0) < -20 -> "↓↓"
                (trendTenths ?: 0) < -5 -> "↓"
                else -> "→"
            }
            "$bg $arrow"
        }
        else -> "no reading"
    }.let { if (it.length <= WatchPush.MAX_SUMMARY) it else it.take(WatchPush.MAX_SUMMARY) }
}

/** Battery-saver / low-power detection (progress.md Q9 — default 20 %, configurable). Uses the OS
 *  power-save signal, the cheapest reliable proxy for the phone's own conservation state. */
class AndroidLowPowerProvider(context: Context) : LowPowerProvider {
    private val pm = context.getSystemService(PowerManager::class.java)
    override suspend fun isLowPower(): Boolean = pm?.isPowerSaveMode == true
}

/** Windowed nonce ceiling in the Room `kv` store, per epoch (PLAN risk S6 burn-the-window). */
class RoomNonceStore(private val repository: T1dmRepository) : NonceStore {
    override suspend fun loadCeiling(epoch: Int): Long =
        repository.getKv(key(epoch))?.toLongOrNull() ?: 0L

    override suspend fun recordCeiling(epoch: Int, seq: Long) {
        val prev = repository.getKv(key(epoch))?.toLongOrNull() ?: 0L
        if (seq > prev) repository.putKv(key(epoch), seq.toString(), System.currentTimeMillis())
    }

    override suspend fun clear() {
        // The epoch space is tiny; clear the ones we might have written.
        for (e in 0..255) repository.putKv(key(e), "0", System.currentTimeMillis())
    }

    private fun key(epoch: Int) = "watch.nonce.ceiling.$epoch"
}

/** Pairing bit + epoch + the authoritative uniffi session's durable blob (X25519 secret + epoch root
 *  + burned nonce ceiling) in the Room `kv` store, base64'd (progress.md Q6 — Keystore wrap TODO). The
 *  host-test loopback double carries no key material, so [WatchPairingStore.Pairing.material] is null
 *  there and only the paired/epoch bits are meaningful. */
class RoomWatchPairingStore(private val repository: T1dmRepository) : WatchPairingStore {
    override suspend fun load(): WatchPairingStore.Pairing? {
        val bonded = repository.getKv(KEY_BONDED) == "1"
        if (!bonded) return null
        val epoch = repository.getKv(KEY_EPOCH)?.toIntOrNull() ?: 0
        val material = repository.getKv(KEY_MATERIAL)
            ?.let { WatchKeyMaterial(android.util.Base64.decode(it, android.util.Base64.NO_WRAP)) }
        return WatchPairingStore.Pairing(epoch = epoch, bonded = true, material = material)
    }

    override suspend fun save(pairing: WatchPairingStore.Pairing) {
        val now = System.currentTimeMillis()
        repository.putKv(KEY_BONDED, if (pairing.bonded) "1" else "0", now)
        repository.putKv(KEY_EPOCH, pairing.epoch.toString(), now)
        pairing.material?.let {
            repository.putKv(KEY_MATERIAL, android.util.Base64.encodeToString(it.bytes, android.util.Base64.NO_WRAP), now)
        }
    }

    override suspend fun clear() {
        val now = System.currentTimeMillis()
        repository.putKv(KEY_BONDED, "0", now)
        repository.putKv(KEY_MATERIAL, "", now)
    }

    private companion object {
        const val KEY_BONDED = "watch.paired"
        const val KEY_EPOCH = "watch.epoch"
        const val KEY_MATERIAL = "watch.keymaterial"
    }
}
