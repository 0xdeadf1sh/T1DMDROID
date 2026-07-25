package com.t1dm.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.t1dm.app.di.AppContainer
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.rememberT1dmHaptics
import kotlinx.coroutines.launch

/**
 * PUBLIC flavor: the shipped medical disclaimer, stated ONCE per install.
 *
 * It was a line pinned above the breadcrumb on every screen, which is the shape a disclaimer takes
 * when nobody reads it: permanent chrome is furniture within a day, and it cost a row of height on
 * every route to say something the user had already absorbed. A modal on first run is read exactly
 * once and then honoured, which is what an acknowledgement is for.
 *
 * The gate is non-dismissible — no dismiss button, and `onDismissRequest` deliberately does nothing,
 * so neither Back nor a scrim tap can walk past it. The only way through is the button, and the only
 * thing the button does is persist the acknowledgement.
 *
 * The flag is collected with `true` as its initial value, NOT false: the kv read is asynchronous, and
 * seeding it false would flash this modal at every returning user for the frame or two before Room
 * answers. Erring toward "already acknowledged" costs a first-run user a beat before the dialog
 * appears; erring the other way puts a legal modal in front of someone who dismissed it months ago.
 */
@Composable
fun Disclaimer(container: AppContainer) {
    val acknowledged by container.disclaimerAcknowledged.collectAsState(initial = true)
    if (acknowledged) return

    val haptics = rememberT1dmHaptics()
    LaunchedEffect(Unit) { haptics.perform(HapticEvent.Warn) }
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.perform(HapticEvent.Confirm)
                    // appScope, not a composition scope: this composable leaves the tree the instant
                    // the flag flips, and a write on a scope that dies with it would be cancelled
                    // mid-flight — the dialog would return on the next launch.
                    container.appScope.launch { container.acknowledgeDisclaimer() }
                },
            ) { Text("I understand") }
        },
        title = { Text("Advisory only") },
        text = {
            Text(
                "Not a medical device. Never dose from this app without independent confirmation.",
            )
        },
    )
}
