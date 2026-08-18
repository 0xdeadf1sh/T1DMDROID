package com.t1dm.sync.nightscout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `device` / `enteredBy` on everything this bridge writes, so a record's origin is legible in the
 *  receiving logbook and a retry can recognise its own earlier POST. */
const val NS_DEVICE = "T1DMDROID"

/**
 * Nightscout JSON — deliberately NOT `SyncJson`.
 *
 * Two reasons, either sufficient. `SyncJson` sets `classDiscriminator = "type"` for the `/v1/stream`
 * event hierarchy, and [NsEntryDto] carries a genuine `type` field of its own that the receiver keys
 * on. And the two contracts are unrelated: a change made for T1DMSERVER's wire has no business
 * silently re-shaping what a third party receives.
 */
internal val NsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * One `POST /api/v1/entries` element — a CGM reading.
 *
 * `sgv` is mg/dL, which is the storage unit on both sides (`SPEC/invariants.md` §3), so nothing is
 * converted here; the receiver renders mmol/L itself if that is what its owner has configured.
 * [date] is epoch-ms and is what the receiver dedupes and orders on; [dateString] is the same instant
 * rendered at the phone's own UTC offset, so the logbook shows the time the reading was taken in.
 */
@Serializable
data class NsEntryDto(
    val sgv: Int,
    val date: Long,
    val dateString: String,
    val direction: String? = null,
    val type: String = "sgv",
    val device: String = NS_DEVICE,
    val utcOffset: Int = 0,
)

/**
 * One `POST /api/v1/treatments` element — a logged meal or bolus.
 *
 * Nightscout has no concept of an appearance or action CURVE: `carbs` and `insulin` are bare amounts.
 * The gamma/Bateman parameters that make a T1DM event self-describing (`gi`, `k`, `theta`,
 * `duration_min`, `ka/ke`) have nowhere to go and are dropped — which is why this bridge is one-way
 * and never a source the phone reads back from.
 *
 * [created_at] is snake_case because the wire is; [notes] carries the phone's `client_id` so a retry
 * after a lost ack can recognise a treatment it already posted.
 */
@Serializable
data class NsTreatmentDto(
    val eventType: String,
    val created_at: String,
    val carbs: Double? = null,
    val insulin: Double? = null,
    val notes: String? = null,
    val enteredBy: String = NS_DEVICE,
    val utcOffset: Int = 0,
)

/** Nightscout careportal event types this bridge emits. A meal is carbohydrate with no insulin
 *  attached (the two are separate events here), and a bolus is insulin with no carbohydrate. */
object NsEventType {
    const val CARBS = "Carb Correction"
    const val BOLUS = "Correction Bolus"
}
