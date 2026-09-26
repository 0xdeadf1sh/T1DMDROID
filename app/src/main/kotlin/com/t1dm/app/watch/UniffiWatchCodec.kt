package com.t1dm.app.watch

import com.t1dm.watch.proto.ControlFrame
import com.t1dm.watch.proto.KexFrame
import com.t1dm.watch.proto.WatchCodec
import com.t1dm.watch.proto.WatchDeviceStatus
import com.t1dm.watch.proto.WatchDisplay
import com.t1dm.watch.proto.WatchForecast
import com.t1dm.watch.proto.WatchHistory
import com.t1dm.watch.proto.WatchPush
import com.t1dm.watch.proto.WatchStats
import uniffi.t1dm_core.WatchControlOut
import uniffi.t1dm_core.WatchDisplayIn
import uniffi.t1dm_core.WatchForecastIn
import uniffi.t1dm_core.WatchGlanceIn
import uniffi.t1dm_core.WatchPaletteIn
import uniffi.t1dm_core.WatchStatsWindowIn
import uniffi.t1dm_core.watchDecodeControl
import uniffi.t1dm_core.watchDecodeStatus
import uniffi.t1dm_core.watchEncodeConfirm
import uniffi.t1dm_core.watchEncodeDisplay
import uniffi.t1dm_core.watchEncodeForecast
import uniffi.t1dm_core.watchEncodeGlance
import uniffi.t1dm_core.watchEncodeHello
import uniffi.t1dm_core.watchEncodeHistory
import uniffi.t1dm_core.watchEncodeStats
import uniffi.t1dm_core.watchEncodeUnpair

/** [WatchCodec] over Rust `t1dm-watch`, as T1DMKDE decodes. Encoders throw on refusal. */
class UniffiWatchCodec : WatchCodec {

    override fun kex(frame: KexFrame): ByteArray = when (frame) {
        is KexFrame.Hello -> watchEncodeHello(frame.epoch, frame.publicKey)
        is KexFrame.Confirm -> watchEncodeConfirm(frame.epoch, frame.ok)
    }

    override fun control(bytes: ByteArray): ControlFrame? = runCatching {
        when (val c = watchDecodeControl(bytes)) {
            is WatchControlOut.HelloAck -> ControlFrame.HelloAck(c.epoch, c.key)
            is WatchControlOut.ConfirmAck -> ControlFrame.ConfirmAck(c.epoch, c.ok)
            is WatchControlOut.ErrEpoch -> ControlFrame.ErrEpoch(c.epoch)
            is WatchControlOut.ErrAuth -> ControlFrame.ErrAuth(c.epoch)
            is WatchControlOut.PushAck -> ControlFrame.PushAck(c.epoch, c.seq)
        }
    }.getOrNull()

    override fun unpair(): ByteArray = watchEncodeUnpair()

    override fun glance(push: WatchPush): ByteArray = watchEncodeGlance(
        WatchGlanceIn(
            lowPower = push.status.lowPowerSuspending,
            stale = push.status.stale,
            signalLoss = push.status.signalLoss,
            warmup = push.status.warmup,
            predictedLow = push.status.predictedLowCrossing,
            predictedHigh = push.status.predictedHighCrossing,
            alarm = push.status.alarmActive,
            forecastUnavailable = push.status.forecastUnavailable,
            bgMgdl = push.bgMgdl,
            trendTenths = push.trendTenths,
            alertBand = push.alertBand?.ordinal,
            forecastStatus = push.forecastStatus?.ordinal,
            fcEndMgdl = push.fcEndMgdl,
            fcHorizonSteps = push.fcHorizonSteps,
            fcTrend = push.fcTrend.ordinal,
            readingAgeMs = push.readingAgeMs,
            bgTrend = push.bgTrend?.ordinal,
            summary = push.summary,
        ),
    )

    override fun history(h: WatchHistory): List<ByteArray> =
        watchEncodeHistory(h.startTsMs, h.mgdl.toList(), h.provenance.toList())

    override fun forecast(f: WatchForecast): List<ByteArray> = watchEncodeForecast(
        WatchForecastIn(
            anchorTsMs = f.anchorTsMs,
            anchorMgdl = f.anchorMgdl,
            forecastStatus = f.status?.ordinal ?: NONE,
            stale = f.stale,
            calibrated = f.calibrated,
            stepMin = f.stepMin,
            levels = f.levels,
            median = f.median.toList(),
            fan = f.fan.toList(),
        ),
    )

    override fun stats(s: WatchStats): ByteArray = watchEncodeStats(
        s.targetLow,
        s.targetHigh,
        s.windows.map {
            WatchStatsWindowIn(
                days = it.days,
                nSamples = it.nSamples,
                veryLow = it.veryLow,
                low = it.low,
                inRange = it.inRange,
                high = it.high,
                veryHigh = it.veryHigh,
                meanMgdl = it.meanMgdl,
                sdMgdl = it.sdMgdl,
                cvPct = it.cvPct,
                gmiPct = it.gmiPct,
            )
        },
    )

    override fun display(d: WatchDisplay): ByteArray = watchEncodeDisplay(
        WatchDisplayIn(
            dark = d.dark,
            palette = d.palette.let {
                WatchPaletteIn(
                    background = it.background,
                    surface = it.surface,
                    surfaceVariant = it.surfaceVariant,
                    primary = it.primary,
                    onPrimary = it.onPrimary,
                    secondary = it.secondary,
                    onSecondary = it.onSecondary,
                    ink = it.ink,
                    inkMuted = it.inkMuted,
                    grid = it.grid,
                    urgentLow = it.urgentLow,
                    low = it.low,
                    inRange = it.inRange,
                    high = it.high,
                    urgentHigh = it.urgentHigh,
                )
            },
            thresholds = d.thresholds.toList(),
            rangeMin = d.rangeMin,
            rangeMax = d.rangeMax,
            windowH = d.windowH,
            staleMin = d.staleMin,
            lossMin = d.lossMin,
            name = d.name,
        ),
    )

    override fun status(bytes: ByteArray): WatchDeviceStatus? = runCatching {
        watchDecodeStatus(bytes).let { WatchDeviceStatus(it.deviceId, it.name, it.epoch, it.extended) }
    }.getOrNull()

    private companion object {
        /** §5.3's "none" forecast status. */
        const val NONE = 0xFF
    }
}
