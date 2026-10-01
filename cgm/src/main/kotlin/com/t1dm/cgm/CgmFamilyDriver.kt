package com.t1dm.cgm

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Shared, fixed-null; no per-session state. Null = cannot report, panel omits row. */
internal val NO_TELEMETRY: StateFlow<CgmSourceTelemetry?> = MutableStateFlow(null)

/** Shared, for the reason [NO_TELEMETRY] is. Constantly false: nothing to offer, no control. */
internal val NOT_BINDABLE: StateFlow<Boolean> = MutableStateFlow(false)

/** Shared: no family reports a backfill round trip, so no spinner. */
internal val NOT_BACKFILLING: StateFlow<Boolean> = MutableStateFlow(false)

/** Shared, for the reason [NOT_BACKFILLING] is: nothing failing, nothing to show. */
internal val NOT_FAILED: StateFlow<String?> = MutableStateFlow(null)

/** Nothing declared, so nothing offered. */
internal val NO_FAMILY_FACTS = CgmFamilyFacts()

/** Heard sensors, no claim flag yet; reported not listed since unknown may be another's. */
data class UnidentifiedSightings(val count: Int = 0, val bestRssiDbm: Int? = null) {
    fun merge(other: UnidentifiedSightings) = UnidentifiedSightings(
        count = count + other.count,
        bestRssiDbm = listOfNotNull(bestRssiDbm, other.bestRssiDbm).maxOrNull(),
    )
}

/** Shared, for the reason [NO_TELEMETRY] is. Constantly null: nothing learned, nothing written. */
internal val NO_DECLARED_WARMUP: StateFlow<Int?> = MutableStateFlow(null)

/** Shared, for the reason [NO_TELEMETRY] is. Constantly null: this sensor states no wear. */
internal val NO_STATED_LIFETIME: StateFlow<Int?> = MutableStateFlow(null)

/** Shared, for the reason [NO_TELEMETRY] is. Constantly null: minFromStart dates the start. */
internal val NO_SENSOR_START: StateFlow<Long?> = MutableStateFlow(null)

internal const val MINUTES_PER_DAY = 1440

/** One coordinator holds every family's sessions; one authoritative source app-wide. */
interface ConnectedCgmSession : CgmSource {
    /** dBm, or null before the first poll. */
    val rssi: StateFlow<Int?>

    /** Display only; null before first record or if family reports nothing beyond glucose. */
    val telemetry: StateFlow<CgmSourceTelemetry?> get() = NO_TELEMETRY

    /** Whether requestBind would act now; no CgmSourceStatus covers present+connected+unclaimed. */
    val bindable: StateFlow<Boolean> get() = NOT_BINDABLE

    /** Minutes just declared by this sensor, null till then; not republished on reconnect. */
    val declaredWarmupWindowMin: StateFlow<Int?> get() = NO_DECLARED_WARMUP

    /** Minutes of wear this sensor states for itself, null till it has. */
    val statedLifetimeMin: StateFlow<Int?> get() = NO_STATED_LIFETIME

    /** Wall-clock ms of minFromStart 0, for a family whose counter can stop while it measures. */
    val sensorStartMs: StateFlow<Long?> get() = NO_SENSOR_START

    /** True while a [requestBackfill] round trip is running; drives the panel's spinner. */
    val backfillInFlight: StateFlow<Boolean> get() = NOT_BACKFILLING

    /** §15: this session's classified last failure; null while nothing is failing. */
    val failure: StateFlow<String?> get() = NOT_FAILED

    /** The sensor's counter stopped while it measures: its store holds nothing newer. */
    val historyExhausted: StateFlow<Boolean> get() = NOT_BACKFILLING

    fun start()
    fun close()

    /** UI-only signal-lost transition; alarm path fires independently on a wall-clock tick. */
    fun markSignalLost()

    fun requestActivate() = Unit

    /** IRREVERSIBLE and user-only; never reached from a reconnect or any automatic path. */
    fun requestBind() = Unit

    /** Pulls sensor-store history to its retention floor; user-only, auto pull never re-asks. */
    fun requestBackfill() = Unit
}

/** Family capability flags; every field defaults conservative so no surface overclaims. */
data class CgmFamilyFacts(
    /** Rated days, or null if undeclared; the countdown's wear when the sensor states none. */
    val ratedCycleDays: Int? = null,
    val supportsActivate: Boolean = false,
    /** The sensor keeps its own store and [ConnectedCgmSession.requestBackfill] can ask it. */
    val supportsHistory: Boolean = false,
    /** [CgmFamilyDriver.repairHistory] can re-date one of this family's wears. */
    val supportsHistoryRepair: Boolean = false,
    /** The family provisions its own sensors over NFC; the panel offers the tap. */
    val supportsProvision: Boolean = false,
    /** The console can seal and open frames under the live session key. */
    val supportsFrameCrypto: Boolean = false,
)

data class CgmFamilyCandidate(
    /** For a never-met sensor; one on record keeps its own tuned warm-up window. */
    val descriptor: CgmSourceDescriptor,
    /** False if unreadable here (unbound or bound elsewhere); still listed, never authoritative. */
    val adoptable: Boolean,
)

/** AiDEX lists paired and heard devices; CT5 has no bond, must hear an advertisement. */
interface CgmFamilyDriver {
    val vendorId: String

    val facts: CgmFamilyFacts get() = CgmFamilyFacts()

