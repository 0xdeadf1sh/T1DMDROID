package com.t1dm.feature.cgm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.SignalBars
import com.t1dm.core.design.fadingEdges
import com.t1dm.core.design.panelCardColors
import com.t1dm.core.design.rememberT1dmHaptics
import com.t1dm.core.model.CgmSourceStatus
import com.t1dm.core.model.CgmSourceTelemetry
import com.t1dm.core.model.statusWord
import kotlin.math.abs

/** Per sensor, one card. Unreportable channel = absent, not zero/dash. No privacy toggle here. */
@Composable
fun CgmScreen(
    state: CgmPanelState = CgmPanelState(),
    onMakeAuthoritative: (String) -> Unit = {},
    /** Not [onActivate], which runs the sensor's own activation. */
    onStartReading: (String) -> Unit = {},
    /** Keeps it on the list; the reversible half of ✕. */
    onStopReading: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onActivate: (String) -> Unit = {},
    /** Claims an unclaimed sensor. Irreversible. */
    onBind: (String) -> Unit = {},
    /** Sweeps for sensors now rather than on the radio's own schedule. */
    onScan: () -> Unit = {},
    /** Tears the sensor's link down and looks for it afresh. */
    onReconnect: (String) -> Unit = {},
    /** Asks the sensor's own store for what the phone lacks. */
    onFetchHistory: (String) -> Unit = {},
    /** Searches for the key this sensor's records actually decode under. */
    onRecoverKey: (String) -> Unit = {},
    /** Re-dates the wear and drops every reading the wrong dates produced. */
    onRepairHistory: (String) -> Unit = {},
    /** Opens the NFC provision sheet for one sighting. */
    onProvision: (String) -> Unit = {},
    onOpenLog: (String) -> Unit = {},
) {
    // Outside the cards: a card recomposes on the live status and would tear its own dialog down.
    var confirming by remember { mutableStateOf<CgmConfirm?>(null) }
    val scroll = rememberScrollState()
    val haptics = rememberT1dmHaptics()
    Column(
        Modifier.fillMaxSize().fadingEdges(scroll).verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val nowMs by produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                kotlinx.coroutines.delay(COUNTDOWN_TICK_MS)
            }
        }

        OutlinedButton(
            onClick = { haptics.perform(HapticEvent.Tap); onScan() },
            enabled = !state.scanning,
        ) { Text(if (state.scanning) "Scanning" else "Scan") }

        // Bind flag rides a scan response absent at this range; else the panel stays silent.
        if (state.unidentified > 0) {
            Text(
                state.unidentifiedRssiDbm
                    ?.let { "${state.unidentified} heard at $it dBm, unidentified" }
                    ?: "${state.unidentified} heard, unidentified",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.sensors.isEmpty()) {
            Text("no sensors yet", style = MaterialTheme.typography.bodyMedium)
        } else {
            state.sensors.forEach { row ->
                SensorCard(
                    row = row,
                    nowMs = nowMs,
                    maxSessions = state.maxSessions,
                    onMakeAuthoritative = onMakeAuthoritative,
                    onStartReading = onStartReading,
                    onReconnect = onReconnect,
                    onFetchHistory = onFetchHistory,
                    onRecoverKey = onRecoverKey,
                    onRepairHistory = onRepairHistory,
                    onProvision = onProvision,
                    onOpenLog = onOpenLog,
                    onRequest = { confirming = it },
                )
            }
        }

        LaunchedEffect(state.lastError) {
            if (state.lastError != null) haptics.perform(HapticEvent.Warn)
        }
        state.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        confirming?.let { pending ->
            CgmConfirmDialog(
                pending = pending,
                onConfirm = {
                    confirming = null
                    when (pending) {
                        is CgmConfirm.Activate -> onActivate(pending.row.id)
                        is CgmConfirm.Bind -> onBind(pending.row.id)
                        is CgmConfirm.RecoverKey -> onRecoverKey(pending.row.id)
                        is CgmConfirm.RepairHistory -> onRepairHistory(pending.row.id)
                        is CgmConfirm.Provision -> onProvision(pending.row.id)
                        is CgmConfirm.Remove -> onRemove(pending.row.id)
                        is CgmConfirm.StopReading -> onStopReading(pending.row.id)
                    }
                },
                onDismiss = { confirming = null },
            )
        }
    }
}

