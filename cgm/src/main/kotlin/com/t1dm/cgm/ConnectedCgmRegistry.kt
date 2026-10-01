package com.t1dm.cgm

import android.util.Log
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Sole read path driver (§3.1); sessions only relay readings, a dropped link is loss-of-signal. */
class ConnectedCgmRegistry(
    private val repository: CgmRepository,
    private val scope: CoroutineScope,
    /** One per family. */
    private val drivers: List<CgmFamilyDriver>,
    private val logs: CgmSensorLogs? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CgmSourceRegistry {

    private val _sources = MutableStateFlow<List<CgmSourceDescriptor>>(emptyList())
    override val sources: StateFlow<List<CgmSourceDescriptor>> = _sources.asStateFlow()

    private val _activeIds = MutableStateFlow<Set<CgmSourceId>>(emptySet())
    override val activeIds: StateFlow<Set<CgmSourceId>> = _activeIds.asStateFlow()

    private val _authoritative = MutableStateFlow<CgmSourceId?>(null)
    override val authoritative: StateFlow<CgmSourceId?> = _authoritative.asStateFlow()

    private val _admittedIds = MutableStateFlow<Set<CgmSourceId>>(emptySet())

    /** [activeIds] trimmed by the radio budget; the excess is dropped silently. */
    val admittedIds: StateFlow<Set<CgmSourceId>> = _admittedIds.asStateFlow()

    private val liveSources = MutableStateFlow<Map<CgmSourceId, ConnectedCgmSession>>(emptyMap())

    /** Not flatMapLatest over liveSources; that would resubscribe and drop an in-flight reading. */
    private val _readings = MutableSharedFlow<CgmReading>(replay = 0, extraBufferCapacity = 128)

    /** Every live source, not just authoritative; the FGS narrows before the alarm engine. */
    fun readings(): Flow<CgmReading> = _readings.asSharedFlow()

    private var loop: Job? = null

    /** Wake-up so a user action skips the enumeration tick; conflated, 2 requests = 1 pass. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private sealed interface Command {
        @JvmInline value class Reconnect(val id: CgmSourceId) : Command

        /** Destructive, runs with the session torn down; rewrites anchor/cursor copies. */
        @JvmInline value class Repair(val id: CgmSourceId) : Command

        /** Destructive like [Repair]: rewrites the key, drops readings, resets the cursor. */
        @JvmInline value class RecoverKey(val id: CgmSourceId) : Command
    }

    /** Drained by the supervisor each pass; sessions torn down/rebuilt only by the jobs' owner. */
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val _recoverable = MutableStateFlow<Set<CgmSourceId>>(emptySet())

    /** Sources whose family holds enough undecodable records to search for the right key. */
    val recoverable: StateFlow<Set<CgmSourceId>> = _recoverable.asStateFlow()

    /** Tears the session down, searches for a wrongly derived key, rebuilds found or not. */
    fun recoverKey(id: CgmSourceId) {
        logs.forSensor(id).i(TAG, "key recovery requested")
        commands.trySend(Command.RecoverKey(id))
        wake.trySend(Unit)
    }

    private val _unidentified = MutableStateFlow(UnidentifiedSightings())

    /** What every family hears and cannot offer; see [UnidentifiedSightings]. */
    val unidentified: StateFlow<UnidentifiedSightings> = _unidentified.asStateFlow()

    private val _scanning = MutableStateFlow(false)

    /** Whether a user-requested search is still running; a sweep can listen up to a minute. */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Raised here and cleared by the pass that actually sweeps for it. */
    private val rescanRequested = AtomicBoolean(false)

    /** The user asked for a search: every family sweeps on the next pass, which runs now. */
    fun rescanNow() {
        drivers.forEach { it.rescan() }
        rescanRequested.set(true)
        _scanning.value = true
        wake.trySend(Unit)
    }

    /** Rebuilds this sensor's link now, overriding whatever hold-off its family has on it. */
    fun reconnect(id: CgmSourceId) {
        logs.forSensor(id).i(TAG, "reconnect requested")
        commands.trySend(Command.Reconnect(id))
        wake.trySend(Unit)
    }

    /** Pull what the sensor holds and the app lacks; a no-op off a session that cannot. */
    fun fetchHistory(id: CgmSourceId) {
        val log = logs.forSensor(id)
        val source = liveSources.value[id]
        if (source == null) {
            log.i(TAG, "fetch history for ${id.value}: no live session (button state may lag the link)")
            return
        }
        log.i(TAG, "fetch history requested")
        source.requestBackfill()
    }

    /** Null while no session is held for [id]. */
    fun sessionOf(id: CgmSourceId): ConnectedCgmSession? = liveSources.value[id]

    /** Destructive, user-pressed; re-dates a wear, drops wrong-anchor readings, rebuilds. */
    fun repairHistory(id: CgmSourceId) {
        logs.forSensor(id).i(TAG, "history repair requested")
        commands.trySend(Command.Repair(id))
        wake.trySend(Unit)
    }

    /** Cold, per-id; share as WhileSubscribed, not N observers for an unwatched screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun statusOf(id: CgmSourceId): Flow<CgmSourceStatus> =
        liveSources.flatMapLatest { it[id]?.status ?: flowOf(CgmSourceStatus.Idle) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun rssiOf(id: CgmSourceId): Flow<Int?> =
        liveSources.flatMapLatest { it[id]?.rssi ?: flowOf(null) }

    /** Display only; null for a family that reports none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun telemetryOf(id: CgmSourceId): Flow<CgmSourceTelemetry?> =
        liveSources.flatMapLatest { it[id]?.telemetry ?: NO_TELEMETRY }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun bindableOf(id: CgmSourceId): Flow<Boolean> =
        liveSources.flatMapLatest { it[id]?.bindable ?: NOT_BINDABLE }

    /** True while a session's backfill round trip runs; drives the panel's spinner. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun backfillInFlightOf(id: CgmSourceId): Flow<Boolean> =
        liveSources.flatMapLatest { it[id]?.backfillInFlight ?: NOT_BACKFILLING }

    /** True once a session saw the sensor's counter stop; no pull can fetch newer history. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun historyExhaustedOf(id: CgmSourceId): Flow<Boolean> =
        liveSources.flatMapLatest { it[id]?.historyExhausted ?: NOT_BACKFILLING }

    /** The live session's classified last failure (§15); null while nothing is failing. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun failureOf(id: CgmSourceId): Flow<String?> =
        liveSources.flatMapLatest { it[id]?.failure ?: NOT_FAILED }

    /** A vendor no driver serves cannot be read at all, so it is offered nothing. */
    fun factsFor(vendorId: String): CgmFamilyFacts = driverFor(vendorId)?.facts ?: NO_FAMILY_FACTS

    /** Minutes of wear per sensor as it stated; from storage at start, then from its sessions. */
    private val statedLifetimes = MutableStateFlow<Map<CgmSourceId, Int>>(emptyMap())

    /** Minutes the sensor states, else its family's rated wear; null when neither is known. */
    fun lifetimeMinOf(id: CgmSourceId): Flow<Int?> =
        combine(_sources, statedLifetimes) { sources, stated ->
            stated[id] ?: sources.firstOrNull { it.id == id }
                ?.let { factsFor(it.vendorId).ratedCycleDays }
                ?.let { it * MINUTES_PER_DAY }
        }.distinctUntilChanged()

    /** Per sensor, as its family holds it; from storage at start, then from its sessions. */
    private val sensorStarts = MutableStateFlow<Map<CgmSourceId, Long>>(emptyMap())

    /** Null when the family keeps none; minFromStart then dates the start. */
    fun sensorStartMsOf(id: CgmSourceId): Flow<Long?> =
        sensorStarts.map { it[id] }.distinctUntilChanged()

    override fun setAuthoritative(id: CgmSourceId) {
        _authoritative.value = id
        // Mirrored in memory; supervisor steers off _activeIds, not storage.
        _activeIds.update { it + id }
        scope.launch {
            ensureOnRecord(id)
            repository.setAuthoritative(id)
        }
    }

    override fun activate(id: CgmSourceId) {
        _activeIds.update { it + id }
        scope.launch {
            ensureOnRecord(id)
            repository.activate(id)
        }
    }

    /** activate/setAuthoritative are UPDATEs, need a row first; idempotent, preserves fields. */
    private suspend fun ensureOnRecord(id: CgmSourceId) {
        val descriptor = descriptorOf(id) ?: return
        repository.upsertSource(descriptor, authoritative = false, lastSeenMs = nowMs())
    }

    /** Refuses the authoritative source; authority may have moved onto the pressed id. */
    override fun deactivate(id: CgmSourceId) {
        if (id == _authoritative.value) return
        _activeIds.update { it - id }
        // §14 phase 6 clean disconnect: the pass runs NOW, so the link drops on the press,
        // not at the next enumeration tick (up to 30 s later — the handback's vendor app
        // needs the sensor released first, §17).
        wake.trySend(Unit)
        scope.launch { repository.deactivate(id) }
    }

    override fun authoritativeSource(): CgmSource? = _authoritative.value?.let { liveSources.value[it] }

    override fun liveSource(id: CgmSourceId): CgmSource? = liveSources.value[id]

    fun activateSensor(id: CgmSourceId) {
        logs.forSensor(id).i(TAG, "activation requested; session ${if (id in liveSources.value) "held" else "absent"}")
        liveSources.value[id]?.requestActivate()
    }

    /** Irreversible; plants the only release password. No automatic caller, user request only. */
    fun bindSensor(id: CgmSourceId) {
        logs.forSensor(id).i(TAG, "bind requested; session ${if (id in liveSources.value) "held" else "absent"}")
        liveSources.value[id]?.requestBind()
    }

    /** Descriptor must move with the column, or registerDiscovered writes back the old window. */
    fun setWarmupWindowMin(id: CgmSourceId, minutes: Int) {
        val clamped = minutes.coerceIn(CgmSourceDescriptor.WARMUP_WINDOW_RANGE)
        _sources.update { current ->
            current.map { if (it.id == id) it.copy(warmupWindowMin = clamped) else it }
        }
        scope.launch { repository.setWarmupWindowMin(id, clamped) }
    }

    /** Marked in memory/persisted; refuses authoritative, keeps secret (may still be worn). */
    fun hide(id: CgmSourceId) {
        if (id == _authoritative.value) return
        _sources.update { current ->
            current.map { if (it.id == id) it.copy(hidden = true) else it }
        }
        _activeIds.update { it - id }
        // Same clean-disconnect beat as [deactivate]: the press ends the link, not the tick.
        wake.trySend(Unit)
        scope.launch { repository.hide(id) }
    }

    /** Descriptor and last-write time per source; supervisor coroutine only, no synchronisation. */
    private val lastPersisted = HashMap<CgmSourceId, Pair<CgmSourceDescriptor, Long>>()

    /** Supervisor coroutine and hydrate only; ensureOnRecord skips this, next pass re-writes. */
    private val persistedIds = HashSet<CgmSourceId>()

    private fun descriptorOf(id: CgmSourceId): CgmSourceDescriptor? = _sources.value.firstOrNull { it.id == id }

    private fun driverFor(vendorId: String): CgmFamilyDriver? = drivers.firstOrNull { it.vendorId == vendorId }

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            hydrate()
            // Beside supervision, not before it: a slow unseal must not delay the first reading.
            launch { hydrateStored() }
            superviseSessions()
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        liveSources.value.values.forEach { it.close() }
        liveSources.value = emptyMap()
        // A stale set would have the panel report a torn-down link.
        _admittedIds.value = emptySet()
    }

    /** Must precede first connect, or a fresh sensor seizes authority (§3.1) on restart. */
    private suspend fun hydrate() {
        val persisted = repository.loadSources()
        if (persisted.isNotEmpty()) _sources.value = persisted
        persisted.mapTo(persistedIds) { it.id }
        _activeIds.value = repository.activeSourceIds().toSet()
        repository.authoritativeSourceId()?.let { _authoritative.value = it }
        // Before the first supervision pass; an unset set reads as "everything held back".
        _admittedIds.value = admitted(_activeIds.value).toSet()
    }

    /** A session's own statement, landing first, wins over storage. */
    private suspend fun hydrateStored() {
        val stored = _sources.value.mapNotNull { d -> storedLifetimeOf(d)?.let { d.id to it } }.toMap()
        statedLifetimes.update { stored + it }
        val starts = _sources.value.mapNotNull { d -> storedStartOf(d)?.let { d.id to it } }.toMap()
        sensorStarts.update { starts + it }
    }

    /** Unreadable falls back to the rated wear; it never stops the registry. */
    private suspend fun storedLifetimeOf(d: CgmSourceDescriptor): Int? =
        readStored(d, "lifetime") { storedLifetimeMin(it) }

    /** Unreadable falls back to minFromStart; it never stops the registry. */
    private suspend fun storedStartOf(d: CgmSourceDescriptor): Long? =
        readStored(d, "start") { storedSensorStartMs(it) }

    private suspend fun <T> readStored(
        d: CgmSourceDescriptor,
        what: String,
        read: suspend CgmFamilyDriver.(CgmSourceId) -> T?,
    ): T? {
        val driver = driverFor(d.vendorId) ?: return null
        return try {
            driver.read(d.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "${d.id.value}: stored $what unreadable — ${e.javaClass.simpleName}")
            null
        }
    }

    /** Sessions start/cancel from this one coroutine; jobs needs no synchronisation. */
    private suspend fun superviseSessions() {
        val jobs = HashMap<CgmSourceId, Job>()
        try {
            while (currentCoroutineContext().isActive) {
                drainCommands(jobs)
                // Read before the pass; mid-pass requests serve next, flag stays raised.
                val serving = rescanRequested.getAndSet(false)
                registerDiscovered()
                logs?.setListed(_sources.value.filterNot { it.hidden }.mapTo(HashSet()) { it.id })
                _unidentified.value = drivers.fold(UnidentifiedSightings()) { acc, d ->
                    acc.merge(runCatching { d.unidentified() }.getOrDefault(UnidentifiedSightings()))
                }
                _recoverable.value = _sources.value.filterTo(HashSet()) { d ->
                    driverFor(d.vendorId)?.let { runCatching { it.canRecoverKey(d) }.getOrDefault(false) } == true
                }.mapTo(HashSet()) { it.id }
                if (serving) _scanning.value = false
                val wanted = admitted(_activeIds.value)
                _admittedIds.value = wanted.toSet()
                // Stop first, so a slot freed this pass can be filled in the same pass.
                jobs.keys.toList().filterNot { it in wanted }.forEach { id -> jobs.remove(id)?.cancel() }
                wanted.filterNot { it in jobs }.forEach { id -> jobs[id] = scope.launch { runSessionsFor(id) } }
                withTimeoutOrNull(ENUMERATE_INTERVAL_MS) { wake.receive() }
            }
        } finally {
            jobs.values.forEach { it.cancel() }
            _admittedIds.value = emptySet()
            _scanning.value = false
        }
    }

    /** Supervisor coroutine only; reconnect joins the old session before the next rebuild. */
    private suspend fun drainCommands(jobs: HashMap<CgmSourceId, Job>) {
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: return
            when (command) {
                is Command.Reconnect -> {
                    descriptorOf(command.id)?.let { driverFor(it.vendorId)?.forget(it) }
                    jobs.remove(command.id)?.cancelAndJoin()
                }
                is Command.Repair -> rewriteTornDown(jobs, command.id, "repair") { repairHistory(it) }
                is Command.RecoverKey -> rewriteTornDown(jobs, command.id, "key recovery") { recoverKey(it) }
            }
        }
    }

    /** Session dies first, joined; nothing holds the state being rewritten. The pass rebuilds. */
    private suspend fun rewriteTornDown(
        jobs: HashMap<CgmSourceId, Job>,
        id: CgmSourceId,
        what: String,
        rewrite: suspend CgmFamilyDriver.(CgmSourceDescriptor) -> Boolean,
    ) {
        val descriptor = descriptorOf(id)
        jobs.remove(id)?.cancelAndJoin()
        if (descriptor == null) return
        val driver = driverFor(descriptor.vendorId) ?: return
        driver.forget(descriptor)
        val log = logs.forSensor(id)
        runCatching { driver.rewrite(descriptor) }
            .onSuccess { log.i(TAG, "$what ${if (it) "done" else "changed nothing"}; the link is rebuilt") }
            .onFailure { log.w(TAG, "$what of ${id.value} failed: ${it.message}") }
    }

    /** Trimmed to MAX_CONCURRENT_SESSIONS, oldest first, authoritative admitted; budget global. */
    private fun admitted(ids: Set<CgmSourceId>): List<CgmSourceId> {
        val byAge = _sources.value.map { it.id }.filter { it in ids }
        val auth = _authoritative.value?.takeIf { it in ids }
        val ranked = listOfNotNull(auth) + byAge.filterNot { it == auth }
        if (ranked.size > MAX_CONCURRENT_SESSIONS) {
            Log.w(TAG, "${ranked.size} active sources; holding the first $MAX_CONCURRENT_SESSIONS")
        }
        return ranked.take(MAX_CONCURRENT_SESSIONS)
    }

    /** Adopts the first adoptable sensor seen; advert name filled once, never overwritten. */
    private suspend fun registerDiscovered() {
        val held = liveSources.value
        for (driver in drivers) {
            // A radio-enumerating family rations off these; only this class knows them.
            val connected = held.values
                .filter { it.descriptor.vendorId == driver.vendorId }
                .map { it.descriptor.id }
                .toSet()
            val active = _activeIds.value.filterTo(HashSet()) { id ->
                descriptorOf(id)?.vendorId == driver.vendorId
            }
            for (candidate in runCatching { driver.candidates(active, connected) }.getOrElse { e ->
                Log.w(TAG, "${driver.vendorId} enumeration failed: ${e.message}")
                emptyList()
            }) {
                val seed = candidate.descriptor
                val id = seed.id
                val stored = descriptorOf(id)
                val desc = when {
                    stored == null -> seed
                    stored.advertName == null && seed.advertName != null -> stored.copy(advertName = seed.advertName)
                    else -> stored
                }
                _sources.update { current ->
                    if (current.none { it.id == id }) current + desc
                    else current.map { if (it.id == id) desc else it }
                }
                val firstEver = _authoritative.value == null && candidate.adoptable
                if (firstEver) {
                    _authoritative.value = id
                    _activeIds.update { it + id }
                }

                // Hearing a sensor isn't a relationship; row permanent, upsertSource pushes name.
                val onRecord = id in persistedIds || candidate.adoptable || id in _activeIds.value
                if (!onRecord) continue
                val now = nowMs()
                val (lastDesc, lastTouchMs) = lastPersisted[id] ?: (null to 0L)
                // Room invalidates per table; unconditional upsert re-runs queries at scan rate.
                if (firstEver || lastDesc != desc || now - lastTouchMs >= LAST_SEEN_TOUCH_MS) {
                    val ordinal = repository.upsertSource(desc, authoritative = firstEver, lastSeenMs = now)
                    if (firstEver) repository.setAuthoritative(id)
                    // Ordinal minted in that transaction; must carry back or every tick re-upserts.
                    val recorded = if (desc.ordinal == ordinal) desc else desc.copy(ordinal = ordinal)
                    if (recorded !== desc) {
                        _sources.update { current -> current.map { if (it.id == id) recorded else it } }
                    }
                    lastPersisted[id] = recorded to now
                    persistedIds += id
                }
            }
        }
    }

    private suspend fun runSessionsFor(id: CgmSourceId) {
        var backoffMs = RETRY_MIN_MS
        val log = logs.forSensor(id)
        // Unbuilt attempts repeat every 30 s out of reach; only the first of a run is logged.
        var unbuiltLogged = false
        try {
            while (currentCoroutineContext().isActive) {
                // Only before the first enumeration; a family can't be guessed from an id.
                val descriptor = descriptorOf(id)
                if (descriptor == null) {
                    delay(RETRY_MAX_MS)
                    continue
                }
                val startedAt = nowMs()
                val reachedLive = connectAndRun(id, descriptor, log, logUnbuilt = !unbuiltLogged)
                unbuiltLogged = reachedLive == null
                // Only a built session yielding nothing is reported; null means rationed elsewhere.
                if (reachedLive == false) driverFor(descriptor.vendorId)?.sessionYieldedNothing(descriptor)
                if (!currentCoroutineContext().isActive) return
                backoffMs = if (reachedLive == true && nowMs() - startedAt >= SESSION_HEALTHY_MS) {
                    RETRY_MIN_MS
                } else {
                    (backoffMs * 2).coerceAtMost(RETRY_MAX_MS)
                }
                if (reachedLive != null) log.i(TAG, "next attempt in ${backoffMs / 1000.0} s")
                delay(backoffMs)
            }
        } finally {
            log.i(TAG, "reading loop stopped (stop, remove, reconnect or link budget)")
        }
    }

    /** true/false: built, reached Live/Warmup or not. null: not built. Always closes session. */
    private suspend fun connectAndRun(
        id: CgmSourceId,
        descriptor: CgmSourceDescriptor,
        log: CgmSensorLog = CgmSensorLog.NONE,
        logUnbuilt: Boolean = true,
    ): Boolean? {
        val driver = driverFor(descriptor.vendorId)
        if (driver == null) {
            log.w(TAG, "no driver for ${descriptor.vendorId}; ${descriptor.id.value} will not be read")
            delay(RETRY_MAX_MS)
            return null
        }
        // Child scope so the session's collectors die with it; SupervisorJob isolates its failure.
        val sessionJob = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
        val source = runCatching { driver.createSession(descriptor, sessionScope) }.getOrElse { e ->
            log.w(TAG, "${descriptor.id.value}: session could not be built — ${e.message}")
            null
        }
        if (source == null) {
            if (logUnbuilt) log.i(TAG, "no session: out of reach or held off; retrying every ${RETRY_MAX_MS / 1000} s")
            sessionScope.cancel()
            // May be reachable again any moment; wait rather than escalate the backoff.
            delay(RETRY_MAX_MS)
            return null
        }
        liveSources.update { it + (id to source) }
        log.i(TAG, "session built: ${source.javaClass.simpleName}")
        var everLive = false
        try {
            sessionScope.launch { source.readings().collect { _readings.emit(it) } }
            sessionScope.launch { source.status.collect { log.i(TAG, "status $it") } }
            // Routed through setWarmupWindowMin so the descriptor and column stay paired.
            sessionScope.launch {
                source.declaredWarmupWindowMin.collect { minutes ->
                    if (minutes != null && minutes != descriptorOf(id)?.warmupWindowMin) {
                        setWarmupWindowMin(id, minutes)
                    }
                }
            }
            sessionScope.launch {
                source.statedLifetimeMin.collect { minutes ->
                    if (minutes != null) statedLifetimes.update { it + (id to minutes) }
                }
            }
            sessionScope.launch {
                source.sensorStartMs.collect { startMs ->
                    if (startMs != null) sensorStarts.update { it + (id to startMs) }
                }
            }
            source.start()
            source.awaitSignalLost {
                // On the registry's scope, not the session's; a live cancel could outlive a ration.
                if (!everLive) {
                    everLive = true
                    scope.launch { driver.sensorReadable(descriptor) }
                }
            }
        } finally {
            source.close()
            // Joined: a session write in flight lands before a repair or key recovery deletes.
            withContext(NonCancellable) { sessionJob.cancelAndJoin() }
            liveSources.update { if (it[id] === source) it - id else it }
            log.i(TAG, "session closed; ${if (everLive) "it delivered" else "it never delivered"}")
        }
        return everLive
    }

    companion object {
        private const val TAG = "CgmScan"
        private const val RETRY_MIN_MS = 1_000L
        private const val RETRY_MAX_MS = 30_000L

        private const val SESSION_HEALTHY_MS = 60_000L

        /** A radio-enumerating family rations itself. */
        private const val ENUMERATE_INTERVAL_MS = 30_000L

        /** Refresh floor for unchanged lastSeenMs; else every enumeration writes at scan rate. */
        private const val LAST_SEEN_TOUCH_MS = 15 * 60_000L

        /** Concurrent GATT links, every family; past controller limit BLE fails intermittently. */
        const val MAX_CONCURRENT_SESSIONS = 4
    }
}