    /** Total; off adapter yields none. active/connected are the app's own reading sources. */
    suspend fun candidates(active: Set<CgmSourceId>, connected: Set<CgmSourceId>): List<CgmFamilyCandidate>

    /** Null when that sensor is not reachable right now. The caller starts it. */
    suspend fun createSession(descriptor: CgmSourceDescriptor, scope: CoroutineScope): ConnectedCgmSession?

    /** Minutes of wear this sensor stated in an earlier session; null when none is held. */
    suspend fun storedLifetimeMin(id: CgmSourceId): Int? = null

    /** [ConnectedCgmSession.sensorStartMs] as an earlier session left it; null when none held. */
    suspend fun storedSensorStartMs(id: CgmSourceId): Long? = null

    /** Only evidence reach=read; reported when the session goes live, not on cancel/end. */
    suspend fun sensorReadable(descriptor: CgmSourceDescriptor) = Unit

    /** Counterpart to sensorReadable; build-failure and cancellation both reach neither. */
    suspend fun sessionYieldedNothing(descriptor: CgmSourceDescriptor) = Unit

    /** User search; next candidates() sweeps and every per-sensor hold-off lifts. Any thread. */
    fun rescan() = Unit

    /** Rebuild this sensor's link: drops its hold-offs and cached handle. Any thread. */
    fun forget(descriptor: CgmSourceDescriptor) = Unit

    /** What this family hears and cannot enumerate right now. Any thread. */
    fun unidentified(): UnidentifiedSightings = UnidentifiedSightings()

    /** Whether enough evidence is held to search for a key this app derived wrong. */
    fun canRecoverKey(descriptor: CgmSourceDescriptor): Boolean = false

    /** Session torn down first, rebuilt after either way; true only if evidence names one key. */
    suspend fun recoverKey(descriptor: CgmSourceDescriptor): Boolean = false

    /** DESTRUCTIVE: re-dates a wear from arrival witnesses, drops readings under old anchor. */
    suspend fun repairHistory(descriptor: CgmSourceDescriptor): Boolean = false
}

