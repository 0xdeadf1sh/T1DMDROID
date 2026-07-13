package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.SampleEntity

/**
 * A neutral wide-sample patch used to fold a server-originated row (WS `sample` event or REST
 * `GET /v1/series` catch-up) into the local wide `sample` table. Series absent from the source are
 * `null` and never clobber a local value (gap preservation). [updatedAt] carries the row's
 * `updated_at` as authored by the phone and stored verbatim by the server; it is NOT a merge
 * discriminator (see [SampleGapFill]) — only the timestamp stamped on a materialized server-only
 * bucket. `:sync` maps its wire DTO onto this so the merge stays free of any transport type. The
 * server row carries no BG provenance/flag, so `:sync` reconstructs MEASURED/NORMAL when a bg is
 * present (provenance is lost on the round-trip through the server's narrower schema).
 */
data class SamplePatch(
    val ts: Long,
    val tzOffsetMin: Int,
    val updatedAt: Long,
    val bgMgdl: Int? = null,
    val bgProvenance: ReadingProvenance? = null,
    val bgFlag: ReadingFlag? = null,
    val steps: Int? = null,
    val mood: Int? = null,
    val hr: Int? = null,
    val sleep: Int? = null,
    val exercise: Int? = null,
)

/**
 * No-server-over-local reconciliation of a server [SamplePatch] against the local `sample` row
 * (SPEC.private.md §3.3). The phone is the sole read-write author, so a catch-up/WS row is only the
 * phone's own earlier push reflected back; presence gap-fill fills ONLY fields the local row lacks
 * (`existing.x ?: patch.x`) and NEVER overwrites a present local value. Unlike the retired LWW merge
 * this has no clock dependency — it compares no `updated_at`, so a cross-clock skew can never let a
 * server echo win over local truth (decisions #2/#3). Local `tzOffsetMin`/`updatedAt` are preserved.
 */
object SampleGapFill {

    /** The gap-filled row to write, or `null` when the patch adds nothing the local row lacked. */
    fun fill(existing: SampleEntity?, patch: SamplePatch): SampleEntity? {
        if (existing == null) return materialize(patch)
        val merged = existing.copy(
            bgMgdl = existing.bgMgdl ?: patch.bgMgdl,
            bgProvenance = existing.bgProvenance ?: patch.bgProvenance,
            bgFlag = existing.bgFlag ?: patch.bgFlag,
            steps = existing.steps ?: patch.steps,
            mood = existing.mood ?: patch.mood,
            hr = existing.hr ?: patch.hr,
            sleep = existing.sleep ?: patch.sleep,
            exercise = existing.exercise ?: patch.exercise,
        )
        return if (merged == existing) null else merged
    }

    /** A bucket present only on the server: materialize it verbatim (nothing local to preserve). */
    private fun materialize(p: SamplePatch) = SampleEntity(
        ts = p.ts,
        tzOffsetMin = p.tzOffsetMin,
        bgMgdl = p.bgMgdl,
        bgProvenance = p.bgProvenance,
        bgFlag = p.bgFlag,
        steps = p.steps,
        mood = p.mood,
        hr = p.hr,
        sleep = p.sleep,
        exercise = p.exercise,
        updatedAt = p.updatedAt,
    )
}
