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

    /** The stream is closed when [body] returns, thrown or not. Leave no partial file behind on
     *  failure: the retention sweep would count it as a backup and prune a real one. */
    suspend fun write(name: String, body: suspend (OutputStream) -> Unit): StoredBackup

    /** Newest first. */
    suspend fun list(): List<StoredBackup>

    /** The caller closes it. */
    suspend fun open(id: String): InputStream

    suspend fun delete(id: String)
}