/** Paired and heard devices; an unpaired sensor pairs on its first F001 notify (CGM.md §4). */
class AidexXFamilyDriver(
    private val bonded: BondedAidexDevices,
    private val repository: CgmRepository,
    private val session: AidexSession,
    private val nowMs: () -> Long,
    private val transportFactory: (BluetoothDevice, CgmSensorLog) -> AidexGattTransport,
    /** Flow not scanner, injected; keeps the rationing testable on host. */
    private val discover: () -> Flow<AidexAdvertisedDevice> = { emptyFlow() },
    /** Handle for a stored address; null off-device or while Bluetooth is off. */
    private val deviceAt: (String) -> BluetoothDevice? = { null },
    private val logs: CgmSensorLogs? = null,
) : CgmFamilyDriver {

    override val vendorId: String = VENDOR_ID

    /** No rated wear: the countdown waits for the sensor's own device info. */
    override val facts = CgmFamilyFacts(supportsActivate = true, supportsHistory = true)

    /** Newest sighting per serial; the handle an unpaired sensor is dialled on. */
    private val heard = ConcurrentHashMap<String, AidexAdvertisedDevice>()

    /** Last address per serial a session derived a key on; loaded from storage on first ask. */
    private val knownAddress = ConcurrentHashMap<String, String>()

    /** Serials whose last session gave nothing before a key; their address waits for a scan. */
    private val addressDistrusted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Serials whose current session derived a key; its later drop is range, not a bad handle. */
    private val authenticated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Per serial: next dial allowed, and the gap that set it. Read for a paired handle only. */
    private val dialHoldOff = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** Raised by rescan, consumed by the sweep it asks for. */
    private val userRequested = AtomicBoolean(false)

    @Volatile
    private var lastSweepMs: Long? = null

    /** Doubled by a sweep that hears no wanted sensor, reset by one that does, or by [rescan]. */
    @Volatile
    private var sweepGapMs: Long = SWEEP_GAP_MS

    override suspend fun candidates(
        active: Set<CgmSourceId>,
        connected: Set<CgmSourceId>,
    ): List<CgmFamilyCandidate> {
        val paired = bonded.all()
        val pairedSerials = paired.mapTo(HashSet()) { it.serial }
        if (userRequested.getAndSet(false)) {
            lastSweepMs = nowMs()
            sweep(USER_SWEEP_MS, until = null)
        } else {
            val wanted = HashSet<String>()
            for (id in active) {
                val serial = id.value.substringAfter(':')
                if (id !in connected && serial !in pairedSerials && !reachable(serial)) wanted += serial
            }
            val last = lastSweepMs
            val now = nowMs()
            if (wanted.isNotEmpty() && (last == null || now - last >= sweepGapMs)) {
                lastSweepMs = now
                val got = sweep(WANTED_SWEEP_MS, until = wanted)
                sweepGapMs = if (wanted.any { it in got }) {
                    SWEEP_GAP_MS
                } else {
                    (sweepGapMs * 2).coerceAtMost(MAX_SWEEP_GAP_MS)
                }
            }
        }
        return listing(
            paired = paired.associate { it.serial to it.name },
            heard = heard.values.associate { it.serial to it.name },
        )
    }

    private suspend fun reachable(serial: String): Boolean =
        heard.containsKey(serial) || reconnectAddress(serial) != null

    /** Every sighting lands in [heard]; ends at [windowMs], or once all of [until] is heard. */
    private suspend fun sweep(windowMs: Long, until: Set<String>?): Set<String> {
        val flow = runCatching { discover() }.getOrElse { e ->
            Log.w(TAG, "cannot scan: ${e.message}")
            return emptySet()
        }
        val got = HashSet<String>()
        val outcome = runCatching {
            withTimeoutOrNull(windowMs) {
                flow.first { dev ->
                    if (heard.put(dev.serial, dev) == null) Log.i(TAG, "heard ${dev.serial} at ${dev.rssi} dBm")
                    logs?.advertLog(sourceIdFor(dev.serial))?.advert(dev.raw, dev.rssi, "adv ${dev.name} ${dev.rssi} dBm")
                    got += dev.serial
                    until != null && got.containsAll(until)
                }
            }
        }
        outcome.exceptionOrNull()?.let { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "scan ended early: ${e.message}")
        }
        return got
    }

    override suspend fun createSession(
        descriptor: CgmSourceDescriptor,
        scope: CoroutineScope,
    ): ConnectedCgmSession? {
        val serial = serialOf(descriptor)
        val paired = bonded.forSerial(serial)?.device
        // A paired handle dials whether or not the sensor is in range.
        if (paired != null && dialHeldOff(serial)) return null
        val device = paired
            ?: heard[serial]?.device
            ?: reconnectAddress(serial)?.let(deviceAt)
            ?: return null
        authenticated -= serial
        val log = logs.forSensor(descriptor.id)
        val via = when {
            paired != null -> "paired handle"
            heard[serial] != null -> "heard handle"
            else -> "stored address"
        }
        log.i(TAG, "dialling $serial on its $via")
        return AidexXConnectedSource(
            descriptor = descriptor,
            serial = serial,
            transport = transportFactory(device, log),
            session = session,
            repository = repository,
            scope = scope,
            nowMs = nowMs,
            onAuthenticated = { authenticatedOn(serial, device.address) },
            log = log,
        )
    }

    override suspend fun storedLifetimeMin(id: CgmSourceId): Int? = repository.loadSensorLifetimeMin(id)

    /** Only a failure before a key blames the handle; a keyed session's drop is range. */
    override suspend fun sessionYieldedNothing(descriptor: CgmSourceDescriptor) {
        val serial = serialOf(descriptor)
        if (authenticated.remove(serial)) return
        heard -= serial
        val log = logs.forSensor(descriptor.id)
        if (addressDistrusted.add(serial)) log.i(TAG, "$serial gave no session; its handle waits for a scan")
        val gap = holdOffDials(serial)
        log.i(TAG, "$serial gave no key; a paired handle is not dialled again for ${gap / 1000} s")
    }

    internal fun dialHeldOff(serial: String, now: Long = nowMs()): Boolean =
        dialHoldOff[serial]?.first?.let { now < it } == true

    private fun holdOffDials(serial: String): Long {
        val gap = ((dialHoldOff[serial]?.second ?: 0L) * 2).coerceIn(DIAL_HOLD_OFF_MIN_MS, DIAL_HOLD_OFF_MAX_MS)
        dialHoldOff[serial] = (nowMs() + gap) to gap
        return gap
    }

    override fun rescan() {
        userRequested.set(true)
        lastSweepMs = null
        sweepGapMs = SWEEP_GAP_MS
        dialHoldOff.clear()
    }

    /** Drops the sighting, the address distrust and the dial hold-off; address and pairing stay. */
    override fun forget(descriptor: CgmSourceDescriptor) {
        val serial = serialOf(descriptor)
        heard -= serial
        addressDistrusted -= serial
        dialHoldOff -= serial
        lastSweepMs = null
    }

    /** Withheld while distrusted; only a session that derives a key on it again restores it. */
    internal suspend fun reconnectAddress(serial: String): String? {
        if (serial in addressDistrusted) return null
        knownAddress[serial]?.let { return it }
        val stored = repository.loadSensorAddress(sourceIdFor(serial)) ?: return null
        return knownAddress.putIfAbsent(serial, stored) ?: stored
    }

    /** A failed address write costs one scan after the next restart, never this session. */
    internal suspend fun authenticatedOn(serial: String, address: String) {
        authenticated += serial
        addressDistrusted -= serial
        dialHoldOff -= serial
        if (knownAddress.put(serial, address) == address) return
        try {
            repository.saveSensorAddress(sourceIdFor(serial), address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logs.forSensor(sourceIdFor(serial)).w(TAG, "the address for $serial was not stored: ${e.javaClass.simpleName}")
        }
    }

    internal companion object {
        const val TAG = "CgmScan"
        const val VENDOR_ID = "aidexx"

        fun sourceIdFor(serial: String) = CgmSourceId("$VENDOR_ID:$serial")

        fun serialOf(descriptor: CgmSourceDescriptor): String =
            descriptor.serialSuffix ?: descriptor.id.value.substringAfter(':')

        /** Pure. Paired: adoptable. Heard only: listed, never adopted; a tap reads it. */
        fun listing(paired: Map<String, String>, heard: Map<String, String>): List<CgmFamilyCandidate> =
            paired.map { (serial, name) ->
                CgmFamilyCandidate(AidexXConnectedSource.descriptorFor(serial, name), adoptable = true)
            } + heard.filterKeys { it !in paired }.map { (serial, name) ->
                CgmFamilyCandidate(AidexXConnectedSource.descriptorFor(serial, name), adoptable = false)
            }

        /** A never-started sensor is heard within 3 s; Search waits on every family in turn. */
        const val USER_SWEEP_MS = 30_000L

        /** Ceiling; spans the 1–2 min idle advert interval, ends once all wanted are heard. */
        const val WANTED_SWEEP_MS = 120_000L

        const val SWEEP_GAP_MS = 60_000L

        const val MAX_SWEEP_GAP_MS = 10 * 60_000L

        /** Doubles per keyless session; a sensor back in range waits at most the cap. */
        const val DIAL_HOLD_OFF_MIN_MS = 30_000L

        const val DIAL_HOLD_OFF_MAX_MS = 5 * 60_000L
    }
}

