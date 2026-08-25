package com.t1dm.sync

/** Metadata only; the `rw` token stays in [TokenStore], never in a row that could be logged. */
data class ServerProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val active: Boolean,
)

/** Null from the endpoint provider means no active profile or no token; callers stand down. */
data class ServerEndpoint(
    val baseUrl: String,
    val token: String,
)
