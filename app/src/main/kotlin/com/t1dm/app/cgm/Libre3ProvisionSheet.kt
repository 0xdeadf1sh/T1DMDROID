package com.t1dm.app.cgm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.t1dm.cgm.AndroidLibre3NfcVLink
import com.t1dm.cgm.Libre3NfcProvision
import com.t1dm.cgm.Libre3Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ACCOUNT_SHAPE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * NFC provision flow for a Libre 3 sighting: account ID + region → fold confirmation → listening.
 * Listening retries until the phone finds the sensor's tag, so Start can be pressed anywhere and
 * the phone brought to the arm afterwards. Account/region persist for the next sensor.
 */
@Composable
fun Libre3ProvisionSheet(
    name: String,
    onDismiss: () -> Unit,
    prefs: suspend () -> Pair<String?, String?>,
    onPersist: suspend (accountId: String, region: String) -> Unit,
    provision: suspend (accountId: String, region: Libre3Region, Libre3NfcProvision.NfcVLink) ->
        Libre3NfcProvision.Outcome,
) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? android.app.Activity
    // The phone rests on the sensor's back for a minute; the screen must not sleep there.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var accountId by remember { mutableStateOf("") }
    var regionEu by remember { mutableStateOf(true) }
    var listening by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val (savedId, savedRegion) = prefs()
        if (!savedId.isNullOrBlank()) accountId = savedId
        if (savedRegion == "us") regionEu = false
    }
    val normalized = accountId.trim().lowercase()
    val idValid = ACCOUNT_SHAPE.matches(normalized)
    val region = if (regionEu) Libre3Region.Eu else Libre3Region.Us
    val fold = if (idValid) {
        remember(normalized, regionEu) {
            com.t1dm.cgm.UniffiLibre3Native().receiverId(normalized, region)
        }
    } else {
        null
    }

    LaunchedEffect(listening, idValid, regionEu) {
        if (!listening || !idValid) return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        while (isActive && !done) {
            status = "Hold the phone to the sensor"
            val outcome = withContext(Dispatchers.IO) {
                provision(normalized, region, AndroidLibre3NfcVLink(act))
            }
            when (outcome) {
                is Libre3NfcProvision.Outcome.Provisioned -> {
                    status = "Provisioned ${outcome.state.serial} at " +
                        outcome.state.bleAddressDisplay
                    done = true
                    onPersist(normalized, region.name)
                }
                Libre3NfcProvision.Outcome.AccountMismatch -> {
                    status = "Account/region mismatch (0xB1)"
                    listening = false
                }
                is Libre3NfcProvision.Outcome.Failed ->
                    status = "${outcome.reason} — hold the phone to the sensor again"
            }
            if (!done && listening) delay(4_000L)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!listening) onDismiss() },
        title = { Text("Provision $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = accountId,
                    onValueChange = { accountId = it },
                    label = { Text("Account ID (dashed UUID)") },
                    singleLine = true,
                    enabled = !listening && !done,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = regionEu,
                        onClick = { regionEu = true },
                        enabled = !listening && !done,
                        label = { Text("EU") },
                    )
                    FilterChip(
                        selected = !regionEu,
                        onClick = { regionEu = false },
                        enabled = !listening && !done,
                        label = { Text("US") },
                    )
                }
                if (idValid && fold != null) {
                    Text(
                        "fold %08x".format(fold.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Button(
                    onClick = { listening = true },
                    enabled = idValid && !listening && !done && activity != null,
                ) {
                    Text(if (listening) "Listening — hold the phone to the sensor" else "Start listening")
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { listening = false },
                enabled = if (listening) true else !done,
            ) {
                Text(
                    when {
                        listening -> "Stop"
                        done -> "Close"
                        else -> "Cancel"
                    }
                )
            }
        },
    )
}