package com.t1dm.app.watch

import android.content.Context
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.t1dm.app.notify.GlanceReadings
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

private const val GRID_MS = 300_000L

/**
 * Staleness and signal loss come off the last MEASURED reading's age;
 * [WatchStatus.lowPowerSuspending] is set later by the link.
 */
class AppWatchGlanceSource(
    private val repository: T1dmRepository,
    private val inferenceState: StateFlow<InferenceState>,
    // Providers, not values: the thresholds and loss window are live config that Settings edits
    // re-hydrate, and capturing them at construction freezes the watch to boot-time values.
    private val thresholdsProvider: () -> AlertThresholds,
    private val lossMinProvider: () -> Int,
    private val staleMin: Int = 15,
) : WatchGlanceSource {

    override suspend fun currentGlance(nowMs: Long): WatchPush? {
        val src = repository.authoritativeSourceId() ?: return null
        // 36 rows, not 1: the newest MEASUREMENT can sit behind a promoted reconstruction.
        val rows = repository.recentReadings(src, 36)
        val readings = GlanceReadings.create(rows)
        if (readings.latest == null) return null

        // The one shared computation, so watch, notification and widgets agree by construction.
        val g = com.t1dm.app.notify.BgGlanceComputer.compute(
            readings = readings,
            state = inferenceState.value,
            thresholds = thresholdsProvider(),
            lossMin = lossMinProvider(),
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
            fcTrend = g.fcTrend.toWatchTrend(),
            summary = g.summary,
            status = WatchStatus(
                stale = g.stale,
                signalLoss = g.signalLoss,
                warmup = g.warmup,
                predictedLowCrossing = g.predictedLowCrossing,
                predictedHighCrossing = g.predictedHighCrossing,
                alarmActive = g.alarmActive,
                // Frozen wire semantics: true in warmup too, where fcEnd is null.
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

/** A failed battery read fails OPEN (not low-power), so the push is never wrongly muted. */
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
 * On-disk form is `base64(iv):base64(ct)`. StrongBox preferred, TEE if unavailable. In `:app`
 * because only the composition root has the Keystore.
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

    /** Throws on any envelope this key did not produce; the caller catches for the legacy fallback. */
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

    /** Null when StrongBox was requested and the platform rejects it; the caller retries in the TEE. */
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

        /** Deletes the wrapping key; the wrapped material is a kv blob the DB wipe drops. Idempotent. */
        fun deleteKey() = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }
}

/** The blob is wrapped at rest by [WatchKeyCipher]; a plaintext base64 blob written before the wrap
 *  existed is read via the legacy fallback and re-wrapped on the next save. The host-test loopback
 *  double carries no key material, so [WatchPairingStore.Pairing.material] is null there. */
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
