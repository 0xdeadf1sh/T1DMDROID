package com.t1dm.cgm

import android.util.Log
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scan-discovered on the service uuid; adoption waits on NFC provisioning, and a GATT session on
 * the pairing phases, so a sighting is everything this driver can offer so far.
 */
class Libre3FamilyDriver(
    private val repository: CgmRepository,
    private val nowMs: () -> Long,
    /** Flow not scanner, injected; keeps this class Android-free and testable on host. */
    private val discover: () -> Flow<Libre3AdvertisedDevice>,
    /** Phase-4 pairing stack; null (default) keeps createSession null — see [PairingStack]. */
    private val pairing: PairingStack? = null,
    private val logs: CgmSensorLogs? = null,
) : CgmFamilyDriver {

    /**
     * PLAN_T1DMDROID.md §4: everything a pairing session needs beyond the stored sensor state.
     * Address-keyed, so [BluetoothDevice] stays in the app wiring and this class stays
     * Android-free; the app container supplies the transport, the tables dir and the native seam.
     */
    class PairingStack(
        val native: Libre3Native,
        /** Resolved per session; null (adapter off, §9 tables not pushed) refuses the session. */
        val tablesDir: () -> String?,
        /** Transport for a stored address; null = unreachable right now. */
        val transportAt: (address: String, scope: CoroutineScope, log: CgmSensorLog) -> Libre3GattTransport?,
    )

    override val vendorId: String = VENDOR_ID

    /** The patch keeps a store; requestBackfill runs Libre3DataStream.backfillRound over it. */
    override val facts = CgmFamilyFacts(
        ratedCycleDays = RATED_CYCLE_DAYS_EU,
        supportsProvision = true,
        supportsHistory = true,
        supportsFrameCrypto = true,
    )

    /** Newest sighting per address; the address is the only identity held so far. */
    private val seen = ConcurrentHashMap<String, Libre3AdvertisedDevice>()

    /** Addresses with a secret on record; affirmative-only, refreshed from storage on first ask. */
    private val holdsSecret: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Null until first sweep ('never swept' is a state); cleared by rescan from any thread. */
    @Volatile
    private var lastDiscoveryMs: Long? = null

    /** Doubled by a sweep that hears nothing, reset by one that does, or by [rescan]. */
    @Volatile
    private var sweepIntervalMs: Long = DISCOVERY_INTERVAL_MS

    /** Raised by rescan, consumed by the sweep it asks for. */
    private val userRequested = AtomicBoolean(false)

    /** Next-allowed instant and gap per address, armed by a session that gave nothing. */
    private val unreachableGate = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** §2 east-positive minutes at event time; the Ct5 supplier, injectable for tests. */
    private val tzOffsetMinFor: (Long) -> Int =
        { ms -> java.util.TimeZone.getDefault().getOffset(ms) / 60_000 }

    override suspend fun candidates(
        active: Set<CgmSourceId>,
        connected: Set<CgmSourceId>,
    ): List<CgmFamilyCandidate> {
        val now = nowMs()
        evictStale(now)
        val last = lastDiscoveryMs
        if (last == null || now - last >= sweepIntervalMs) {
            lastDiscoveryMs = now
            val window = if (userRequested.getAndSet(false)) USER_SWEEP_MS else DISCOVERY_SWEEP_MS
            val heard = sweep(window)
            sweepIntervalMs = if (heard) {
                DISCOVERY_INTERVAL_MS
            } else {
                (sweepIntervalMs * 2).coerceAtMost(IDLE_DISCOVERY_INTERVAL_MS)
            }
        }
        return seen.values.map { dev ->
            CgmFamilyCandidate(descriptorFor(dev.address), adoptable = adoptable(dev.address))
        }
    }

    /** A secret on record — provisioned here — is what makes a sighting enumerable. */
    internal suspend fun adoptable(address: String): Boolean {
        if (address in holdsSecret) return true
        val held = repository.loadSensorSecret(sourceIdFor(address)) != null
        if (held) holdsSecret += address
        return held
    }

    /**
     * PLAN_T1DMDROID.md §4: the pairing lifecycle for a provisioned (adoptable) sensor. The
     * stored state is the gate — undecodable blobs stay on disk untouched (the only copy), the
     * §9 tables dir is re-checked per session, and pairing-stack absence (host tests, or the app
     * wiring pre-phase-4) keeps the registry from building a link at all.
     */
    override suspend fun createSession(
        descriptor: CgmSourceDescriptor,
        scope: CoroutineScope,
    ): ConnectedCgmSession? {
        val stack = pairing ?: return null
        val address = addressOf(descriptor)
        if (heldOff(address)) return null
        val log = logs.forSensor(descriptor.id)
        val blob = repository.loadSensorSecret(descriptor.id) ?: return null
        val sensor = Libre3SensorState.decode(blob) ?: run {
            log.w(TAG, "the stored secret for $address is not a blob this build understands")
            return null
        }
        val tables = stack.tablesDir() ?: run {
            log.i(TAG, "pairing for $address refused: the §9 tables dir is not available")
            return null
        }
        val transport = stack.transportAt(address, scope, log) ?: run {
            log.i(TAG, "no transport for $address (adapter off?)")
            return null
        }
        log.i(TAG, "dialling ${sensor.serial} at $address")
        // Phase 5: the session owns ingestion too — repository, clock and tz supplier ride in.
        return Libre3ConnectedSource(
            descriptor = descriptor,
            transport = transport,
            native = stack.native,
            tablesDir = tables,
            sensor = sensor,
            repository = repository,
            scope = scope,
            nowMs = nowMs,
            tzOffsetMinFor = tzOffsetMinFor,
            log = log,
        )
    }

    /** The patch-info wear NFC provisioning stored; an unreadable blob states nothing. */
    override suspend fun storedLifetimeMin(id: CgmSourceId): Int? =
        repository.loadSensorSecret(id)?.let { Libre3SensorState.decode(it) }?.statedLifetimeMin

    internal fun heldOff(address: String, now: Long = nowMs()): Boolean =
        unreachableGate[address]?.first?.let { now < it } == true

    /** Each empty session doubles the wait, [HOLD_OFF_MIN_MS] up to [HOLD_OFF_MAX_MS]. */
    override suspend fun sessionYieldedNothing(descriptor: CgmSourceDescriptor) {
        val address = addressOf(descriptor)
        val now = nowMs()
        val prior = unreachableGate[address]
        if (prior != null && now < prior.first) return
        val gap = ((prior?.second ?: 0L) * 2).coerceIn(HOLD_OFF_MIN_MS, HOLD_OFF_MAX_MS)
        unreachableGate[address] = (now + gap) to gap
        logs.forSensor(descriptor.id).i(TAG, "$address gave no session; not dialing it again for ${gap / 1000} s")
    }

    override suspend fun sensorReadable(descriptor: CgmSourceDescriptor) {
        unreachableGate -= addressOf(descriptor)
    }

    /** An advert means the sensor is in range again. */
    internal fun sighted(address: String) {
        unreachableGate -= address
    }

    override fun rescan() {
        userRequested.set(true)
        lastDiscoveryMs = null
        sweepIntervalMs = DISCOVERY_INTERVAL_MS
        unreachableGate.clear()
    }

    /** Drops the sighting and the hold-off. */
    override fun forget(descriptor: CgmSourceDescriptor) {
        val address = addressOf(descriptor)
        seen -= address
        unreachableGate -= address
    }

    /** A sighting older than this says nothing of range now; batched reports land a flush late. */
    private fun evictStale(now: Long) {
        seen.entries.removeAll { now - it.value.heardAtMs >= SEEN_TTL_MS }
    }

    /** Every sighting lands in [seen]; returns whether anything was heard in the window. */
    private suspend fun sweep(windowMs: Long): Boolean {
        val flow = runCatching { discover() }.getOrElse { e ->
            Log.w(TAG, "cannot scan: ${e.message}")
            return false
        }
        var heard = false
        // Scan ending is ordinary (adapter off, scan-limit, stream done); fails soft.
        val outcome = runCatching {
            withTimeoutOrNull(windowMs) {
                flow.collect { dev ->
                    heard = true
                    sighted(dev.address)
                    val first = seen.put(dev.address, dev) == null
                    if (first) Log.i(TAG, "heard ${dev.address} at ${dev.rssi} dBm")
                    logs?.advertLog(sourceIdFor(dev.address))?.advert(
                        dev.raw,
                        dev.rssi,
                        "adv ${dev.rssi} dBm heard ${(nowMs() - dev.heardAtMs) / 1000} s before delivery",
                    )
                }
            }
        }
        outcome.exceptionOrNull()?.let { e ->
            // The one throw that must NOT be swallowed: this coroutine's own cancellation.
            if (e is CancellationException) throw e
            Log.w(TAG, "scan ended early: ${e.message}")
        }
        return heard
    }

    internal companion object {
        const val TAG = "CgmScan"

        const val VENDOR_ID: String = "libre3"

        /** The sensor family; the scope displayed history spans. */
        const val MODEL_ID: String = "libre3:3plus"

        const val BRAND: String = "Libre 3"

        /** PLAN_T1DMDROID.md §5.1: the primary data service, also the scan filter. */
        val SERVICE_UUID: UUID = UUID.fromString("089810CC-EF89-11E9-81B4-2A2AE2DBCCE4")

        /** PLAN_T1DMDROID.md §5.7: warmup = 60 min default from currentLifeCount. */
        const val WARMUP_WINDOW_MIN: Int = 60

        /** EU 3 Plus: NFC patch-info reports 21600 min (live 2026-09-24), not 14 d. */
        const val RATED_CYCLE_DAYS_EU: Int = 15

        fun sourceIdFor(address: String) = CgmSourceId("$VENDOR_ID:$address")

        fun addressOf(descriptor: CgmSourceDescriptor): String =
            descriptor.serialSuffix ?: descriptor.id.value.substringAfter(':')

        /** Colon-free tail of the address: all a sighting can show before provisioning. */
        fun descriptorFor(address: String): CgmSourceDescriptor =
            CgmSourceDescriptor(
                id = sourceIdFor(address),
                vendorId = VENDOR_ID,
                sensorModelId = MODEL_ID,
                advertName = null,
                displayName = "$BRAND ${address.filter { it != ':' }.takeLast(4)}",
                serialSuffix = address,
                warmupWindowMin = WARMUP_WINDOW_MIN,
                passiveOnly = false,
            )

        /** Long on purpose: identity doesn't change; re-enumerates more than radio should wake. */
        const val DISCOVERY_INTERVAL_MS = 5 * 60_000L

        /** Generous while the sensor's own rate is unmeasured; ends on the first sighting. */
        const val DISCOVERY_SWEEP_MS = 15_000L

        /** User search; same window the AidexX family waits on. */
        const val USER_SWEEP_MS = 30_000L

        /** Floor once nothing to look for; still found inside an hour. */
        const val IDLE_DISCOVERY_INTERVAL_MS = 30 * 60_000L

        /** Generous but finite, so a gone sensor eventually leaves. */
        const val SEEN_TTL_MS = 20 * 60_000L

        /** First wait after an empty session; the vendor's flat 30 s rescan, as CT5. */
        const val HOLD_OFF_MIN_MS = 30_000L

        /** The discovery cadence: ~8% radio duty (a 30 s connect per 5 min) while gone. */
        const val HOLD_OFF_MAX_MS = DISCOVERY_INTERVAL_MS
    }
}

/** Public identity helper for the provisioner; the companion stays internal to :cgm. */
fun libre3SourceId(address: String) = Libre3FamilyDriver.sourceIdFor(address)
