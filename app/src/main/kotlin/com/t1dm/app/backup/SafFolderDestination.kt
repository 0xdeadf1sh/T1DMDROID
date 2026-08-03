package com.t1dm.app.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.t1dm.data.backup.Archive
import java.io.InputStream
import java.io.OutputStream

/**
 * A folder the user granted once through `ACTION_OPEN_DOCUMENT_TREE`, with the grant persisted
 * across reboots.
 *
 * Writing outside the app's own storage is the entire point: Android deletes app-private files on
 * uninstall, so a backup kept there could never serve the fresh-install restore this exists for.
 *
 * **Files are matched by PREFIX, not by extension.** Some document providers rewrite or append an
 * extension on create, and the archive is identified by its gzip magic rather than its name in any
 * case — so [PREFIX] is the whole of what makes a file ours, and a provider that renames
 * `t1dm-20260803-0400.t1dmbak` to something with a `.bin` on the end breaks neither the retention
 * sweep nor a restore.
 */
class SafFolderDestination(
    context: Context,
    private val treeUri: Uri,
    override val label: String,
) : BackupDestination {

    private val resolver: ContentResolver = context.contentResolver

    private val parentDocUri: Uri
        get() = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): StoredBackup {
        val target = DocumentsContract.createDocument(resolver, parentDocUri, MIME, name)
            ?: throw BackupDestinationException("could not create a file in $label")
        // A failure past this point leaves a zero-or-partial-length document behind, which the
        // retention sweep would count as a backup and prune a good one to make room for. Delete it
        // and re-raise, so a failed run costs nothing rather than costing the oldest real archive.
        try {
            resolver.openOutputStream(target)?.use { body(it) }
                ?: throw BackupDestinationException("could not open $name for writing")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw t
        }
        return stat(target) ?: StoredBackup(target.toString(), name, 0L, 0L)
    }

    override suspend fun list(): List<StoredBackup> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val out = ArrayList<StoredBackup>()
        resolver.query(children, COLUMNS, null, null, null)?.use { c ->
            val idIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val timeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val name = c.getString(nameIx) ?: continue
                if (!name.startsWith(PREFIX)) continue
                val docId = c.getString(idIx) ?: continue
                out.add(
                    StoredBackup(
                        id = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                        name = name,
                        sizeBytes = if (c.isNull(sizeIx)) 0L else c.getLong(sizeIx),
                        modifiedAtMs = if (c.isNull(timeIx)) 0L else c.getLong(timeIx),
                    ),
                )
            }
        }
        // Newest first by the provider's own timestamp, with the name as the tie-break. The name
        // carries a sortable stamp, so it stands in coherently when a provider reports no
        // last-modified at all — which some do, and which would otherwise make the sweep's idea of
        // "oldest" arbitrary.
        out.sortWith(compareByDescending<StoredBackup> { it.modifiedAtMs }.thenByDescending { it.name })
        return out
    }

    override suspend fun open(id: String): InputStream =
        resolver.openInputStream(Uri.parse(id))
            ?: throw BackupDestinationException("could not open the backup for reading")

    override suspend fun delete(id: String) {
        if (!DocumentsContract.deleteDocument(resolver, Uri.parse(id))) {
            throw BackupDestinationException("could not delete the backup")
        }
    }

    private fun stat(uri: Uri): StoredBackup? =
        resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val nameIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val timeIx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            StoredBackup(
                id = uri.toString(),
                name = c.getString(nameIx) ?: "",
                sizeBytes = if (c.isNull(sizeIx)) 0L else c.getLong(sizeIx),
                modifiedAtMs = if (c.isNull(timeIx)) 0L else c.getLong(timeIx),
            )
        }

    companion object {
        /** What makes a file in the chosen folder one of ours. Also keeps the sweep from deleting
         *  anything the user happens to keep alongside them. */
        const val PREFIX = "t1dm-"

        const val MIME = "application/octet-stream"

        /** `t1dm-YYYYMMDD-HHMM.t1dmbak` — lexically sortable, so the name alone orders the set even
         *  when a provider reports no timestamp. */
        fun fileName(stamp: String): String = "$PREFIX$stamp.${Archive.EXTENSION}"

        /**
         * The granted folder's own display name, read once at grant time and then stored. Null when
         * the provider will not say — some do not — and the caller substitutes a generic label.
         */
        fun displayName(context: Context, treeUri: Uri): String? {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            return context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }?.ifBlank { null }
        }

        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** A destination-layer failure, carrying a message fit to show the user. Distinct from an archive
 *  parse failure so the panel can say whether the folder or the file was the problem. */
class BackupDestinationException(message: String) : java.io.IOException(message)
