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
}
