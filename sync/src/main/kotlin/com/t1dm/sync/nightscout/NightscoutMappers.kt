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

/** Trend is TENTHS of mg/dL/min; Nightscout arrows cut at whole units (10/20/30). Null ⇒ null. */
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

/** Only bgMgdl crosses: exercise is carb EQUIVALENT, opposite sign to a meal (§3); never carbs. */
fun SampleEntity.toNsEntry(trendTenthsPerMin: Int?): NsEntryDto? {
    val bg = bgMgdl ?: return null
    // Fail closed: `sgv` claims sensor signal a third party can't retract. 2nd of two stops.
    if (bgProvenance == ReadingProvenance.RECONSTRUCTED) return null
    // Unsnapped, so two readings contesting one slot reach the host as the two readings they are.
    val at = bgMeasuredAtMs ?: ts
    return NsEntryDto(
        sgv = bg,
        date = at,
        dateString = nsIso(at, tzOffsetMin),
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

/** Null for BASAL: T1DM basal is units DELIVERED (§3), Nightscout a RATE — errs by duration. */
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

// A treatment carries `updatedAt`, not grid-snapped `tsMs`: same-slot host-keying would drop one.

/** Best-effort: a host may overwrite `notes` — see [NightscoutClient.alreadyPosted]. */
internal fun noteWithClientId(note: String?, clientId: String): String =
    if (note.isNullOrBlank()) clientId else "$note [$clientId]"
