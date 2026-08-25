package com.t1dm.sync.nightscout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `device` / `enteredBy` on everything this bridge writes. */
const val NS_DEVICE = "T1DMDROID"

/** NOT `SyncJson`: its `classDiscriminator = "type"` would collide with [NsEntryDto]'s own `type`
 *  field, and the two contracts are unrelated. */
internal val NsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * [sgv] is mg/dL, the storage unit on both sides (`SPEC/invariants.md` §3), so nothing is converted.
 * [date] is epoch-ms and is what the receiver dedupes and orders on; [dateString] is the same instant
 * rendered at the phone's own UTC offset.
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
 * Nightscout has no CURVE: `carbs`/`insulin` are bare amounts and the gamma/Bateman parameters are
 * dropped, which is why this bridge is one-way. [created_at] is snake_case because the wire is;
 * [notes] carries the phone's `client_id` so a retry can recognise a treatment it already posted.
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

/** A meal is carbohydrate with no insulin and a bolus insulin with no carbohydrate — separate
 *  events here. */
object NsEventType {
    const val CARBS = "Carb Correction"
    const val BOLUS = "Correction Bolus"
}
