package com.t1dm.app.backup

import android.content.Context
import android.net.Uri
import com.t1dm.app.settings.SettingsStore
import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.data.T1dmRepository
import com.t1dm.data.backup.ArchiveCounts
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BackupRun(val file: StoredBackup, val counts: ArchiveCounts, val pruned: Int)

/** Not the restore path: that stays in AppContainer, which owns the actuator policies. */
class BackupManager(
    private val appContext: Context,
    private val repository: T1dmRepository,
    private val settings: SettingsStore,
    private val dispatchers: T1dmDispatchers,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Null when no folder is granted; rebuilt per call since the grant can be revoked. */
    suspend fun destination(): BackupDestination? {
        val uri = settings.currentBackupFolder() ?: return null
        // The URI-derived label is the fallback for a grant made before the label was stored.
        val label = settings.currentBackupFolderLabel() ?: uri.toDisplayLabel()
        return SafFolderDestination(appContext, Uri.parse(uri), label)
    }

    suspend fun runBackup(): BackupRun = withContext(dispatchers.io) {
        val dest = destination() ?: throw BackupDestinationException("no backup folder chosen")
        val now = clock()
        try {
            val configJson = settings.exportJson()
            var written = ArchiveCounts()
            val file = dest.write(SafFolderDestination.fileName(stampFor(now))) { out ->
                written = repository.writeArchive(out, configJson, appVersion, now)
            }
            val counts = written
            // Pruning happens only after a successful write, so nothing is spent for nothing.
            val pruned = prune(dest)
            settings.recordBackupOk(now, file.sizeBytes, counts.total)
            settings.clearBackupError()
            BackupRun(file, counts, pruned)
        } catch (t: Throwable) {
            settings.recordBackupError(now, t.message ?: t::class.simpleName ?: "unknown failure")
            throw t
        }
    }

    /** Failure is logged and swallowed: failing here would trigger a second archive write. */
    private suspend fun prune(dest: BackupDestination): Int {
        val keep = settings.currentBackupKeep()
        return runCatching {
            val stale = dest.list().drop(keep)
            var removed = 0
            for (f in stale) {
                if (runCatching { dest.delete(f.id) }.isSuccess) removed++
            }
            removed
        }.getOrElse {
            Timber.tag(TAG).w(it, "retention sweep failed")
            0
        }
    }

    suspend fun list(): List<StoredBackup> = withContext(dispatchers.io) {
        destination()?.list() ?: emptyList()
    }

    suspend fun delete(id: String) = withContext(dispatchers.io) {
        (destination() ?: throw BackupDestinationException("no backup folder chosen")).delete(id)
    }

    suspend fun open(id: String): InputStream = withContext(dispatchers.io) {
        (destination() ?: throw BackupDestinationException("no backup folder chosen")).open(id)
    }

    /** [out] is closed by the caller. */
    suspend fun exportTo(out: OutputStream): ArchiveCounts = withContext(dispatchers.io) {
        repository.writeArchive(out, settings.exportJson(), appVersion, clock())
    }

    companion object {
        private const val TAG = "Backup"

        /** Local time, not UTC, and lexically sortable so the file list orders itself. */
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

        fun stampFor(ms: Long): String =
            STAMP.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}

/** Panel label only; nothing keys on it. */
private fun String.toDisplayLabel(): String {
    val decoded = Uri.decode(this)
    return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { "the chosen folder" }
}