/** Scan-discovered, bind-claimed; radio rationed by sweep interval and rescan back-off. */
class Ct5FamilyDriver(
    private val repository: CgmRepository,
    private val session: Ct5Session,
    private val nowMs: () -> Long,
    /** Flow not scanner, injected; keeps this class Android-free and testable on host. */
    private val discover: () -> Flow<Ct5AdvertisedDevice>,
    private val transportFactory: (BluetoothDevice, CoroutineScope, CgmSensorLog) -> Ct5GattTransport,
    /** Window length; locked OS flush is ~5min, must outlast a flush or hears nothing. */
    private val screenOn: () -> Boolean = { true },
    /** Adoption source for a sensor bound elsewhere; injected, defaults to nothing pending. */
    private val importSource: Ct5ImportSource? = null,
    /** Handle for a stored address; null off-device or while Bluetooth is off. */
    private val deviceAt: (String) -> BluetoothDevice? = { null },
    private val logs: CgmSensorLogs? = null,
) : CgmFamilyDriver {

    override val vendorId: String = Ct5Constants.VENDOR_ID

    /** Rated wear = written at bind; no activation/reset, claim is once and irreversible. */
    override val facts = CgmFamilyFacts(
        ratedCycleDays = Ct5Constants.CYCLE_DAYS,
        supportsHistory = true,
        supportsHistoryRepair = true,
    )

    /** Concurrent: written from the supervisor coroutine and each session, in parallel. */
    private val seen = ConcurrentHashMap<String, Sighting>()

    /** heardAtMs ages the handle; recordedAtMs marks which scan window produced it. */
    private class Sighting(val dev: Ct5AdvertisedDevice, val heardAtMs: Long, val recordedAtMs: Long)

    /** Next-allowed instant and escalated gap, per sensor; cleared by any sighting. */
    private val rescanGate = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** Next wait for a heard-but-unreadable sensor; unlike rescanGate, only a reading clears it. */
    private val unreadableGate = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** BSNs whose session reached ACCEPTED checkID; a later drop is ordinary at ~-80 dBm. */
    private val authenticated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Newest address per BSN, heard or authenticated on; a CT5 unit's address is static. */
    private val knownAddress = ConcurrentHashMap<String, String>()

    /** Touched only from [candidates], on the coordinator's one supervisor coroutine. */
    private var importChecked = false

    /** Null until first sweep ('never swept' is a state); cleared by rescan from any thread. */
    @Volatile
    private var lastDiscoveryMs: Long? = null

    /** Doubled by a sweep that hears nothing, reset by one that does, or by [rescan]. */
    @Volatile
    private var sweepIntervalMs: Long = DISCOVERY_INTERVAL_MS

    /** Undecodable frames per BSN, newest last; session rebuilds every N bad records instead. */
    private val undecodable = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()

    /** BSNs with a held secret; affirmative-only, dropped via releaseSecret on a lost key. */
    private val holdsSecret: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every BSN ever sighted, never TTL-evicted; eviction would make a stationary one look new. */
    private val everHeard: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** BSNs already logged heard-but-unenumerable, so the refusal logs once, not every tick. */
    private val refusalLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Raised by rescan, consumed by the sweep it asks for; uses userSweepWindowMs. */
    private val userRequested = AtomicBoolean(false)

    override suspend fun candidates(
        active: Set<CgmSourceId>,
        connected: Set<CgmSourceId>,
    ): List<CgmFamilyCandidate> {
        val now = nowMs()
        importPendingSensor()
        evictStale(now, connected)
        val last = lastDiscoveryMs
        if (last == null || now - last >= sweepInterval(active, connected)) {
            // Read BEFORE the stamp below, or every sweep looks like a later one.
            val firstOfThisProcess = last == null
            lastDiscoveryMs = now
            // Rationing keys on LEARNING something, not on hearing it — see [newsworthy].
            val knownBefore = HashSet(everHeard)
            val window =
                if (userRequested.getAndSet(false)) userSweepWindowMs() else sweepWindowMs(firstOfThisProcess)
            val heard = scan(targetBsn = null, timeoutMs = window) {
                newsworthy(it, active, connected, knownBefore)
            }
            sweepIntervalMs = if (heard) {
                DISCOVERY_INTERVAL_MS
            } else {
                (sweepIntervalMs * 2).coerceAtMost(IDLE_DISCOVERY_INTERVAL_MS)
            }
        }
        return seen.values.mapNotNull { sighting ->
            val dev = sighting.dev
            val adoptable = adoptable(dev.bsn)
            // Somebody else's sensor stays unenumerated but stays in seen, which rationing reads.
            if (!belongsOnRecord(dev.bound, adoptable)) {
                // Once per sensor, else 'heard but never shown' looks like 'never heard'.
                if (refusalLogged.add(dev.bsn)) {
                    val why = if (dev.bound == null) {
                        "no advertisement has carried its bind flag yet"
                    } else {
                        "the air says it is claimed and no key is held here"
                    }
                    Log.i(TAG, "${dev.bsn} is heard but not listed — $why")
                }
                return@mapNotNull null
            }
            refusalLogged -= dev.bsn
            CgmFamilyCandidate(
                descriptor = Ct5ConnectedSource.descriptorFor(dev.bsn, dev.name),
                adoptable = adoptable,
            )
        }
    }

    /** Base interval while a wanted sensor is unreachable (its recovery channel). */
    private fun sweepInterval(active: Set<CgmSourceId>, connected: Set<CgmSourceId>): Long {
        if (active.any { it !in connected }) return DISCOVERY_INTERVAL_MS
        val reachableIdle = seen.values.any {
            actionable(it.dev, active) && sourceIdFor(it.dev.bsn) !in connected
        }
        val chosen =
            if (!reachableIdle && connected.isNotEmpty()) IDLE_DISCOVERY_INTERVAL_MS else sweepIntervalMs
        // Interval must clear the locked sweep window, or sweeps run back to back.
        return if (screenOn()) chosen else maxOf(chosen, lockedSweepIntervalMs())
    }

    /** Locked sweep spacing must exceed its own window, or the radio never stops. */
    private fun lockedSweepIntervalMs(): Long = Ct5Constants.LOCKED_SWEEP_MS + LOCKED_SWEEP_GAP_MS

    /** Locked window outlasts one flush, except the first sweep of a process, kept short. */
    internal fun sweepWindowMs(firstOfThisProcess: Boolean = false): Long =
        if (screenOn() || firstOfThisProcess) DISCOVERY_SWEEP_MS else Ct5Constants.LOCKED_SWEEP_MS

    /** User search window; idle sensor advertises every 8s, bind flag may be missed. */
    internal fun userSweepWindowMs(): Long =
        if (screenOn()) Ct5Constants.SCAN_TIMEOUT_MS else Ct5Constants.LOCKED_SWEEP_MS

    /** A ceiling; the scan ends the moment the sensor it names is heard. */
    internal fun targetedScanMs(): Long =
        if (screenOn()) Ct5Constants.SCAN_TIMEOUT_MS else Ct5Constants.LOCKED_SCAN_TIMEOUT_MS

    /** A sighting older than this says nothing of range now; batched reports land a flush late. */
    internal fun handleTtlMs(): Long =
        if (screenOn()) HANDLE_TTL_MS else HANDLE_TTL_MS + Ct5Constants.BATCHED_HANDLE_GRACE_MS

    private fun actionable(dev: Ct5AdvertisedDevice, active: Set<CgmSourceId>): Boolean =
        radioWorthwhile(dev.bound, wanted = sourceIdFor(dev.bsn) in active)

    /** Whether the sweep learned anything (resets back-off): unlinked-wanted or new. */
    private fun newsworthy(
        dev: Ct5AdvertisedDevice,
        active: Set<CgmSourceId>,
        connected: Set<CgmSourceId>,
        knownBefore: Set<String>,
    ): Boolean {
        val id = sourceIdFor(dev.bsn)
        return sweepLearnedSomething(
            bound = dev.bound,
            // Except while unreadableGate refuses it: the fresh handle would go unused.
            wanted = id in active && !rationed(dev.bsn),
            connected = id in connected,
            alreadyKnown = dev.bsn in knownBefore,
        )
    }

    /** Evicts sightings past SEEN_TTL_MS; a connected sensor is never evicted but still ages. */
    private fun evictStale(now: Long, connected: Set<CgmSourceId>) {
        seen.entries.removeAll {
            now - it.value.heardAtMs >= SEEN_TTL_MS && sourceIdFor(it.key) !in connected
        }
    }

    /** Logs what arrival witnesses say about dating: offset (fixable) vs drift. No action. */
    internal suspend fun logAnchorDiagnosis(descriptor: CgmSourceDescriptor) {
        val bsn = bsnOf(descriptor)
        val log = logs.forSensor(descriptor.id)
        val d = Ct5AnchorRepair.diagnose(repository.advertArrivals(descriptor.id))
        if (d == null) {
            log.i(TAG, "anchor for $bsn: too few arrivals to say anything")
            return
        }
        val held = repository.loadSensorSecret(descriptor.id)?.let { Ct5SensorState.decode(it) }
        val heldAnchor = held?.bindTimeMs
        val offBy = heldAnchor?.let { (it - d.anchor) / 1000 }
        log.i(
            TAG,
            "anchor for $bsn: held=$heldAnchor witnessed=${d.anchor} offBy=${offBy}s " +
                "over ${d.witnesses} arrivals, early-to-late skew ${d.skewMs / 1000}s",
        )
    }

    internal suspend fun adoptable(bsn: String): Boolean {
        if (bsn in holdsSecret) return true
        val held = repository.loadSensorSecret(sourceIdFor(bsn)) != null
        if (held) holdsSecret += bsn
        return held
    }

    /** Called on a session that clears its own stored secret; syncs holdsSecret to match. */
    internal fun releaseSecret(bsn: String) {
        holdsSecret -= bsn
    }

    /** Once per process; never overwrites a held secret, the only unbind-password copy. */
    private suspend fun importPendingSensor() {
        val source = importSource ?: return
        if (importChecked) return
        importChecked = true
        val text = runCatching { source.read() }.getOrNull() ?: return
        val import = Ct5StateImport.parse(text)
        if (import == null) {
            // Document stays in place; no reason logged, it would name key-material fields.
            Log.w(TAG, "an import document is present but is not one this build will act on")
            return
        }
        val id = sourceIdFor(import.bsn)
        if (repository.loadSensorSecret(id) != null) {
            Log.i(TAG, "${import.bsn} is already readable here; the import document is discarded unused")
        } else {
            repository.saveSensorSecret(id, import.state.encode())
            holdsSecret += import.bsn
            Log.i(TAG, "adopted ${import.bsn}, bound elsewhere; it can now be selected")
        }
        runCatching { source.consume() }
    }

    internal fun keepUndecodableForTest(bsn: String, frame: ByteArray) = keepUndecodable(bsn, frame)

    internal fun noteAuthenticatedForTest(bsn: String) {
        unreadableGate -= bsn
        authenticated += bsn
    }

    internal fun rationed(bsn: String, now: Long = nowMs()): Boolean =
        unreadableGate[bsn]?.first?.let { now < it } == true

    private fun arm(gate: ConcurrentHashMap<String, Pair<Long, Long>>, bsn: String): Long {
        val now = nowMs()
        val prior = gate[bsn]
        if (prior != null && now < prior.first) return prior.second
        val gap = ((prior?.second ?: 0L) * 2).coerceIn(RESCAN_MIN_GAP_MS, RESCAN_MAX_GAP_MS)
        gate[bsn] = (now + gap) to gap
        return gap
    }

    /** The one thing that lifts [unreadableGate]; see there for why a sighting must not. */
    override suspend fun sensorReadable(descriptor: CgmSourceDescriptor) {
        unreadableGate -= bsnOf(descriptor)
    }

    override suspend fun storedSensorStartMs(id: CgmSourceId): Long? =
        repository.loadSensorSecret(id)?.let { Ct5SensorState.decode(it) }?.bindTimeMs

    /** Rations a sensor that linked but gave nothing; except AWAITING_BIND, keeps Bind offered. */
    override suspend fun sessionYieldedNothing(descriptor: CgmSourceDescriptor) {
        val bsn = bsnOf(descriptor)
        if (!adoptable(bsn)) return
        // Gate-clear here: checkID passed then link dropped, weak-link loss, not unreadable.
        if (authenticated.remove(bsn)) return
        val gap = arm(unreadableGate, bsn)
        logs.forSensor(descriptor.id).i(TAG, "$bsn was reachable but reported nothing; not trying it again for ${gap / 1000} s")
    }

    private fun bsnOf(descriptor: CgmSourceDescriptor): String =
        descriptor.serialSuffix ?: descriptor.id.value.substringAfter(':')

    /** Bind flag never arrived, no key overrides; a distant sensor misses its scan response. */
    override fun unidentified(): UnidentifiedSightings {
        val open = seen.values.filter { sightingIsUnidentified(it.dev.bound, it.dev.bsn in holdsSecret) }
        return UnidentifiedSightings(open.size, open.maxOfOrNull { it.dev.rssi })
    }

    private fun keepUndecodable(bsn: String, frame: ByteArray) {
        val held = undecodable.computeIfAbsent(bsn) { ArrayDeque() }
        synchronized(held) {
            held.addLast(frame)
            while (held.size > Ct5KeySearch.MAX_FRAMES) held.removeFirst()
        }
    }

    override fun canRecoverKey(descriptor: CgmSourceDescriptor): Boolean =
        undecodable[bsnOf(descriptor)]?.size?.let { it >= Ct5KeySearch.MIN_FRAMES } == true

    /** Searches held frames for the right key, persists it; needs exactly one match. */
    override suspend fun recoverKey(descriptor: CgmSourceDescriptor): Boolean {
        val bsn = bsnOf(descriptor)
        val log = logs.forSensor(descriptor.id)
        val held = undecodable[bsn] ?: return false
        val frames = synchronized(held) { held.toList() }
        if (frames.size < Ct5KeySearch.MIN_FRAMES) return false
        val recovered = Ct5KeySearch.recover(frames.map { session.pushUnderEveryKey(it) })
        if (recovered == null) {
            log.w(TAG, "key search over ${frames.size} frames from $bsn named no single key")
            return false
        }
        val blob = repository.loadSensorSecret(descriptor.id) ?: return false
        val current = Ct5SensorState.decode(blob) ?: return false
        if (current.cipherId == recovered) {
            log.i(TAG, "the key held for $bsn is already the one the records decode under")
            return false
        }
        repository.saveSensorSecret(descriptor.id, current.copy(cipherId = recovered).encode())
        // A wrong key doesn't block storage: garbage can decode as an ordinary row, drops too.
        val dropped = repository.deleteReadingsForSource(descriptor.id)
        repository.saveSourceCursor(descriptor.id, 0)
        synchronized(held) { held.clear() }
        log.i(TAG, "key recovered for $bsn from ${frames.size} records; $dropped rows the old key wrote dropped")
        return true
    }

    /** Fail-closed order: nothing deletes until the anchor is agreed and persisted (crash-safe). */
    override suspend fun repairHistory(descriptor: CgmSourceDescriptor): Boolean {
        val bsn = bsnOf(descriptor)
        val log = logs.forSensor(descriptor.id)
        val anchor = Ct5AnchorRepair.anchorFrom(repository.advertArrivals(descriptor.id))
        if (anchor == null) {
            log.w(TAG, "no anchor could be agreed for $bsn; nothing was deleted")
            return false
        }
        val blob = repository.loadSensorSecret(descriptor.id) ?: return false
        val current = Ct5SensorState.decode(blob) ?: return false
        val moved = current.bindTimeMs - anchor
        // A walked anchor is always ahead of truth; repair only moves it back, never forward.
        if (moved < Ct5Constants.SAMPLE_INTERVAL_MS) {
            log.i(
                TAG,
                "history for $bsn left alone: the witnesses put the anchor ${moved / 1000}s from the one held, " +
                    "which is not a repair",
            )
            return false
        }
        repository.saveSensorSecret(descriptor.id, current.copy(bindTimeMs = anchor).encode())
        val dropped = repository.deleteReadingsForSource(descriptor.id)
        repository.saveSourceCursor(descriptor.id, 0)
        log.i(TAG, "history repaired for $bsn: anchor moved back ${moved / 1000}s, $dropped rows dropped")
        return true
    }

    override fun rescan() {
        userRequested.set(true)
        lastDiscoveryMs = null
        sweepIntervalMs = DISCOVERY_INTERVAL_MS
        rescanGate.clear()
        unreadableGate.clear()
    }

    /** Drops the hold-offs and the sighting; the known address stays, a unit never changes it. */
    override fun forget(descriptor: CgmSourceDescriptor) {
        val bsn = bsnOf(descriptor)
        rescanGate -= bsn
        unreadableGate -= bsn
        seen -= bsn
    }

    override suspend fun createSession(
        descriptor: CgmSourceDescriptor,
        scope: CoroutineScope,
    ): ConnectedCgmSession? {
        val bsn = descriptor.serialSuffix ?: descriptor.id.value.substringAfter(':')
        val log = logs.forSensor(descriptor.id)
        val known = reconnectAddress(bsn)?.let(deviceAt)
        val sighted = if (known == null) handleFor(bsn) ?: return null else seen[bsn]?.dev
        val device = known ?: sighted?.device ?: return null
        val secret = repository.loadSensorSecret(descriptor.id)?.let { blob ->
            Ct5SensorState.decode(blob) ?: run {
                // Left ON DISK, untouched: it is the only copy of an unbind password.
                log.w(TAG, "the stored secret for $bsn is not a blob this build understands")
                null
            }
        }
        log.i(
            TAG,
            "dialling $bsn on its ${if (known != null) "stored address" else "fresh sighting"}; " +
                "key ${if (secret != null) "held" else "absent"}, air says bound=${sighted?.bound}",
        )
        // Once per session, acted on by nothing; only account of offset-vs-drift dating.
        logAnchorDiagnosis(descriptor)
        return Ct5ConnectedSource(
            descriptor = descriptor,
            bsn = bsn,
            transport = transportFactory(device, scope, log),
            session = session,
            repository = repository,
            scope = scope,
            initialState = secret,
            // Air's tri-state bind flag: fresh/offer-bind vs claimed-elsewhere vs unsaid.
            advertisedBound = sighted?.bound,
            nowMs = nowMs,
            onSecretReleased = ::releaseSecret,
            onUndecodable = { frame -> keepUndecodable(bsn, frame) },
            onAuthenticated = { authenticatedOn(bsn, device.address) },
            log = log,
        )
    }

    /** A held key's sensor is dialled at its last address, however old; no scan first. */
    internal suspend fun reconnectAddress(bsn: String): String? {
        if (rationed(bsn) || !adoptable(bsn)) return null
        knownAddress[bsn]?.let { return it }
        val stored = repository.loadSensorAddress(sourceIdFor(bsn)) ?: return null
        return knownAddress.putIfAbsent(bsn, stored) ?: stored
    }

    /** A failed address write costs one scan after the next restart, never this session. */
    internal suspend fun authenticatedOn(bsn: String, address: String) {
        unreadableGate -= bsn
        authenticated += bsn
        knownAddress[bsn] = address
        try {
            repository.saveSensorAddress(sourceIdFor(bsn), address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logs.forSensor(sourceIdFor(bsn)).w(TAG, "the address for $bsn was not stored: ${e.javaClass.simpleName}")
        }
    }

    internal fun noteAddress(bsn: String, address: String) {
        knownAddress[bsn] = address
    }

    /** First connect: rescans on aged sighting; dialling an absent sensor burns the watchdog. */
    internal suspend fun handleFor(bsn: String): Ct5AdvertisedDevice? {
        val startedAt = nowMs()
        // Checked before cache: held-by-another is heard every sweep, gate below never fires.
        if (rationed(bsn, startedAt)) return null
        val cached = seen[bsn]
        if (cached != null && startedAt - cached.heardAtMs < handleTtlMs()) return cached.dev
        // No targeted rescan while locked; a 2nd BATCH client resets buffer, starves flush.
        if (!screenOn()) return null
        val gate = rescanGate[bsn]
        if (gate != null && startedAt < gate.first) return null
        scan(targetBsn = bsn, timeoutMs = targetedScanMs())
        // Both needed: delivered by this scan (not stale-young), heard recently (not buffered-old).
        val fresh = seen[bsn]?.takeIf {
            it.recordedAtMs >= startedAt && nowMs() - it.heardAtMs < handleTtlMs()
        }
        if (fresh == null) {
            val gap = arm(rescanGate, bsn)
            logs.forSensor(sourceIdFor(bsn)).i(TAG, "$bsn was not heard fresh enough to connect on; holding off for ${gap / 1000} s")
            return null
        }
        rescanGate -= bsn
        return fresh.dev
    }

    /** Every sighting merges into seen; returns whether counts() matched the merged sighting. */
    private suspend fun scan(
        targetBsn: String?,
        timeoutMs: Long,
        counts: (Ct5AdvertisedDevice) -> Boolean = { true },
    ): Boolean {
        val flow = runCatching { discover() }.getOrElse { e ->
            Log.w(TAG, "cannot scan: ${e.message}")
            return false
        }
        var heard = false
        // Scan ending is ordinary (adapter off, scan-limit, stream done); fails soft.
        val outcome = runCatching {
            withTimeoutOrNull(timeoutMs) {
                if (targetBsn == null) {
                    // A full sweep: hear everything the window allows, then stop on the timeout.
                    flow.collect { if (counts(record(it))) heard = true }
                } else {
                    flow.first { if (counts(record(it))) heard = true; it.bsn == targetBsn }
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

    /** Newest HANDLE always, but never a forgotten bind flag — see [mergedFlag]. */
    private fun record(dev: Ct5AdvertisedDevice): Ct5AdvertisedDevice {
        val prior = seen[dev.bsn]?.dev
        val merged = if (prior == null) {
            dev
        } else {
            dev.copy(
                bound = mergedFlag(dev.bound, prior.bound),
                running = mergedFlag(dev.running, prior.running),
            )
        }
        val recordedAtMs = nowMs()
        seen[dev.bsn] = Sighting(merged, heardAtMs = dev.heardAtMs, recordedAtMs = recordedAtMs)
        noteAddress(dev.bsn, dev.device.address)
        val firstEver = everHeard.add(dev.bsn)
        rescanGate -= dev.bsn
        logs?.advertLog(sourceIdFor(dev.bsn))?.advert(
            dev.raw,
            dev.rssi,
            "adv ${dev.name} ${dev.rssi} dBm bound=${dev.bound} running=${dev.running} " +
                "heard ${(recordedAtMs - dev.heardAtMs) / 1000} s before delivery",
        )
        // Logs two moments only: meeting a sensor, learning its offerable flag; never repeats.
        if (firstEver || (prior?.bound == null && merged.bound != null)) {
            Log.i(TAG, "heard ${dev.bsn} at ${dev.rssi} dBm, bind flag ${merged.bound ?: "not advertised yet"}")
        }
        return merged
    }

    internal companion object {
        const val TAG = "CgmScan"

        fun sourceIdFor(bsn: String) = CgmSourceId("${Ct5Constants.VENDOR_ID}:$bsn")

        /** No manufacturer block = no fact; must not erase a prior true (re-offers owned). */
        fun mergedFlag(fresh: Boolean?, prior: Boolean?): Boolean? = fresh ?: prior

        /** Worth radio time if wanted or air says unclaimed; not keyed on secret. */
        fun radioWorthwhile(bound: Boolean?, wanted: Boolean): Boolean = bound == false || wanted

        /** Whether a sweep may reset back-off; pure for host truth tests. A link isn't a find. */
        fun sweepLearnedSomething(
            bound: Boolean?,
            wanted: Boolean,
            connected: Boolean,
            alreadyKnown: Boolean,
        ): Boolean = !connected && (wanted || (bound == false && !alreadyKnown))

        /** Air-claimed with no local secret = another client's; enumerating would leak a serial. */
        fun belongsOnRecord(bound: Boolean?, adoptable: Boolean): Boolean = adoptable || bound == false

        /** Report, not list, only unknown-flag refusals; a claimed-flag refusal is normal. */
        fun sightingIsUnidentified(bound: Boolean?, adoptable: Boolean): Boolean =
            bound == null && !adoptable

        /** Long on purpose: identity doesn't change; re-enumerates more than radio should wake. */
        const val DISCOVERY_INTERVAL_MS = 5 * 60_000L

        /** One advert suffices; a fresh sensor advertises hard, idle one only every 8s. */
        const val DISCOVERY_SWEEP_MS = 15_000L

        /** Floor once nothing to look for; still found within 30min, inside 45min warm-up. */
        const val IDLE_DISCOVERY_INTERVAL_MS = 30 * 60_000L

        /** How long a sighting counts as the sensor being in range. */
        const val HANDLE_TTL_MS = 60_000L

        /** Generous (idle advertises every 8s) but finite, so a gone sensor eventually leaves. */
        const val SEEN_TTL_MS = 20 * 60_000L

        /** Idle gap between locked sweeps; else radio scanned continuously while asleep. */
        const val LOCKED_SWEEP_GAP_MS = 60_000L

        /** First wait after a targeted rescan hears nothing; matches vendor's flat 30s rescan. */
        const val RESCAN_MIN_GAP_MS = 30_000L

        /** Caps at one doubling; end-of-wear costs a minute's listen per minute till stopped. */
        const val RESCAN_MAX_GAP_MS = 60_000L
    }
}

internal suspend fun ConnectedCgmSession.awaitSignalLost(onEverLive: () -> Unit) {
    status.first { st ->
        // A faulting sensor is still readable; rationing would hide the fault being watched.
        if (st == CgmSourceStatus.Live || st == CgmSourceStatus.Warmup || st == CgmSourceStatus.Faulted) {
            onEverLive()
        }
        st == CgmSourceStatus.SignalLost
    }
}
