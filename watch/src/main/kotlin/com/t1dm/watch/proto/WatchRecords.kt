package com.t1dm.watch.proto

import com.t1dm.core.model.ForecastStatus

/** What one push carries; SPEC/watch.md §5.2. All but [GLANCE] reach EXTENDED peripherals only. */
enum class WatchRecordKind { GLANCE, HISTORY_RECENT, HISTORY_DAY, FORECAST, STATS, DISPLAY }

/** Provenance codes of §5.4. */
object WatchProvenance {
    const val MEASURED = 0
    const val INTERPOLATED = 1
    const val RECONSTRUCTED = 2
    const val WARMUP = 3
}

/** Slot i sits at [startTsMs] + i·5 min; [mgdl] < 0 is empty. */
class WatchHistory(val startTsMs: Long, val mgdl: IntArray, val provenance: IntArray)

/** §5.5; an empty [median] withdraws the forecast. [fan] is step-major, [levels] per step. */
class WatchForecast(
    val anchorTsMs: Long,
    val anchorMgdl: Double,
    val status: ForecastStatus?,
    val stale: Boolean,
    val calibrated: Boolean,
    val stepMin: Int,
    val levels: Int,
    val median: DoubleArray,
    val fan: DoubleArray,
)

/** Band fractions 0..1; CV and GMI in percent, as `AdvancedStats` carries them. */
data class WatchStatsWindow(
    val days: Int,
    val nSamples: Int,
    val veryLow: Double,
    val low: Double,
    val inRange: Double,
    val high: Double,
    val veryHigh: Double,
    val meanMgdl: Double,
    val sdMgdl: Double,
    val cvPct: Double,
    val gmiPct: Double,
)

data class WatchStats(val targetLow: Int, val targetHigh: Int, val windows: List<WatchStatsWindow>)

/** ARGB per §5.7 role; the crate owns the wire order. */
data class WatchPalette(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val secondary: Int,
    val onSecondary: Int,
    val ink: Int,
    val inkMuted: Int,
    val grid: Int,
    val urgentLow: Int,
    val low: Int,
    val inRange: Int,
    val high: Int,
    val urgentHigh: Int,
)

/** [thresholds] urgent-low, low, high, urgent-high mg/dL. */
class WatchDisplay(
    val dark: Boolean,
    val palette: WatchPalette,
    val thresholds: IntArray,
    val rangeMin: Int,
    val rangeMax: Int,
    val windowH: Int,
    val staleMin: Int,
    val lossMin: Int,
    val name: String,
)

/** STATUS, §2: [deviceId] 16 lowercase hex digits, [name] what the peripheral advertises. */
data class WatchDeviceStatus(val deviceId: String, val name: String, val epoch: Int, val extended: Boolean)

/** Every layout of SPEC/watch.md; `:app` binds the Rust `t1dm-watch` crate, the one coder. */
interface WatchCodec {
    fun kex(frame: KexFrame): ByteArray

    /** Null on a frame the crate refuses. */
    fun control(bytes: ByteArray): ControlFrame?

    /** The plaintext sealed to unpair, §7. */
    fun unpair(): ByteArray

    fun glance(push: WatchPush): ByteArray

    fun outlook(o: WatchOutlook): ByteArray

    fun history(h: WatchHistory): List<ByteArray>

    fun forecast(f: WatchForecast): List<ByteArray>

    fun stats(s: WatchStats): ByteArray

    fun display(d: WatchDisplay): ByteArray

    /** Null on a short or foreign STATUS block. */
    fun status(bytes: ByteArray): WatchDeviceStatus?
}
