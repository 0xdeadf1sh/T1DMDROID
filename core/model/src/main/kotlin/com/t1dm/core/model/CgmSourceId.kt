package com.t1dm.core.model

/** Stable, human-readable source identity, e.g. "aidexx:22222C74D9". Never a BLE address. */
@JvmInline
value class CgmSourceId(val value: String) {
    companion object {
        /** Named here, not in debug build: archive restore and MIGRATION_10_11 need it by name. */
        val DEBUG = CgmSourceId("aidexx:DEBUG")
    }

    /** Non-identifying wire label (bg_source); hashes vendor:serial; 16 bytes, 32-bit bruteable. */
    val opaque: String
        get() {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return "s_" + digest.take(16).joinToString("") { "%02x".format(it) }
        }
}
