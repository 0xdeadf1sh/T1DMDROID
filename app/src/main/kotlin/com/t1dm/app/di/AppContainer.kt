package com.t1dm.app.di

import android.content.Context
import com.t1dm.alerts.AlarmConfig
import com.t1dm.app.cgm.AppCgmRepository
import com.t1dm.app.inference.RoomBgHistoryProvider
import com.t1dm.app.sync.RoomPredictionStore
import com.t1dm.app.sync.SyncManager
import com.t1dm.app.sync.SyncStatus
import com.t1dm.app.sync.SyncStatusStore
import com.t1dm.cgm.AidexXPlugin
import com.t1dm.cgm.AidexXSourceRegistry
import com.t1dm.core.common.DefaultT1dmDispatchers
import com.t1dm.core.common.NativeCore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceDescriptor
import com.t1dm.core.model.InferenceState
import com.t1dm.core.nativecore.UniffiNativeCore
import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.AppDatabase
import com.t1dm.inference.InferenceController
import com.t1dm.inference.buildInferenceController
import com.t1dm.sync.CatchUpCoordinator
import com.t1dm.sync.DrainConfig
import com.t1dm.sync.HttpUrlConnectionSyncClient
import com.t1dm.sync.NoActiveProfileException
import com.t1dm.sync.OutboxEnqueuer
import com.t1dm.sync.QueueDrainer
import com.t1dm.sync.ServerProfile
import com.t1dm.sync.ServerProfileStore
import com.t1dm.sync.SyncHttpClient
import com.t1dm.sync.KeystoreTokenStore
import com.t1dm.sync.TokenStore
import com.t1dm.sync.WebSocketStreamClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.plus

