package com.t1dm.app.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.t1dm.data.backup.Archive
import java.io.InputStream
import java.io.OutputStream

/** Matched by [PREFIX], not extension (providers rewrite it); archive id is its gzip magic. */
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
        // A partial document here would be swept by retention, pruning a good backup for room.
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
        // Name is the tie-break: providers report no last-modified, but name carries a stamp.
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
        /** What makes a file ours, and what keeps the sweep off everything else in the folder. */
        const val PREFIX = "t1dm-"

        const val MIME = "application/octet-stream"

        fun fileName(stamp: String): String = "$PREFIX$stamp.${Archive.EXTENSION}"

        /** Null when the provider will not say; the caller substitutes a generic label. */
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

/** Message shown to user; distinct from a parse failure so the panel names folder vs file. */
class BackupDestinationException(message: String) : java.io.IOException(message)
