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
     * server's history without a real device identifier reaching its storage, its backups, or the
     * operator console. Same input, same label, on every install and after any restore.
     */
    val opaque: String
        get() {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return "s_" + digest.take(4).joinToString("") { "%02x".format(it) }
        }
}
