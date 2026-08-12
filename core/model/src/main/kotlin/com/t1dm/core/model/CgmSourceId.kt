package com.t1dm.core.model

/** Stable, human-readable source identity, e.g. "aidexx:22222C74D9". Never a BLE address. */
@JvmInline
value class CgmSourceId(val value: String) {
    companion object {
        /**
         * The synthetic source debug-injected readings are attributed to. Named here rather than in
         * the debug build alone because two release-path readers need it by name: the archive
         * restore, deciding which model class a source with no recorded class belongs to, and
         * `MIGRATION_10_11`, which must give it the same answer.
         */
        val DEBUG = CgmSourceId("aidexx:DEBUG")
    }

    /**
     * A short, stable, non-identifying label for this sensor — what crosses the wire as
     * `bg_source` (`SPEC/http-api.md`).
     *
     * [value] is `vendor:serial`, and the serial is the number printed on the sensor: everything
     * else about the sensor is derived from it. Hashing means a sensor change stays visible in the
     * server's history without the serial itself reaching its storage, its backups, or the operator
     * console. Same input, same label, on every install and after any restore.
     *
     * **16 bytes, not 4.** A serial has a known alphabet and a known length, so the whole input space
     * is small enough to enumerate: a 32-bit digest of it is recovered by brute force in seconds, and
     * a label that can be inverted is the serial with extra steps. This is not a secret — anyone who
     * can read the label can also read the readings — but it should not be a serial in disguise.
     */
    val opaque: String
        get() {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return "s_" + digest.take(16).joinToString("") { "%02x".format(it) }
        }
}
