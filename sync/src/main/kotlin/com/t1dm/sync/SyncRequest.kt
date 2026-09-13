package com.t1dm.sync

/** [body] is UTF-8 JSON, or null for a GET. */
data class SyncRequest(val method: String, val path: String, val body: ByteArray?) {
    override fun equals(other: Any?) = other is SyncRequest &&
        method == other.method && path == other.path && (body?.contentEquals(other.body ?: ByteArray(0)) ?: (other.body == null))

    override fun hashCode() = (method.hashCode() * 31 + path.hashCode()) * 31 + (body?.contentHashCode() ?: 0)
}

/** 4xx/5xx do NOT throw; the drainer classifies. */
data class SyncResponse(val code: Int, val body: ByteArray) {
    val ok: Boolean get() = code in 200..299

    /** A 4xx that will never succeed on replay; the drainer drops it. */
    val permanentClientError: Boolean get() = code in 400..499 && code != 401 && code != 403 && code != 429

    val authError: Boolean get() = code == 401 || code == 403

    override fun equals(other: Any?) = other is SyncResponse && code == other.code && body.contentEquals(other.body)
    override fun hashCode() = code * 31 + body.contentHashCode()
}
