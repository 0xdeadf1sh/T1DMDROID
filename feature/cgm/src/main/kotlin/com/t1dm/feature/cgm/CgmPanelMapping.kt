package com.t1dm.feature.cgm

import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry

/** Every field defaults to the absent or conservative answer. */
data class CgmSensorLive(
    val status: CgmSourceStatus = CgmSourceStatus.Idle,
    val rssiDbm: Int? = null,
    val telemetry: CgmSourceTelemetry? = null,
    /** This sensor is present, answering, and has never been claimed. */
    val bindable: Boolean = false,
    /** Minutes since activation, off this sensor's own newest reading. */
    val sensorAgeMin: Int? = null,
    /** The grid instant of that same reading; the expiry anchor. */
    val readingTsMs: Long? = null,
    /** Minutes of wear the sensor states, else its family's rated wear. */
    val lifetimeMin: Int? = null,
    /** Rated wear of this sensor's family; null where the family states none. */
    val ratedCycleDays: Int? = null,
    /** Its family has an activation frame; false ⇒ the control is drawn but disabled. */
    val supportsActivate: Boolean = false,
    /** Its family keeps a store on the sensor that can be asked for what the phone lacks. */
    val supportsHistory: Boolean = false,
    /** True while that ask is running; the row's Fetch-history control shows a spinner. */
    val backfillInFlight: Boolean = false,
    /** The sensor's counter stopped: its store holds nothing newer. */
    val historyExhausted: Boolean = false,
    /** §15: the live session's classified last failure; null while nothing is failing. */
    val failureNote: String? = null,
    /** Its family holds records decoded to nothing physical; search finds the right key. */
    val keyRecoverable: Boolean = false,
    /** Its family can re-date a wear whose anchor was walked forward. */
    val supportsHistoryRepair: Boolean = false,
    /** Its family provisions fresh sensors over NFC. */
    val supportsProvision: Boolean = false,
    /** Its family can seal and open frames under the live session key. */
    val supportsFrameCrypto: Boolean = false,
)

/** Pure. authoritativeId null pre-adoption; admittedIds = radio budget's active set. */
fun cgmPanelState(
    sources: List<CgmSourceDescriptor>,
    authoritativeId: String?,
    activeIds: Set<String>,
    admittedIds: Set<String>,
    live: Map<String, CgmSensorLive>,
    maxSessions: Int,
    scanning: Boolean = false,
    unidentified: Int = 0,
    unidentifiedRssiDbm: Int? = null,
    lastError: String? = null,
): CgmPanelState = CgmPanelState(
    maxSessions = maxSessions,
    scanning = scanning,
    unidentified = unidentified,
    unidentifiedRssiDbm = unidentifiedRssiDbm,
    lastError = lastError,
    // Removed sensors drop out here (registry re-upserts whole set). Authoritative always listed.
    sensors = sources.mapNotNull { d ->
        val id = d.id.value
        val authoritative = id == authoritativeId
        if (d.hidden && !authoritative) return@mapNotNull null
        val l = live[id] ?: ABSENT
        val active = authoritative || id in activeIds
        // Warmup=established, resets start+recost. Faulted=RUNNING, skip needless reconnect.
        val established = l.status == CgmSourceStatus.Live ||
            l.status == CgmSourceStatus.Warmup ||
            l.status == CgmSourceStatus.Faulted
        CgmSensorRow(
            id = id,
            name = d.displayName,
            ordinalLabel = d.ordinalLabel(),
            active = active,
            authoritative = authoritative,
            status = l.status,
            rssiDbm = l.rssiDbm,
            sensorAgeMin = l.sensorAgeMin,
            // All three must be known; a countdown from an assumed start or wear isn't justifiable.
            expiryMs = if (l.sensorAgeMin != null && l.readingTsMs != null && l.lifetimeMin != null) {
                l.readingTsMs + (l.lifetimeMin - l.sensorAgeMin).toLong() * 60_000L
            } else {
                null
            },
            warmupWindowMin = d.warmupWindowMin,
            ratedCycleDays = l.ratedCycleDays,
            telemetry = l.telemetry,
            canBind = l.bindable,
            // Enabled on has && can. Activate always drawn; the rest only when has.
            hasActivate = l.supportsActivate,
            canActivate = l.supportsActivate && !established,
            hasHistory = l.supportsHistory,
            canFetchHistory = l.supportsHistory && established && !l.historyExhausted,
            fetchingHistory = l.backfillInFlight,
            historyExhausted = l.historyExhausted,
            failureNote = l.failureNote,
            // Drawn only when decode actually failed; a reading sensor just refinds its key.
            hasRecoverKey = l.keyRecoverable,
            canRecoverKey = l.keyRecoverable,
            // Reads raw advert store, no session needed; out-of-range sensor can still re-date.
            hasRepairHistory = l.supportsHistoryRepair,
            canRepairHistory = l.supportsHistoryRepair,
            // Drawn on every sighting of a provisioning family; the tap does the rest.
            hasProvision = l.supportsProvision,
            canProvision = l.supportsProvision,
            hasFrameCrypto = l.supportsFrameCrypto,
            // A link the app wants and does not hold, whichever way it is wanted.
            canReconnect = active && !established,
            // admittedIds empty pre/post-supervision; size clause honest; else radio ranks.
            admitted = !active || activeIds.size <= maxSessions || id in admittedIds,
        )
    },
)

/** A sensor with no session; one shared instance, all defaults. */
private val ABSENT = CgmSensorLive()