/** Carries the row, not an id: the list is rebuilt from the registry under the open dialog. */
private sealed interface CgmConfirm {
    val row: CgmSensorRow

    data class Activate(override val row: CgmSensorRow) : CgmConfirm
    data class Bind(override val row: CgmSensorRow) : CgmConfirm
    data class RecoverKey(override val row: CgmSensorRow) : CgmConfirm
    data class RepairHistory(override val row: CgmSensorRow) : CgmConfirm
    data class Provision(override val row: CgmSensorRow) : CgmConfirm
    data class Remove(override val row: CgmSensorRow) : CgmConfirm
    data class StopReading(override val row: CgmSensorRow) : CgmConfirm
}

@Composable
private fun CgmConfirmDialog(pending: CgmConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    LaunchedEffect(pending) { haptics.perform(HapticEvent.Warn) }
    val title: String
    val body: String?
    val accept: String
    when (pending) {
        is CgmConfirm.Activate -> { title = "Activate sensor?"; body = null; accept = "Activate" }
        is CgmConfirm.Bind -> {
            title = "Bind ${pending.row.name}?"
            body = "Can't be undone — the sensor is claimed for good"
            accept = "Bind"
        }
        is CgmConfirm.RepairHistory -> {
            title = "Repair ${pending.row.name}?"
            body = "Deletes its readings and re-dates the wear from arrival times"
            accept = "Repair"
        }
        is CgmConfirm.Provision -> {
            title = "Provision ${pending.row.name}?"
            body = "Hold the phone to the sensor; this app becomes its reader"
            accept = "Provision"
        }
        is CgmConfirm.RecoverKey -> {
            title = "Recover key for ${pending.row.name}?"
            body = "Deletes its readings and replaces the key"
            accept = "Recover"
        }
        is CgmConfirm.Remove -> {
            title = "Remove ${pending.row.name}?"; body = "Readings are kept"; accept = "Remove"
        }
        is CgmConfirm.StopReading -> {
            title = "Stop reading ${pending.row.name}?"; body = null; accept = "Stop"
        }
    }
    AlertDialog(
        onDismissRequest = { haptics.perform(HapticEvent.Reject); onDismiss() },
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Commit); onConfirm() }) { Text(accept) }
        },
        dismissButton = {
            TextButton(onClick = { haptics.perform(HapticEvent.Reject); onDismiss() }) { Text("Cancel") }
        },
    )
}

