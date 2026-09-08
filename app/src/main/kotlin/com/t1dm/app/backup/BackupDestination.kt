package com.t1dm.app.backup

import java.io.InputStream
import java.io.OutputStream

/** [id] is opaque: only the destination that issued it may interpret it. */
class StoredBackup(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

interface BackupDestination {

    val label: String

    /** Stream closes either way; no partial file — retention sweep would prune a real backup. */
    suspend fun write(name: String, body: suspend (OutputStream) -> Unit): StoredBackup

    /** Newest first. */
    suspend fun list(): List<StoredBackup>

    /** The caller closes it. */
    suspend fun open(id: String): InputStream

    suspend fun delete(id: String)
}
