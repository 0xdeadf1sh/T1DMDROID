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

/** Stated ONCE; empty onDismissRequest blocks Back/scrim; initial=true avoids a flash on return. */
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
                    // appScope: leaving the tree flips the flag, cancelling a tied scope mid-write.
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
