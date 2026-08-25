package com.t1dm.data

import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.SampleEntity

/**
 * A server row folded into the local wide `sample` table; a null series never clobbers a local
 * value. [updatedAt] is not a merge discriminator. Do NOT default [bgProvenance] to MEASURED:
 * `:sync` maps it from the wire `bg_reconstructed` flag, and a promoted span read back as sensor
 * signal could clear an alarm and feed a dose.
 */
data class SamplePatch(
    val ts: Long,
    val tzOffsetMin: Int,
    val updatedAt: Long,
    val bgMgdl: Int? = null,
    val bgSource: String? = null,
    val bgProvenance: ReadingProvenance? = null,
    val bgFlag: ReadingFlag? = null,
    val steps: Int? = null,
    val mood: Int? = null,
    val hr: Int? = null,
    val sleep: Int? = null,
    /** Grams of carbohydrate equivalent (`SPEC/invariants.md` §3). */
    val exercise: Double? = null,
)

/**
 * Fills ONLY the fields the local row lacks (§3.3), never overwriting a present local value, and
 * compares no `updated_at`, so clock skew cannot let a server echo win. Local
 * `tzOffsetMin`/`updatedAt` are preserved.
 */
object SampleGapFill {

    /** Null when the patch adds nothing the local row lacked. */
    fun fill(existing: SampleEntity?, patch: SamplePatch): SampleEntity? {
        if (existing == null) return materialize(patch)
        val merged = existing.copy(
            bgMgdl = existing.bgMgdl ?: patch.bgMgdl,
            bgSource = existing.bgSource ?: patch.bgSource,
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

    private fun materialize(p: SamplePatch) = SampleEntity(
        ts = p.ts,
        tzOffsetMin = p.tzOffsetMin,
        bgMgdl = p.bgMgdl,
        // The SERVER's label — never the currently authoritative sensor's, which may not have
        // produced this reading, and never null, which the re-push would clear on the server.
        bgSource = p.bgSource,
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
