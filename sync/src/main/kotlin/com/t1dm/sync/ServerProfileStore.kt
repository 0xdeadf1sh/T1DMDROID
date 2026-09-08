package com.t1dm.sync

import com.t1dm.data.T1dmRepository
import com.t1dm.data.db.ServerProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** N profiles, exactly one active; metadata in Room, `rw` token in [TokenStore] by profile id. */
class ServerProfileStore(
    private val repo: T1dmRepository,
    private val tokens: TokenStore,
) {
    fun observeProfiles(): Flow<List<ServerProfile>> =
        repo.observeProfiles().map { list -> list.map { it.toDomain() } }

    fun observeActive(): Flow<ServerProfile?> =
        repo.observeActiveProfile().map { it?.toDomain() }

    /** [makeActive] enforces the one-active invariant atomically. */
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

    suspend fun activeEndpoint(): ServerEndpoint? {
        val p = repo.activeProfile() ?: return null
        val token = tokens.get(p.id) ?: return null
        return ServerEndpoint(baseUrl = p.baseUrl, token = token)
    }

    private fun ServerProfileEntity.toDomain() =
        ServerProfile(id = id, label = label, baseUrl = baseUrl, active = active)
}
