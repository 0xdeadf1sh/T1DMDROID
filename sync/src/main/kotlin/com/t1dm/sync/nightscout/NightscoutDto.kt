package com.t1dm.sync.nightscout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `device` / `enteredBy` on everything this bridge writes. */
const val NS_DEVICE = "T1DMDROID"

/** No classDiscriminator: `type` is NsEntryDto's own field. Also encodes [OutboxRequest]. */
internal val NsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** sgv is mg/dL both sides (SPEC/invariants.md §3); date is the dedup/order key, not dateString. */
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

/** No curve params on Nightscout (bridge is one-way); notes carries client_id so retries dedupe. */
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

/** A meal is carbs with no insulin; a bolus is insulin with no carbs — separate events. */
object NsEventType {
    const val CARBS = "Carb Correction"
    const val BOLUS = "Correction Bolus"
}
