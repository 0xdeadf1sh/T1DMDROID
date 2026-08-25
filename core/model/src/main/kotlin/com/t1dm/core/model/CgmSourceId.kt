package com.t1dm.core.model

/** Stable, human-readable source identity, e.g. "aidexx:22222C74D9". Never a BLE address. */
@JvmInline
value class CgmSourceId(val value: String) {
    companion object {
        /** Named here rather than in the debug build: the archive restore and `MIGRATION_10_11`
         *  both need it by name on the release path. */
        val DEBUG = CgmSourceId("aidexx:DEBUG")
    }

    /**
     * Non-identifying label, crossing the wire as `bg_source` (`SPEC/http-api.md`). [value] is
     * `vendor:serial`; hashing keeps the serial out of the server's storage. 16 bytes, not 4: a
     * serial's input space is small enough that a 32-bit digest inverts by brute force in seconds.
     */
    val opaque: String
        get() {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return "s_" + digest.take(16).joinToString("") { "%02x".format(it) }
        }
}