/** Deliberately not itself a control: promotion is the named button below. */
@Composable
private fun SensorCard(
    row: CgmSensorRow,
    nowMs: Long,
    maxSessions: Int,
    onMakeAuthoritative: (String) -> Unit,
    onStartReading: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onFetchHistory: (String) -> Unit,
    onRecoverKey: (String) -> Unit,
    onRepairHistory: (String) -> Unit,
    onProvision: (String) -> Unit,
    onOpenLog: (String) -> Unit,
    onRequest: (CgmConfirm) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberT1dmHaptics()
    Card(Modifier.fillMaxWidth(), colors = panelCardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val dim = LocalContentColor.current.copy(alpha = 0.7f)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (row.authoritative) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(row.ordinalLabel, style = MaterialTheme.typography.labelSmall, color = dim)
                }
                Text(
                    when {
                        row.authoritative -> "main"
                        row.active -> "use"
                        else -> "idle"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.authoritative) cs.primary else dim,
                )
                // X is two-stage: stops reading first, delists only once stopped. Both halves ask.
                if (row.authoritative) {
                    Spacer(Modifier.size(REMOVE_SLOT))
                } else {
                    val stops = row.active
                    IconButton(
                        onClick = {
                            haptics.perform(HapticEvent.Tap)
                            onRequest(
                                if (stops) CgmConfirm.StopReading(row) else CgmConfirm.Remove(row),
                            )
                        },
                        // No TTS voice speaks U+2715; the button would announce as unlabelled.
                        modifier = Modifier.size(REMOVE_SLOT).semantics {
                            contentDescription =
                                if (stops) "Stop reading ${row.name}" else "Remove ${row.name}"
                        },
                    ) {
                        Text(
                            "✕",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (stops) dim else cs.error,
                        )
                    }
                }
            }

            KvWord("State", statusWord(row.status))
            // §15 taxonomy: the row names what failed, not just that it failed.
            row.failureNote?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
            // The budget drops the excess silently; nothing else on screen would say why.
            if (!row.admitted) {
                Text(
                    "waiting — $maxSessions links in use",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Signal", style = MaterialTheme.typography.bodySmall, color = dim)
                if (row.rssiDbm != null) {
                    SignalBars(row.rssiDbm)
                } else {
                    Text("no signal", style = MaterialTheme.typography.bodySmall, color = dim)
                }
            }
            row.sensorAgeMin?.let { Kv("Sensor age", fullDuration(it.toLong() * 60_000L)) }
            row.expiryMs?.let {
                val remainingMs = it - nowMs
                Kv("Time left", if (remainingMs <= 0L) "expired" else fullDuration(remainingMs))
            }
            row.ratedCycleDays?.let { Kv("Rated cycle", "$it days") }
            TelemetryRows(row.telemetry)

            Row(
                Modifier
                    .padding(top = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Every sensor has a log, so it leads the row: in view without scrolling.
                OutlinedButton(
                    onClick = { haptics.perform(HapticEvent.Tap); onOpenLog(row.id) },
                ) { Text("Log") }
                if (!row.authoritative) {
                    Button(
                        onClick = {
                            haptics.perform(HapticEvent.Confirm)
                            if (row.active) onMakeAuthoritative(row.id) else onStartReading(row.id)
                        },
                    ) { Text(if (row.active) "Use as main" else "Start reading") }
                }
                if (row.canBind) {
                    Button(
                        onClick = {
                            haptics.perform(HapticEvent.Tap); onRequest(CgmConfirm.Bind(row))
                        },
                    ) { Text("Bind") }
                }
                if (row.canReconnect) {
                    Button(
                        onClick = { haptics.perform(HapticEvent.Tap); onReconnect(row.id) },
                    ) { Text("Reconnect") }
                }
                if (row.hasRecoverKey) {
                    Button(
                        onClick = {
                            haptics.perform(HapticEvent.Tap); onRequest(CgmConfirm.RecoverKey(row))
                        },
                        enabled = row.canRecoverKey,
                    ) { Text("Recover key") }
                }
                if (row.hasRepairHistory) {
                    OutlinedButton(
                        onClick = {
                            haptics.perform(HapticEvent.Tap); onRequest(CgmConfirm.RepairHistory(row))
                        },
                        enabled = row.canRepairHistory,
                    ) { Text("Repair history") }
                }
                if (row.hasProvision) {
                    OutlinedButton(
                        onClick = {
                            haptics.perform(HapticEvent.Tap); onRequest(CgmConfirm.Provision(row))
                        },
                        enabled = row.canProvision,
                    ) { Text("Provision") }
                }
                if (row.hasHistory) {
                    OutlinedButton(
                        onClick = { haptics.perform(HapticEvent.Tap); onFetchHistory(row.id) },
                        enabled = row.canFetchHistory && !row.fetchingHistory,
                    ) {
                        if (row.fetchingHistory) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else if (row.historyExhausted) {
                            Text("Sensor storage full — no history")
                        } else {
                            Text("Fetch history")
                        }
                    }
                }
                Button(
                    onClick = {
                        haptics.perform(HapticEvent.Tap); onRequest(CgmConfirm.Activate(row))
                    },
                    enabled = row.hasActivate && row.canActivate,
                ) { Text("Activate") }
            }
        }
    }
}

/** Iw/Ib: no unit, byte-exact vs vendor, unit inferred. Electrode order is family's own. */
@Composable
private fun TelemetryRows(t: CgmSourceTelemetry?) {
    if (t == null) return
    t.tempCx100?.let { Kv("Temp", "${hundredths(it)} °C") }
    t.batteryRaw?.let { Kv("Battery", it.toString()) }
    t.iwX100?.let { Kv("Iw", hundredths(it)) }
    t.ibX100?.let { Kv("Ib", hundredths(it)) }
    t.electrodesMv?.takeIf { it.isNotEmpty() }?.let { mv ->
        Kv("Electrodes", remember(mv) { mv.joinToString("/") } + " mV")
    }
    // Shown even at zero: a reported "no error" is a fact.
    t.errorCode?.let { Kv("Error", it.toString()) }
}

