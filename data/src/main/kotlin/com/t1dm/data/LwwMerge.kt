package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.SampleEntity

/**
 * A neutral wide-sample patch used to fold a server-originated row (WS `sample` event or REST
 * `GET /v1/series` catch-up) into the local wide `sample` table. Series absent from the source are
 * `null` and never clobber a local value (gap preservation); [updatedAt] carries the server's
 * `updated_at` for last-writer-wins. `:sync` maps its wire DTO onto this so the merge stays free of
 * any transport type. The server row carries no BG provenance/flag, so `:sync` reconstructs
 * MEASURED/NORMAL when a bg is present (documented reconstruction — provenance is lost on the
 * round-trip through the server's narrower schema).
 */
data class SamplePatch(
    val ts: Long,
    val tzOffsetMin: Int,
    val updatedAt: Long,
    val bgMgdl: Int? = null,
    val bgProvenance: ReadingProvenance? = null,
    val bgFlag: ReadingFlag? = null,
    val carbsG: Double? = null,
    val bolusU: Double? = null,
    val basalU: Double? = null,
    val steps: Int? = null,
    val mood: Int? = null,
    val hr: Int? = null,
    val sleep: Int? = null,
    val exercise: Int? = null,
)

/**
 * Last-writer-wins reconciliation of a server [SamplePatch] against the local row, per 5-min bucket
 * on `updated_at` (SPEC.private.md §3.5 / server-integration memory). The phone is the sole writer,
 * so a catch-up row is the phone's own earlier push reflected back; LWW at bucket granularity, with
 * per-field gap preservation, keeps not-yet-synced local fields intact.
 */
object LwwMerge {

    /** The merged row to write, or `null` when the local row is newer-or-equal (no write). */
    fun merge(existing: SampleEntity?, patch: SamplePatch): SampleEntity? {
        if (existing == null) return materialize(patch)
        // Strictly-older-or-equal server write loses; a genuine tie is a no-op either way.
        if (patch.updatedAt <= existing.updatedAt) return null
        return existing.copy(
            tzOffsetMin = patch.tzOffsetMin,
            bgMgdl = patch.bgMgdl ?: existing.bgMgdl,
            bgProvenance = patch.bgProvenance ?: existing.bgProvenance,
            bgFlag = patch.bgFlag ?: existing.bgFlag,
            carbsG = patch.carbsG ?: existing.carbsG,
            bolusU = patch.bolusU ?: existing.bolusU,
            basalU = patch.basalU ?: existing.basalU,
            steps = patch.steps ?: existing.steps,
            mood = patch.mood ?: existing.mood,
            hr = patch.hr ?: existing.hr,
            sleep = patch.sleep ?: existing.sleep,
            exercise = patch.exercise ?: existing.exercise,
            updatedAt = patch.updatedAt,
        )
    }

    private fun materialize(p: SamplePatch) = SampleEntity(
        ts = p.ts,
        tzOffsetMin = p.tzOffsetMin,
        bgMgdl = p.bgMgdl,
        bgProvenance = p.bgProvenance,
        bgFlag = p.bgFlag,
        carbsG = p.carbsG,
        bolusU = p.bolusU,
        basalU = p.basalU,
        steps = p.steps,
        mood = p.mood,
        hr = p.hr,
        sleep = p.sleep,
        exercise = p.exercise,
        updatedAt = p.updatedAt,
    )
}
