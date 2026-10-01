package com.t1dm.calc

import com.t1dm.core.model.BackendId

/** lastMeasuredTsMs null = no MEASURED reading. interpolatedFraction: INTERPOLATED/WARMUP share. */
data class AnchorInfo(
    val lastMeasuredTsMs: Long?,
    val anchorTsMs: Long,
    val currentBgMgdl: Double?,
    val interpolatedFraction: Double,
    val warmup: Boolean,
) {
    fun ageMs(nowMs: Long): Long? = lastMeasuredTsMs?.let { nowMs - it }
}

/** From logged doses only (§3.6-F). [bolusIobU] null ⇒ unknown, which fails the IOB rail closed. */
data class IobSnapshot(
    /** Bolus and basal. */
    val iobU: Double?,
    val cobG: Double,
    /** MAX(MIN(tsMs,loggedAtMs)), not MAX(tsMs): a dragged-forward dose mustn't quiet the rail. */
    val lastLoggedDoseTsMs: Long?,
    /** What the IOB ceiling reads; basal on board runs to tens of units at steady state. */
    val bolusIobU: Double? = iobU,
) {
    fun minSinceLastDose(nowMs: Long): Long? = lastLoggedDoseTsMs?.let { (nowMs - it) / 60_000L }
}

data class BackendInfo(
    val backend: BackendId,
) {
    /** §3.6-E: only fp32 XNNPACK CPU authority drives a dose; StubBackend/baseline both refuse. */
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
