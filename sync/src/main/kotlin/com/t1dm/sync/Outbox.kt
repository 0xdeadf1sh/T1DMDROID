package com.t1dm.sync

import kotlinx.serialization.Serializable

/** Method/path/body captured at enqueue, replayed verbatim. A BG marker's payload is empty. */
@Serializable
data class OutboxRequest(val method: String, val path: String, val body: String) {
    fun toSyncRequest() = SyncRequest(method, path, body.toByteArray(Charsets.UTF_8))
}
