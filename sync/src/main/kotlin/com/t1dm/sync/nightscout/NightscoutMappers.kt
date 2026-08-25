package com.t1dm.sync.nightscout

import com.t1dm.core.model.ReadingProvenance
import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.SampleEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** ISO-8601 at the phone's own offset, not normalised to Z (`SPEC/invariants.md` §2). */
fun nsIso(tsMs: Long, tzOffsetMin: Int): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(tsMs), ZoneOffset.ofTotalSeconds(tzOffsetMin * 60))
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/** Trend is TENTHS of mg/dL per minute; Nightscout's arrows cut at whole units, hence 10/20/30.
 *  A null trend yields null, not `Flat`. */
fun nsDirection(trendTenthsPerMin: Int?): String? = when {
    trendTenthsPerMin == null -> null
    trendTenthsPerMin >= 30 -> "DoubleUp"
    trendTenthsPerMin >= 20 -> "SingleUp"
    trendTenthsPerMin >= 10 -> "FortyFiveUp"
    trendTenthsPerMin > -10 -> "Flat"
    trendTenthsPerMin > -20 -> "FortyFiveDown"
    trendTenthsPerMin > -30 -> "SingleDown"
    else -> "DoubleDown"
}

/** Only `bgMgdl` crosses: `exercise` is carbohydrate EQUIVALENT, opposite in sign to a meal
 *  (`SPEC/invariants.md` §3), and must never reach a `carbs` field. */
fun SampleEntity.toNsEntry(trendTenthsPerMin: Int?): NsEntryDto? {
    val bg = bgMgdl ?: return null
    // Fail closed: `sgv` claims sensor signal, and a third party has no route to take a record back
    // out. Second of two stops; promotion files no bridge row either.
    if (bgProvenance == ReadingProvenance.RECONSTRUCTED) return null
    return NsEntryDto(
        sgv = bg,
        date = ts,
        dateString = nsIso(ts, tzOffsetMin),
        direction = nsDirection(trendTenthsPerMin),
        utcOffset = tzOffsetMin,
    )
}

/** The appearance curve does not survive; see [NsTreatmentDto]. */
fun LoggedMealEntity.toNsTreatment(): NsTreatmentDto = NsTreatmentDto(
    eventType = NsEventType.CARBS,
    created_at = nsIso(updatedAt, tzOffsetMin),
    carbs = grams,
    notes = noteWithClientId(note, clientId),
    utcOffset = tzOffsetMin,
)

/** Null for BASAL: a T1DM basal is units DELIVERED (`SPEC/invariants.md` §3), Nightscout's is a RATE
 *  with a duration — no mapping between them without a factor-of-duration error. */
fun LoggedDoseEntity.toNsTreatment(): NsTreatmentDto? {
    if (kind != DoseKind.BOLUS) return null
    return NsTreatmentDto(
        eventType = NsEventType.BOLUS,
        created_at = nsIso(updatedAt, tzOffsetMin),
        insulin = units,
        notes = noteWithClientId(note, clientId),
        utcOffset = tzOffsetMin,
    )
}

// A treatment carries `updatedAt`, not grid-snapped `tsMs`: a meal and its bolus land on one slot,
// and a host keying treatments by timestamp discards the second with a 200.

/** Best-effort: a host may overwrite `notes` — see [NightscoutClient.alreadyPosted]. */
internal fun noteWithClientId(note: String?, clientId: String): String =
    if (note.isNullOrBlank()) clientId else "$note [$clientId]"
