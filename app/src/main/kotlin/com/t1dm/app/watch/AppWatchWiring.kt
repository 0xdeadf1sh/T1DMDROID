package com.t1dm.app.watch

import android.content.Context
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.t1dm.app.notify.GlanceReadings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.t1dm.core.model.AlertThresholds
import com.t1dm.core.model.InferenceState
import com.t1dm.core.model.ModelPrediction
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.StatsWindow
import com.t1dm.data.T1dmRepository
import com.t1dm.data.stats.StatsRepository
import com.t1dm.watch.LowPowerProvider
import com.t1dm.watch.WatchExtendedSource
import com.t1dm.watch.WatchGlanceSource
import com.t1dm.watch.crypto.NonceStore
import com.t1dm.watch.crypto.WatchDevice
import com.t1dm.watch.crypto.WatchDeviceStore
import com.t1dm.watch.crypto.WatchKeyMaterial
import com.t1dm.watch.crypto.WatchPairingStore
import com.t1dm.watch.crypto.WatchStores
import com.t1dm.watch.proto.WatchDisplay
import com.t1dm.watch.proto.WatchForecast
import com.t1dm.watch.proto.WatchHistory
import com.t1dm.watch.proto.WatchProvenance
import com.t1dm.watch.proto.WatchPush
import com.t1dm.watch.proto.WatchStats
import com.t1dm.watch.proto.WatchStatsWindow
import com.t1dm.watch.proto.WatchStatus
import com.t1dm.watch.proto.WatchTrend
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val GRID_MS = 300_000L

/** Staleness/signal loss off the last MEASURED reading's age; lowPowerSuspending set later. */
class AppWatchGlanceSource(
    private val repository: T1dmRepository,
    private val inferenceState: StateFlow<InferenceState>,
    // Providers, not values: capturing at construction would freeze the watch to boot-time config.
    private val thresholdsProvider: () -> AlertThresholds,
    private val lossMinProvider: () -> Int,
    private val staleMin: Int = STALE_MIN,
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
            bgTrend = g.trend?.toWatchTrend(),
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

    companion object {
        /** Minutes; the display record carries it so a peripheral ages the glance by this rule. */
        const val STALE_MIN = 15
    }
}

