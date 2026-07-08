package com.t1dm.sync

import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.ServerProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * N-profile server store with exactly one active profile (PLAN.private.md Phase 3 / server-
 * integration memory). Profile *metadata* persists in Room (via [T1dmRepository]); the `rw` token
 * persists in the Keystore-backed [TokenStore], keyed by profile id. Full CRUD/switch UI is Phase 7,
 * but the schema is N-profile now so that work is additive, not a migration.
 *
 * The composition root passes [activeEndpoint] to the [SyncHttpClient] and [StreamClient] so they
 * always follow the current active profile without holding a stale URL/token.
 */
class ServerProfileStore(
    private val repo: T1dmRepository,
    private val tokens: TokenStore,
) {
    fun observeProfiles(): Flow<List<ServerProfile>> =
        repo.observeProfiles().map { list -> list.map { it.toDomain() } }

    fun observeActive(): Flow<ServerProfile?> =
        repo.observeActiveProfile().map { it?.toDomain() }

    /** Create or update a profile; when [makeActive] the one-active invariant is enforced atomically. */
    suspend fun upsert(
        id: String,
        label: String,
        baseUrl: String,
        token: String?,
        makeActive: Boolean,
        nowMs: Long,
    ) {
        val existing = repo.profileById(id)
        repo.upsertProfile(
            ServerProfileEntity(
                id = id,
                label = label,
                baseUrl = baseUrl.trimEnd('/'),
                active = makeActive || (existing?.active ?: false),
                createdAtMs = existing?.createdAtMs ?: nowMs,
                updatedAtMs = nowMs,
            ),
            makeActive = makeActive,
        )
        if (token != null) tokens.put(id, token)
    }

    suspend fun setActive(id: String) = repo.setActiveProfile(id)

    suspend fun delete(id: String) {
        repo.deleteProfile(id)
        tokens.remove(id)
    }

    /** The current active endpoint (base URL + token), or `null` if none is configured/tokened. */
    suspend fun activeEndpoint(): ServerEndpoint? {
        val p = repo.activeProfile() ?: return null
        val token = tokens.get(p.id) ?: return null
        return ServerEndpoint(baseUrl = p.baseUrl, token = token)
    }

    private fun ServerProfileEntity.toDomain() =
        ServerProfile(id = id, label = label, baseUrl = baseUrl, active = active)
}