/** Fixed two decimals: `3104` ⇒ `31.04`, negatives included. */
internal fun hundredths(x100: Int): String {
    val sign = if (x100 < 0) "-" else ""
    val magnitude = abs(x100)
    return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
}

private val REMOVE_SLOT = 40.dp

/** Only has to be finer than the minute resolution "time left" is drawn at. */
private const val COUNTDOWN_TICK_MS = 30_000L

@Composable
private fun Kv(key: String, value: String) = KvRow(key, value, numeric = true)

@Composable
private fun KvWord(key: String, value: String) = KvRow(key, value, numeric = false)

@Composable
private fun KvRow(key: String, value: String, numeric: Boolean) {
    com.t1dm.core.design.KeyValueRow(
        key, value, numeric = numeric,
        labelStyle = MaterialTheme.typography.bodySmall,
        valueStyle = MaterialTheme.typography.bodySmall,
    )
}

/** All non-zero units, e.g. "9 d 3 h 20 m"; minutes resolution. */
internal fun fullDuration(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val d = totalMin / 1440
    val h = (totalMin % 1440) / 60
    val m = totalMin % 60
    return buildList {
        if (d > 0) add("$d d")
        if (h > 0) add("$h h")
        if (m > 0 || isEmpty()) add("$m m")
    }.joinToString(" ")
}

/** Exactly one row is authoritative, and it is always also active. */
data class CgmSensorRow(
    val id: String,
    val name: String,
    /** What the rest of the app calls it while names are hidden, e.g. `CGM #0`. */
    val ordinalLabel: String,
    val active: Boolean,
    val authoritative: Boolean,
    val status: CgmSourceStatus = CgmSourceStatus.Idle,
    val rssiDbm: Int? = null,
    /** Minutes since activation (the reading's `minFromStart`). */
    val sensorAgeMin: Int? = null,
    /** Reading instant + (stated wear, else family rating) - age; null if any is unknown. */
    val expiryMs: Long? = null,
    val warmupWindowMin: Int = 0,
    /** Null when the family states none. Shown only; [expiryMs] carries the countdown. */
    val ratedCycleDays: Int? = null,
    /** Null for a source that reports none, and before its first record. */
    val telemetry: CgmSourceTelemetry? = null,
    /** Unclaimed and answering. Binding is irreversible. */
    val canBind: Boolean = false,
    /** hasActivate=frame exists; canActivate=press honored now. Enabled only if both. */
    val hasActivate: Boolean = false,
    val canActivate: Boolean = false,
    /** Family keeps a sensor-side store; canFetchHistory is whether a session can ask it now. */
    val hasHistory: Boolean = false,
    val canFetchHistory: Boolean = false,
    /** A fetch round trip is running; the button shows a spinner and refuses re-taps. */
    val fetchingHistory: Boolean = false,
    /** The sensor's counter stopped: its store holds nothing newer, so the fetch is disabled. */
    val historyExhausted: Boolean = false,
    /** §15: the session's classified last failure; drawn under the state, null when nothing. */
    val failureNote: String? = null,
    /** Being read, with no link held. */
    val canReconnect: Boolean = false,
    /** Records here decoded to nothing physical, so the key it holds can be searched for. */
    val hasRecoverKey: Boolean = false,
    val canRecoverKey: Boolean = false,
    /** Its wear can be re-dated and its readings pulled again. Destructive. */
    val hasRepairHistory: Boolean = false,
    val canRepairHistory: Boolean = false,
    /** The family provisions this sensor over NFC; the tap runs after the confirm. */
    val hasProvision: Boolean = false,
    val canProvision: Boolean = false,
    /** The console's encrypt and decrypt; they need a live data plane besides. */
    val hasFrameCrypto: Boolean = false,
    /** False when the radio budget cannot carry a sensor the user switched on. */
    val admitted: Boolean = true,
)

/** Feature-local projection of the registry; keeps `:feature:cgm` free of a `:cgm` dependency. */
data class CgmPanelState(
    val sensors: List<CgmSensorRow> = emptyList(),
    /** Concurrent links the radio budget allows. */
    val maxSessions: Int = 0,
    /** A search the user asked for is still running. */
    val scanning: Boolean = false,
    /** Sensors heard but not yet said claimed-or-not by advert; can't be offered yet. */
    val unidentified: Int = 0,
    /** The strongest of them. */
    val unidentifiedRssiDbm: Int? = null,
    val lastError: String? = null,
)
