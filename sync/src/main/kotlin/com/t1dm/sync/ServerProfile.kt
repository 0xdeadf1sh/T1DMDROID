package com.t1dm.sync

/**
 * A resolved server profile: the persisted [ServerProfileEntity] metadata plus its `rw` token from
 * the Keystore-backed [TokenStore]. Kept separate from the Room entity so the secret never rides in
 * a data-class that could be logged or serialized alongside the DB (Phase 3).
 */
data class ServerProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val active: Boolean,
)

/**
 * The live target a request is issued against — an active profile's base URL and its `rw` token.
 * `null` from the endpoint provider means "no active profile / no token", on which the drainer and
 * stream client stand down rather than error.
 */
data class ServerEndpoint(
    val baseUrl: String,
    val token: String,
)
