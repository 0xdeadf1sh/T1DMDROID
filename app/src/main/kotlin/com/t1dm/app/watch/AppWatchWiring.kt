package com.t1dm.app.watch

import android.content.Context
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.t1dm.core.common.T1dmDispatchers
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.t1dm.core.model.AlertThresholds
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

        // Lift the ONE shared computation (BgGlanceComputer) so the watch, the always-on
        // notification, and the widgets agree by construction (Phase 7B). The §3.6 gate lives
        // there; the watch just maps the glance onto its frozen wire record.
        val g = com.t1dm.app.notify.BgGlanceComputer.compute(
            latest = latest,
            state = inferenceState.value,
            thresholds = thresholds,
            lossMin = lossMin,
            staleMin = staleMin,
            nowMs = nowMs,
        )

        return WatchPush(
            bgMgdl = g.bgMgdl,
            trendTenths = g.trendTenths,
            readingAgeMs = g.readingAgeMs,
            alertBand = g.band,
            forecastStatus = g.forecastStatus,
            fcEndMgdl = g.fcEndMgdl,
            fcHorizonSteps = g.horizonSteps,
            fcTrend = g.trend.toWatchTrend(),
            summary = g.summary,
            status = WatchStatus(
                stale = g.stale,
                signalLoss = g.signalLoss,
                warmup = g.warmup,
                predictedLowCrossing = g.predictedLowCrossing,
                predictedHighCrossing = g.predictedHighCrossing,
                alarmActive = g.alarmActive,
                // Preserve the frozen wire semantics: "no eligible forecast" (true in warmup too,
                // where fcEnd is null) — the warmup bit distinguishes the collecting-context case.
                forecastUnavailable = g.fcEndMgdl == null,
            ),
        )
    }

    private fun com.t1dm.app.notify.GlanceTrend.toWatchTrend(): WatchTrend = when (this) {
        com.t1dm.app.notify.GlanceTrend.RISING_FAST -> WatchTrend.RISING_FAST
        com.t1dm.app.notify.GlanceTrend.RISING -> WatchTrend.RISING
        com.t1dm.app.notify.GlanceTrend.FLAT -> WatchTrend.FLAT
        com.t1dm.app.notify.GlanceTrend.FALLING -> WatchTrend.FALLING
        com.t1dm.app.notify.GlanceTrend.FALLING_FAST -> WatchTrend.FALLING_FAST
    }
}

/**
 * Battery-saver / low-power detection (progress.md Q9 — default entry 20 %, configurable). Two
 * independent triggers, both settings-gated (Phase 7C item 14): the OS power-save signal AND a
 * user-set battery-percentage floor. When the whole feature is disabled it never suspends the watch
 * push. Every input is read fresh per call so a Settings change takes effect on the next 5-min tick;
 * a failed battery read fails OPEN (not low-power) so the push is never wrongly muted.
 */
class AndroidLowPowerProvider(
    private val context: Context,
    private val enabled: suspend () -> Boolean,
    private val thresholdPercent: suspend () -> Int,
    private val useOsSaver: suspend () -> Boolean,
) : LowPowerProvider {
    private val pm = context.getSystemService(PowerManager::class.java)

    override suspend fun isLowPower(): Boolean {
        if (!enabled()) return false
        if (useOsSaver() && pm?.isPowerSaveMode == true) return true
        val pct = batteryPercent() ?: return false
        return pct <= thresholdPercent()
    }

    private fun batteryPercent(): Int? = runCatching {
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    }.getOrNull()
}

/** Windowed nonce ceiling in the Room `kv` store, per epoch (risk S6 burn-the-window). */
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

/**
 * AndroidKeyStore-backed envelope for the watch durable blob: a hardware-bound AES-256-GCM key wraps
 * the material and the on-disk form is `base64(iv):base64(ct)`. Mirrors [com.t1dm.sync.KeystoreTokenStore]
 * (stock GCM, non-exportable key) with a distinct alias, but PREFERS StrongBox (the K90's Dimensity
 * 9500 exposes it; progress.md Q6), degrading to a TEE key if StrongBox is unavailable. Kept in `:app`
 * because only the composition root has the Keystore; `:watch` stays pure-JVM.
 */
internal class WatchKeyCipher(context: Context) {
    private val appContext = context.applicationContext

    fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** Reverses [wrap]; throws (caught by the caller for the legacy-plaintext fallback) on any envelope
     *  that this key did not produce — a bare base64 blob has no `:` and fails here immediately. */
    fun unwrap(packed: String): ByteArray {
        val sep = packed.indexOf(':')
        require(sep > 0) { "not a wrapped envelope" }
        val iv = Base64.decode(packed.substring(0, sep), Base64.NO_WRAP)
        val ct = Base64.decode(packed.substring(sep + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generate(strongBox = true) ?: generate(strongBox = false)!!
    }

    /** Returns null when StrongBox was requested but the platform rejects it, so the caller retries in
     *  the TEE. `appContext` is unused by the spec but kept to bind the key to this install's Keystore. */
    private fun generate(strongBox: Boolean): SecretKey? {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        gen.init(spec)
        return try {
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            if (strongBox) null else throw e
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "t1dm_watch_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /** Full-erase (issue 5): delete the wrapping key. The wrapped watch key MATERIAL is a kv blob
         *  (dropped by the DB wipe); removing the alias burns the last recoverable trace. Idempotent. */
        fun deleteKey() = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }
}

/** Pairing bit + epoch + the authoritative uniffi session's durable blob (X25519 secret + epoch root
 *  + burned nonce ceiling) in the Room `kv` store. The blob is wrapped at rest with an AndroidKeyStore
 *  AES-256-GCM key ([WatchKeyCipher]); a Phase-5 CLEAR base64 blob written before the wrap existed is
 *  read back via the legacy fallback and RE-WRAPPED on the next save (no forced re-pair). The host-test
 *  loopback double carries no key material, so [WatchPairingStore.Pairing.material] is null there and
 *  only the paired/epoch bits are meaningful. */
class RoomWatchPairingStore(
    private val repository: T1dmRepository,
    context: Context,
) : WatchPairingStore {
    private val cipher = WatchKeyCipher(context)

    override suspend fun load(): WatchPairingStore.Pairing? {
        val bonded = repository.getKv(KEY_BONDED) == "1"
        if (!bonded) return null
        val epoch = repository.getKv(KEY_EPOCH)?.toIntOrNull() ?: 0
        val material = repository.getKv(KEY_MATERIAL)
            ?.takeIf { it.isNotBlank() }
            ?.let { decodeMaterial(it) }
            ?.let { WatchKeyMaterial(it) }
        return WatchPairingStore.Pairing(epoch = epoch, bonded = true, material = material)
    }

    /** Unwrap the Keystore envelope; if that fails, interpret the stored value as the legacy plaintext
     *  base64 blob so a pre-wrap on-device session still resumes (it is re-wrapped on the next save). */
    private fun decodeMaterial(stored: String): ByteArray? =
        runCatching { cipher.unwrap(stored) }
            .getOrElse { runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() }

    override suspend fun save(pairing: WatchPairingStore.Pairing) {
        val now = System.currentTimeMillis()
        repository.putKv(KEY_BONDED, if (pairing.bonded) "1" else "0", now)
        repository.putKv(KEY_EPOCH, pairing.epoch.toString(), now)
        pairing.material?.let {
            repository.putKv(KEY_MATERIAL, cipher.wrap(it.bytes), now)
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
