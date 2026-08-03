package com.t1dm.app.backup

import java.io.InputStream
import java.io.OutputStream

/** One stored backup, as the panel lists it and the retention sweep ranks it. [id] is opaque and
 *  belongs to the destination that issued it — a document URI here, a file id elsewhere. */
class StoredBackup(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

/**
 * Where backups are written, listed and pruned.
 *
 * The seam exists for one reason: a second destination — Google Drive over its REST API — is a
 * plausible next step, and it must not require reopening the worker, the retention sweep or the
 * panel to add. Everything above this interface is written in terms of it and knows nothing of SAF,
 * tree grants or content resolvers.
 *
 * It is a thin seam on purpose. [write] takes a body that is handed the stream rather than returning
 * bytes, so a multi-megabyte archive is never materialised to hand across the boundary — the
 * abstraction costs one virtual call per backup, not one per row.
 */
interface BackupDestination {

    /** What the panel calls this destination — the folder's name here, an account elsewhere. */
    val label: String

    /**
     * Create [name] and stream the archive into it. The stream is closed when [body] returns,
     * whether or not it threw.
     *
     * Implementations must not leave a partial file behind on failure: a half-written archive that
     * survives is indistinguishable from a good one to the retention sweep, which would then count
     * it as a backup and prune a real one to make room.
     */
    suspend fun write(name: String, body: suspend (OutputStream) -> Unit): StoredBackup

    /** Every backup this destination holds, newest first. */
    suspend fun list(): List<StoredBackup>

    /** Open one for reading. The caller closes it. */
    suspend fun open(id: String): InputStream

    suspend fun delete(id: String)
}
