package com.t1dm.sync.nightscout

import com.t1dm.data.db.DoseKind
import com.t1dm.data.db.LoggedDoseEntity
import com.t1dm.data.db.LoggedMealEntity
import com.t1dm.data.db.SampleEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * An instant at the phone's own UTC offset, ISO-8601 with the offset spelled out.
 *
 * The offset is carried rather than normalised to Z so a logbook that renders `created_at` verbatim
 * shows the wall-clock time the event happened at, across a timezone change — the same reason
 * `tz_offset` rides every T1DM record (`SPEC/invariants.md` §2).
 */
fun nsIso(tsMs: Long, tzOffsetMin: Int): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(tsMs), ZoneOffset.ofTotalSeconds(tzOffsetMin * 60))
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/**
 * Trend → Nightscout's `direction` enum.
 *
 * The stored trend is TENTHS of mg/dL per minute (`CgmReadingEntity.trendTenthsPerMin`); Nightscout's
 * arrows are cut at whole mg/dL/min — 1, 2 and 3 — so the thresholds here are 10, 20 and 30. Getting
 * that factor wrong is silent: every reading would render as a double arrow and still look like data.
 *
 * A null trend yields null rather than `Flat`. "Flat" is a claim that the glucose is not moving,
 * which is not what an absent trend says.
 */
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

/**
 * A grid slot → one `entries` element, or null when the slot holds no BG.
 *
 * ONLY `bgMgdl` crosses. The other scalars on the row are either meaningless to a Nightscout reader
 * or actively dangerous in it: `exercise` is grams of carbohydrate EQUIVALENT (`SPEC/invariants.md`
 * §3) — a disposal term whose sign is opposite to a meal's — and putting it anywhere near a `carbs`
 * field would have the logbook read a bout of exercise as food eaten.
 */
fun SampleEntity.toNsEntry(trendTenthsPerMin: Int?): NsEntryDto? {
    val bg = bgMgdl ?: return null
    return NsEntryDto(
        sgv = bg,
        date = ts,
        dateString = nsIso(ts, tzOffsetMin),
        direction = nsDirection(trendTenthsPerMin),
        utcOffset = tzOffsetMin,
    )
}

/** A logged meal → a `Carb Correction`. The appearance curve does not survive; see [NsTreatmentDto]. */
fun LoggedMealEntity.toNsTreatment(): NsTreatmentDto = NsTreatmentDto(
    eventType = NsEventType.CARBS,
    created_at = nsIso(updatedAt, tzOffsetMin),
    carbs = grams,
    notes = noteWithClientId(note, clientId),
    utcOffset = tzOffsetMin,
)

/**
 * A logged dose → a `Correction Bolus`, or null for a BASAL one.
 *
 * BASAL returns null deliberately. A T1DM basal record is units DELIVERED (`SPEC/invariants.md` §3),
 * an amount; Nightscout's basal model is a RATE with a duration. There is no mapping between them
 * that is not a factor-of-duration error waiting to happen, and an amount posted into a rate field
 * would misreport total insulin — so this bridge declines rather than guesses.
 */
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

/**
 * Why a bridged treatment carries `updatedAt` and not `tsMs`.
 *
 * `tsMs` is grid-snapped: a meal and the bolus taken with it — the commonest pairing there is — land
 * on ONE five-minute instant. A Nightscout-compatible host keys treatments by timestamp, so the pair
 * collides and the second is discarded with a 200, losing a record while reporting success. `updatedAt`
 * is the unsnapped wall clock at the moment of logging, which separates them and is in any case the
 * time a logbook is asking for.
 *
 * The grid still governs everything the grid is for: BG entries key on it, and it remains the event's
 * authoritative time in the phone's own record and on `T1DMSERVER`. Only the mirrored copy differs,
 * by under half a slot. Two events logged inside the same SECOND would still collide; nothing here
 * prevents that, and by hand it does not arise.
 */

/** The phone's `client_id`, appended to whatever the user wrote. Best-effort only: a host is free to
 *  overwrite `notes` with its own text, and this one does — see [NightscoutClient.alreadyPosted]. */
internal fun noteWithClientId(note: String?, clientId: String): String =
    if (note.isNullOrBlank()) clientId else "$note [$clientId]"