/**
 * The manual composition root (PLAN.private.md — "DI/wiring: manual is fine"). Built once in
 * [com.t1dm.app.T1dmApplication] and reached via `(application as T1dmApplication).container`.
 * Everything long-lived that the UI, the [com.t1dm.app.service.CgmScanService], and the debug
 * hooks share is constructed here exactly once; nothing constructs its own database, dispatchers,
 * or native core.
 *
 * Deliberately free of `:inference` — the Phase-1 walking skeleton (decode → Room → graph →
 * service → model-free alarm → steps) must run with no forecast in sight (§3.6-A).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val dispatchers: T1dmDispatchers = DefaultT1dmDispatchers()

    val nativeCore: NativeCore = UniffiNativeCore()

    /** Application-lifetime scope for the CGM registry's shared scan (survives Activity churn). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val repository: T1dmRepository by lazy { T1dmRepository(database, dispatchers) }

    /** The `:cgm` persistence port bound onto the Room-backed [T1dmRepository]. */
    private val cgmRepository by lazy { AppCgmRepository(repository) }

    val plugin: AidexXPlugin by lazy { AidexXPlugin(nativeCore, cgmRepository) }

    val registry: AidexXSourceRegistry by lazy {
        AidexXSourceRegistry(
            plugin = plugin,
            repository = cgmRepository,
            scope = appScope,
        )
    }

    /** Conservative boot defaults (§3.6-A); user config replaces these later. */
    val alarmConfig: AlarmConfig = AlarmConfig.DEFAULT

    // ─── Inference runtime (Phase 2) ──────────────────────────────────────────────────────────

    /** Dev-time models dir on the app's external files (adb-pushable; the 27 MB .pte is NOT bundled).
     *  Push with: `adb push descriptor.json <this>/` and `adb push t1dmai_best.xnnpack.pte <this>/`. */
    val modelsDir: File = File(appContext.getExternalFilesDir(null), "models").apply { mkdirs() }

    /** The Phase-2 orchestrator: builds one shared context per cycle, fans out serially over the
     *  running set, decodes + guards in Rust, publishes + persists predictions tagged by model_id. */
    val inferenceController: InferenceController by lazy {
        buildInferenceController(
            native = nativeCore,
            dispatchers = dispatchers,
            modelsDir = modelsDir,
            history = RoomBgHistoryProvider(repository, registry),
            // Phase 3: the dedicated `prediction` table is the source of truth, and every cycle
            // enqueues a deduped `PREDICTIONS` batch for all running models (retires the kv blob).
            predictionStore = RoomPredictionStore(repository, outboxEnqueuer, syncStatusStore),
        )
    }

    val inferenceState: StateFlow<InferenceState> get() = inferenceController.state

    /** Discover on-device models + rehydrate the last predictions once at startup (off-main). */
    fun startInference() {
        appScope.launch {
            inferenceController.restoreLast()
            inferenceController.refreshModels()
        }
    }

    // ─── Server sync (Phase 3) ────────────────────────────────────────────────────────────────

    /** Per-profile `rw` token at rest, Keystore-wrapped — never in the keep-forever Room DB. */
    val tokenStore: TokenStore by lazy { KeystoreTokenStore(appContext) }

    /** N-profile store (one active); the endpoint provider both the client and the stream follow. */
    val serverProfileStore: ServerProfileStore by lazy { ServerProfileStore(repository, tokenStore) }

    val syncHttpClient: SyncHttpClient by lazy {
        HttpUrlConnectionSyncClient(
            endpoint = { serverProfileStore.activeEndpoint() },
            dispatchers = dispatchers,
        )
    }

    val outboxEnqueuer: OutboxEnqueuer by lazy { OutboxEnqueuer(repository) }

    /** Live Network-panel telemetry (process-scoped; the durable outbox itself is persisted). */
    val syncStatusStore: SyncStatusStore = SyncStatusStore()

    private val drainConfig: DrainConfig = DrainConfig()

    private val queueDrainer: QueueDrainer by lazy {
        QueueDrainer(
            dao = database.outboxDao(),
            http = syncHttpClient,
            sampleAt = repository::sampleAt,
            dispatchers = dispatchers,
            config = drainConfig,
        )
    }

    private val streamClient by lazy {
        WebSocketStreamClient(
            endpoint = { serverProfileStore.activeEndpoint() },
            dispatchers = dispatchers,
        )
    }

    private val catchUpCoordinator by lazy {
        CatchUpCoordinator(stream = streamClient, http = syncHttpClient, repo = repository)
    }

    /** The always-on sync orchestrator; the FGS calls [SyncManager.launch] in its lifecycle scope. */
    val syncManager: SyncManager by lazy {
        SyncManager(
            drainer = queueDrainer,
            catchUp = catchUpCoordinator,
            repository = repository,
            status = syncStatusStore,
            dispatchers = dispatchers,
        )
    }

    val syncStatus: StateFlow<SyncStatus> get() = syncStatusStore.state

    /** Configured outbox bounds (surfaced on the Network panel next to the live depth/age). */
    val outboxMaxAgeMs: Long get() = drainConfig.maxAgeMs
    val outboxMaxSize: Int get() = drainConfig.maxQueueSize

    // ─── Server profile read models + config actions (Settings → Server) ──────────────────────

    val serverProfiles: Flow<List<ServerProfile>> = serverProfileStore.observeProfiles()

    val activeServerProfile: Flow<ServerProfile?> = serverProfileStore.observeActive()

    /**
     * Create/update the primary server profile and make it active (the Phase-3 single-profile UI;
     * the store is N-profile so multi-profile CRUD is additive later). A blank [token] keeps the
     * stored one. Runs off-main.
     */
    suspend fun saveServerProfile(label: String, baseUrl: String, token: String) {
        val existing = repository.activeProfile()
        serverProfileStore.upsert(
            id = existing?.id ?: "default",
            label = label.ifBlank { "server" },
            baseUrl = baseUrl,
            token = token.ifBlank { null },
            makeActive = true,
            nowMs = System.currentTimeMillis(),
        )
    }

    /** One-shot health probe against the active profile; a human-readable status line. */
    suspend fun checkServerHealth(): String = runCatching {
        val h = syncHttpClient.health()
        "reachable — status=${h.status}, ${h.ws_clients} ws client(s)"
    }.getOrElse { e ->
        when (e) {
            is NoActiveProfileException -> "no active profile / token configured"
            else -> "unreachable — ${e.message ?: e::class.simpleName}"
        }
    }

    // ─── Dashboard read models (DB-backed so they survive process death) ──────────────────────

    val activeSource: Flow<CgmSourceDescriptor?> = repository.observeActiveSource()

    val allSources: Flow<List<CgmSourceDescriptor>> = repository.observeSources()

    /** Every reading of the active source (widest window; Phase-1 volumes are tiny). */
    val dashboardReadings: Flow<List<CgmReading>> = activeSource.flatMapLatest { d ->
        if (d == null) flowOf(emptyList()) else repository.observeReadings(d.id, 0L, Long.MAX_VALUE)
    }

    val latestReading: Flow<CgmReading?> = activeSource.flatMapLatest { d ->
        if (d == null) flowOf(null) else repository.observeLatestReading(d.id)
    }

    /** Set once [com.t1dm.app.service.CgmScanService] is up, so the UI can reflect service state. */
    val serviceRunning = MutableStateFlow(false)
}
