package com.t1dm.core.model

/** Stable, human-readable source identity, e.g. "aidexx:22222C74D9". Never a BLE address. */
@JvmInline
value class CgmSourceId(val value: String) {
    companion object {
        /** Named here, not debug build: archive restore, MIGRATION_10_11 need it on release. */
        val DEBUG = CgmSourceId("aidexx:DEBUG")
    }

    /** Stored as bg_source, hashed: 16B, not 4, resists brute force. */
    val opaque: String
        get() {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return "s_" + digest.take(16).joinToString("") { "%02x".format(it) }
        }
}
