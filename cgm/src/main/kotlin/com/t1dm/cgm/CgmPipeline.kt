package com.t1dm.cgm

import com.t1dm.core.common.NativeCore
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.DecodedAdvert
import com.t1dm.core.model.ReadingFlag
import java.util.TimeZone

data class PipelineOutput(
    val readings: List<CgmReading>,
    /** Null when no ≥20-byte 0x0059 payload was present. */
    val glucosePayload: ByteArray?,
    /** Null on a short or CRC-failing payload. */
    val decoded: DecodedAdvert?,
    /** Present whenever a payload was isolated. */
    val minFromStart: Int?,
) {
    /** A payload that decoded is CRC-valid (CGM.md §3.2). */
    val crcValid: Boolean get() = decoded != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PipelineOutput) return false
        return readings == other.readings &&
            (glucosePayload?.contentEquals(other.glucosePayload) ?: (other.glucosePayload == null)) &&
            decoded == other.decoded && minFromStart == other.minFromStart
    }

    override fun hashCode(): Int {
        var h = readings.hashCode()
        h = 31 * h + (glucosePayload?.contentHashCode() ?: 0)
        h = 31 * h + (decoded?.hashCode() ?: 0)
        h = 31 * h + (minFromStart ?: 0)
        return h
    }
}

/**
 * The passive-advert decode pipeline for one CGM source. Stages are synchronous and
 * side-effect-free; persistence is the caller's job. Stateful (the dedup ring and the grid stamper),
 * so exactly one instance per source per thread.
 */
class CgmPipeline(
    private val sourceId: CgmSourceId,
    private val nativeCore: NativeCore,
    private val classifier: ReadingClassifier = ReadingClassifier(),
    private val dedup: DedupRing = DedupRing(),
    private val gridStamper: GridStamper = GridStamper(),
    /** Grid instant → local UTC offset, minutes. */
    private val tzOffsetMinFor: (Long) -> Int = { ms -> TimeZone.getDefault().getOffset(ms) / 60_000 },
) {
    fun process(raw: RawAdvert): PipelineOutput {
        val payload = AdStructureParser.manufacturerPayload(raw.adBytes)
            ?: return PipelineOutput(emptyList(), null, null, null)

        // The first LE u16 of the payload (CGM.md §3.1).
        val mfs = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        if (dedup.contains(mfs)) {
            return PipelineOutput(emptyList(), payload, null, mfs)
        }

        val decoded = nativeCore.decodeAdvert(payload)
            ?: return PipelineOutput(emptyList(), payload, null, mfs)   // short or CRC-failing
        dedup.record(mfs)

        val flag = classifier.classify(decoded)
        if (flag == ReadingFlag.INVALID) {
            return PipelineOutput(emptyList(), payload, decoded, mfs)
        }

        val readings = gridStamper.stamp(
            sourceId = sourceId,
            decoded = decoded,
            flag = flag,
            rxWallMs = raw.rxWallMs,
            tzOffsetMin = tzOffsetMinFor(raw.rxWallMs),
            rssi = raw.rssi,
        )
        return PipelineOutput(readings, payload, decoded, mfs)
    }
}
