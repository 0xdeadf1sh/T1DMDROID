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

/** What one automatic run did: the file it wrote, what went into it, and how many older ones the
 *  retention sweep removed afterwards. */
class BackupRun(val file: StoredBackup, val counts: ArchiveCounts, val pruned: Int)

/**
 * Owns the automatic-backup destination, the run itself, and the retention sweep.
 *
 * Deliberately not the restore path: applying a settings document means re-hydrating the alarm and
 * actuator policies, which is the composition root's business, so that half stays in `AppContainer`
 * alongside the legacy reader it may need to fall back to.
 */
class BackupManager(
    private val appContext: Context,
    private val repository: T1dmRepository,
    private val settings: SettingsStore,
    private val dispatchers: T1dmDispatchers,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * The configured destination, or null when no folder has been granted.
     *
     * Rebuilt per call rather than cached: the grant can be revoked from system settings or the
     * folder deleted between one run and the next, and a cached handle would keep reporting a
     * destination that no longer exists.
     */
    suspend fun destination(): BackupDestination? {
        val uri = settings.currentBackupFolder() ?: return null
        // The label captured when the folder was granted is the provider's own display name and is
        // preferred; deriving one from the URI is the fallback for a grant made by an older build.
        val label = settings.currentBackupFolderLabel() ?: uri.toDisplayLabel()
        return SafFolderDestination(appContext, Uri.parse(uri), label)
    }

    /**
     * Write one backup and prune to the retention setting. Throws on failure — the caller decides
     * whether that is a worker retry or a line on the panel — but records the outcome either way,
     * because a run nobody hears about is the failure mode this feature has to avoid above all.
     */
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
            // Only ever AFTER a successful write. Pruning first would mean a run that then failed had
            // spent the user's oldest backup and produced nothing in exchange.
            val pruned = prune(dest)
            settings.recordBackupOk(now, file.sizeBytes, counts.total)
            settings.clearBackupError()
            BackupRun(file, counts, pruned)
        } catch (t: Throwable) {
            settings.recordBackupError(now, t.message ?: t::class.simpleName ?: "unknown failure")
            throw t
        }
    }

    /**
     * Delete everything past the newest [SettingsStore.backupKeep]. A failure here is logged and
     * swallowed: the backup itself already succeeded, and reporting the whole run as failed because
     * a stale file could not be removed would be a lie that also triggers a worker retry — which
     * would write a second archive, making the very problem worse.
     */
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

    /** The manual SAF export — the same archive, to a file the user names. [out] is closed by the caller. */
    suspend fun exportTo(out: OutputStream): ArchiveCounts = withContext(dispatchers.io) {
        repository.writeArchive(out, settings.exportJson(), appVersion, clock())
    }

    companion object {
        private const val TAG = "Backup"

        /** `yyyyMMdd-HHmm` in LOCAL time. Local rather than UTC because the name is read by a human
         *  looking for last night's backup, and lexically sortable so the file list orders itself. */
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

        fun stampFor(ms: Long): String =
            STAMP.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}

/** The last path segment of a tree URI, decoded — `primary:Documents/T1DM` becomes `T1DM`. A label
 *  for the panel only; nothing keys on it. */
private fun String.toDisplayLabel(): String {
    val decoded = Uri.decode(this)
    return decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { "the chosen folder" }
}