/** SPEC/watch.md §5.4–§5.7 off the authoritative source and the phone's own settings. */
class AppWatchExtendedSource(
    private val repository: T1dmRepository,
    private val inferenceState: StateFlow<InferenceState>,
    /** §8.4 fan in bandsMgdl's layout, or null to send the raw fan; as the BG panel draws it. */
    private val calibratedBands: suspend (ModelPrediction) -> List<Double>?,
    private val stats: StatsRepository,
    private val display: suspend () -> WatchDisplay,
) : WatchExtendedSource {

    override suspend fun history(nowMs: Long, slots: Int): WatchHistory? {
        val src = repository.authoritativeSourceId() ?: return null
        val end = nowMs / GRID_MS * GRID_MS
        val start = end - (slots - 1) * GRID_MS
        val mgdl = IntArray(slots) { -1 }
        val provenance = IntArray(slots)
        for (r in repository.readingsInRange(src, start, end)) {
            val i = ((r.tsMs - start) / GRID_MS).toInt()
            val bg = r.bgMgdl ?: continue
            if (i !in 0 until slots || r.flag == ReadingFlag.INVALID || bg !in 0..MAX_SLOT_MGDL) continue
            mgdl[i] = bg
            provenance[i] = when {
                r.provenance == ReadingProvenance.RECONSTRUCTED -> WatchProvenance.RECONSTRUCTED
                r.provenance == ReadingProvenance.INTERPOLATED -> WatchProvenance.INTERPOLATED
                r.flag == ReadingFlag.WARMUP -> WatchProvenance.WARMUP
                else -> WatchProvenance.MEASURED
            }
        }
        return WatchHistory(start, mgdl, provenance)
    }

    override suspend fun forecast(): WatchForecast {
        val p = inferenceState.value.selectedPrediction
        if (p == null || p.horizonSteps == 0 || p.bandsMgdl.size != p.horizonSteps * p.nQuantiles) return WITHDRAWN
        val cal = runCatching { calibratedBands(p) }.getOrNull()?.takeIf { it.size == p.bandsMgdl.size }
        return WatchForecast(
            anchorTsMs = p.anchorTsMs,
            anchorMgdl = p.lastBg,
            status = p.status,
            stale = p.stale,
            calibrated = cal != null,
            stepMin = (p.stepMs / 60_000L).toInt(),
            levels = p.nQuantiles,
            median = p.medianBg.toDoubleArray(),
            fan = (cal ?: p.bandsMgdl).toDoubleArray(),
        )
    }

    override suspend fun stats(): WatchStats {
        val target = stats.currentTargetRange()
        val windows = StatsWindow.entries.map { w ->
            val s = stats.localStats(w)
            WatchStatsWindow(
                days = w.days,
                nSamples = s.nSamples,
                veryLow = s.subBands.veryLow,
                low = s.subBands.low,
                inRange = s.subBands.inRange,
                high = s.subBands.high,
                veryHigh = s.subBands.veryHigh,
                meanMgdl = s.meanBg,
                sdMgdl = s.sd,
                cvPct = s.cv,
                gmiPct = s.gmi,
            )
        }
        return WatchStats(target.lowMgdl, target.highMgdl, windows)
    }

    override suspend fun display(): WatchDisplay = display.invoke()

    private companion object {
        /** A history slot carries 12 bits of mg/dL (§5.4). */
        const val MAX_SLOT_MGDL = 4095

        val WITHDRAWN = WatchForecast(
            anchorTsMs = 0L,
            anchorMgdl = Double.NaN,
            status = null,
            stale = false,
            calibrated = false,
            stepMin = 5,
            levels = 7,
            median = DoubleArray(0),
            fan = DoubleArray(0),
        )
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

/** kv rows `watch.<id>.*`, one namespace per pairing; `watch.devices` lists them. */
class RoomWatchStores(private val repository: T1dmRepository, context: Context) : WatchStores {
    private val cipher = WatchKeyCipher(context)

    override val devices: WatchDeviceStore = RoomWatchDeviceStore(repository)
    override fun pairing(id: String): WatchPairingStore = RoomWatchPairingStore(repository, cipher, id)
    override fun nonces(id: String): NonceStore = RoomNonceStore(repository, id)
}

/** `id,name,address` rows joined by `;`; ids and advertised names carry neither separator. */
class RoomWatchDeviceStore(private val repository: T1dmRepository) : WatchDeviceStore {
    /** Each change rewrites one row; links on other coroutines lose rows without it. */
    private val lock = Mutex()

    override suspend fun load(): List<WatchDevice> = lock.withLock {
        dropSingleWatchPairing()
        decode(repository.getKv(KEY_DEVICES))
    }

    override suspend fun put(device: WatchDevice) = lock.withLock {
        write(decode(repository.getKv(KEY_DEVICES)).filterNot { it.id == device.id } + device)
    }

    override suspend fun remove(id: String) = lock.withLock {
        write(decode(repository.getKv(KEY_DEVICES)).filterNot { it.id == id })
    }

    private suspend fun write(list: List<WatchDevice>) = repository.putKv(
        KEY_DEVICES,
        list.joinToString(";") { "${it.id},${it.name},${it.address.orEmpty()}" },
        System.currentTimeMillis(),
    )

    /** The one-watch rows name no device, so they cannot be keyed; that pairing is re-made. */
    private suspend fun dropSingleWatchPairing() {
        if (repository.getKv(LEGACY_BONDED) != "1") return
        val now = System.currentTimeMillis()
        repository.putKv(LEGACY_BONDED, "0", now)
        repository.putKv(LEGACY_MATERIAL, "", now)
    }

    private fun decode(raw: String?): List<WatchDevice> = raw.orEmpty().split(';').mapNotNull { row ->
        val f = row.split(',')
        if (f.size != 3 || f[0].isBlank() || f[1].isBlank()) null else WatchDevice(f[0], f[1], f[2].ifBlank { null })
    }

    private companion object {
        const val KEY_DEVICES = "watch.devices"
        const val LEGACY_BONDED = "watch.paired"
        const val LEGACY_MATERIAL = "watch.keymaterial"
    }
}

class RoomNonceStore(private val repository: T1dmRepository, private val deviceId: String) : NonceStore {
    override suspend fun loadCeiling(epoch: Int): Long =
        repository.getKv(key(epoch))?.toLongOrNull() ?: 0L

    override suspend fun recordCeiling(epoch: Int, seq: Long) {
        val prev = repository.getKv(key(epoch))?.toLongOrNull() ?: 0L
        if (seq > prev) repository.putKv(key(epoch), seq.toString(), System.currentTimeMillis())
    }

    override suspend fun clear() {
        // The epoch space is tiny; clear the ones we might have written.
        for (e in 0..255) {
            if (repository.getKv(key(e)) != null) repository.putKv(key(e), "0", System.currentTimeMillis())
        }
    }

    private fun key(epoch: Int) = "watch.$deviceId.nonce.$epoch"
}

/** On-disk: base64(iv):base64(ct). StrongBox preferred, TEE fallback; :app owns the Keystore. */
internal class WatchKeyCipher(context: Context) {
    private val appContext = context.applicationContext

    fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    /** Throws on any envelope this key didn't produce; caller catches for the legacy fallback. */
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

    /** Null when StrongBox is requested and rejected; the caller retries in the TEE. */
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

        /** Deletes the wrapping key; wrapped material is a kv blob, dropped by DB wipe. */
        fun deleteKey() = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        }
    }
}

/** Blob wrapped at rest by WatchKeyCipher; a legacy plaintext blob is read, re-wrapped on save. */
class RoomWatchPairingStore internal constructor(
    private val repository: T1dmRepository,
    private val cipher: WatchKeyCipher,
    deviceId: String,
) : WatchPairingStore {
    private val keyBonded = "watch.$deviceId.paired"
    private val keyEpoch = "watch.$deviceId.epoch"
    private val keyMaterial = "watch.$deviceId.keymaterial"

    override suspend fun load(): WatchPairingStore.Pairing? {
        val bonded = repository.getKv(keyBonded) == "1"
        if (!bonded) return null
        val epoch = repository.getKv(keyEpoch)?.toIntOrNull() ?: 0
        val material = repository.getKv(keyMaterial)
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
        repository.putKv(keyBonded, if (pairing.bonded) "1" else "0", now)
        repository.putKv(keyEpoch, pairing.epoch.toString(), now)
        pairing.material?.let {
            repository.putKv(keyMaterial, cipher.wrap(it.bytes), now)
        }
    }

    override suspend fun clear() {
        val now = System.currentTimeMillis()
        repository.putKv(keyBonded, "0", now)
        repository.putKv(keyMaterial, "", now)
    }
}
