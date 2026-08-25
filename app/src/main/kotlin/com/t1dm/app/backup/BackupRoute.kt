package com.t1dm.app.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.t1dm.app.di.AppContainer
import com.t1dm.app.settings.SettingsStore
import com.t1dm.data.backup.ArchiveCounts
import com.t1dm.feature.backup.BackupEntry
import com.t1dm.feature.backup.BackupPanelState
import com.t1dm.feature.backup.BackupScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Archive work runs on [AppContainer.appScope], not the route's scope: navigating away cancels a
 *  `rememberCoroutineScope` and would abandon a half-written archive in the folder. */
@Composable
fun BackupRoute(container: AppContainer, onNotice: (String) -> Unit) {
    val ctx = LocalContext.current
    val ui = rememberCoroutineScope()
    val settings = container.settingsStore

    val folderUri by settings.backupFolderUri.collectAsState(null)
    val folderLabel by settings.backupFolderLabel.collectAsState(null)
    val cadence by settings.backupCadenceHours.collectAsState(SettingsStore.DEFAULT_BACKUP_CADENCE_H)
    val keep by settings.backupKeep.collectAsState(SettingsStore.DEFAULT_BACKUP_KEEP)
    val lastOk by settings.backupLastOk.collectAsState(null)
    val lastError by settings.backupLastError.collectAsState(null)

    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    val backups by produceState(initialValue = emptyList<BackupEntry>(), folderUri, refresh) {
        value = if (folderUri == null) {
            emptyList()
        } else {
            runCatching { container.backupManager.list() }.getOrDefault(emptyList())
                .map { BackupEntry(it.id, it.name, it.sizeBytes, it.modifiedAtMs) }
        }
    }

    // Ages here are read in minutes and hours, so a one-minute tick is as fine as the display gets.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000L)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ui.launch {
            status = withContext(container.dispatchers.io) {
                runCatching {
                    // Without this the grant dies with the process.
                    ctx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val label = SafFolderDestination.displayName(ctx, uri) ?: "the chosen folder"
                    settings.setBackupFolder(uri.toString(), label)
                    "Folder set to $label"
                }.getOrElse { "Could not use that folder — ${it.message ?: it::class.simpleName}" }
            }
            refresh++
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SafFolderDestination.MIME),
    ) { uri ->
        if (uri == null) { status = "Export cancelled"; return@rememberLauncherForActivityResult }
        busy = "Writing backup…"
        container.appScope.launch {
            val line = runCatching {
                var counts = ArchiveCounts()
                ctx.contentResolver.openOutputStream(uri)?.use { counts = container.backupManager.exportTo(it) }
                    ?: error("could not open the file")
                "Exported ${counts.total} rows"
            }.getOrElse { "Export failed — ${it.message ?: it::class.simpleName}" }
            status = line
            busy = null
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) { status = "Import cancelled"; return@rememberLauncherForActivityResult }
        busy = "Restoring…"
        container.appScope.launch {
            status = restore(container) { ctx.contentResolver.openInputStream(uri) ?: error("could not open the file") }
            busy = null
        }
    }

    BackupScreen(
        state = BackupPanelState(
            folderLabel = folderLabel.takeIf { folderUri != null },
            cadenceHours = cadence,
            cadenceStops = SettingsStore.BACKUP_CADENCE_STOPS,
            keep = keep,
            keepStops = SettingsStore.BACKUP_KEEP_STOPS,
            lastOkAtMs = lastOk?.atMs,
            lastOkBytes = lastOk?.bytes ?: 0L,
            lastOkRows = lastOk?.rows ?: 0,
            lastErrorAtMs = lastError?.atMs,
            lastError = lastError?.message,
            backups = backups,
            busy = busy,
            status = status,
            nowMs = nowMs,
        ),
        onPickFolder = { status = null; folderPicker.launch(null) },
        onSetCadence = { hours ->
            ui.launch {
                settings.setBackupCadenceHours(hours)
                // Re-read rather than trusting `hours`: the setter snaps to a declared stop.
                AutoBackupWorker.sync(ctx, settings.currentBackupCadenceHours())
            }
        },
        onSetKeep = { n -> ui.launch { settings.setBackupKeep(n) } },
        onBackupNow = {
            busy = "Backing up…"
            container.appScope.launch {
                status = runCatching {
                    val run = container.backupManager.runBackup()
                    buildString {
                        append("Backed up ${run.counts.total} rows")
                        if (run.pruned > 0) append(" · ${run.pruned} older removed")
                    }
                }.getOrElse { "Backup failed — ${it.message ?: it::class.simpleName}" }
                busy = null
                refresh++
            }
        },
        onExport = {
            status = null
            exportPicker.launch(SafFolderDestination.fileName(BackupManager.stampFor(System.currentTimeMillis())))
        },
        onImport = { status = null; importPicker.launch(arrayOf("*/*")) },
        onRestore = { entry ->
            busy = "Restoring ${entry.name}…"
            container.appScope.launch {
                status = restore(container) { container.backupManager.open(entry.id) }
                busy = null
            }
        },
        onDelete = { entry ->
            container.appScope.launch {
                status = runCatching {
                    container.backupManager.delete(entry.id)
                    "Deleted ${entry.name}"
                }.getOrElse { "Could not delete — ${it.message ?: it::class.simpleName}" }
                refresh++
            }
        },
    )

    // A restore lands rows behind every panel, so its receipt is raised app-wide.
    LaunchedEffect(status) {
        val line = status
        if (line != null && line.startsWith("Restored")) onNotice(line)
    }
}

private suspend fun restore(container: AppContainer, open: suspend () -> java.io.InputStream): String =
    runCatching {
        val r = container.restoreArchive(open)
        buildString {
            append("Restored ${r.archive.applied.total} rows")
            if (r.archive.duplicates > 0) append(" · ${r.archive.duplicates} already held")
            if (r.archive.skipped > 0) append(" · ${r.archive.skipped} unreadable")
            if (r.settingsKeys > 0) append(" · ${r.settingsKeys} settings")
            if (r.settingsError != null) append(" · settings not restored")
            if (r.archive.truncated) append(" — file was incomplete")
        }
    }.getOrElse { "Restore failed — ${it.message ?: it::class.simpleName}" }
