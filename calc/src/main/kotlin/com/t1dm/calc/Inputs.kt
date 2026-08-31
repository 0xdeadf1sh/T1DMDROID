package com.t1dm.calc

import com.t1dm.core.model.BackendId
import com.t1dm.core.model.Precision

/** [lastMeasuredTsMs] null ⇒ no MEASURED reading at all. [interpolatedFraction] is the fraction of
 *  the recent anchor context that is INTERPOLATED or WARMUP. [currentBgMgdl] is the last real value. */
data class AnchorInfo(
    val lastMeasuredTsMs: Long?,
    val anchorTsMs: Long,
    val currentBgMgdl: Double?,
    val interpolatedFraction: Double,
    val warmup: Boolean,
) {
    fun ageMs(nowMs: Long): Long? = lastMeasuredTsMs?.let { nowMs - it }
}

/** From logged doses only (§3.6-F). [iobU] null ⇒ unknown, which fails the IOB rail closed. */
data class IobSnapshot(
    val iobU: Double?,
    val cobG: Double,
    /** `MAX(MIN(tsMs, loggedAtMs))` over the dose store, not `MAX(tsMs)`: a dose dragged into the
     *  present must not quiet the rail that reads it. */
    val lastLoggedDoseTsMs: Long?,
) {
    fun minSinceLastDose(nowMs: Long): Long? = lastLoggedDoseTsMs?.let { (nowMs - it) / 60_000L }
}

data class BackendInfo(
    val backend: BackendId,
    val precision: Precision,
) {
    /** §3.6-E: the fp32 XNNPACK CPU authority drives a dose and nothing else does. The StubBackend
     *  and the classical baseline both land here and both refuse. */
    val trustworthy: Boolean get() = backend == BackendId.EXECUTORCH_XNNPACK_FP32
}

/** null ⇒ no signal. */
fun interface AnchorInfoSource {
    suspend fun current(nowMs: Long): AnchorInfo?
}

/** null ⇒ store failure. */
fun interface IobSource {
    suspend fun snapshot(nowMs: Long): IobSnapshot?
}
