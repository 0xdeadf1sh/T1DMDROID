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

/** Stated ONCE per install. `onDismissRequest` is empty deliberately: neither Back nor a scrim tap
 *  may walk past it. `initial = true` because the kv read is async — flashing this at a returning
 *  user is the worse error. */
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
                    // appScope: this composable leaves the tree the instant the flag flips, and a
                    // write on a scope that dies with it would be cancelled mid-flight.
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
