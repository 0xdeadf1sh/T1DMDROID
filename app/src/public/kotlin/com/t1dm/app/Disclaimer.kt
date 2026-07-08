package com.t1dm.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// PUBLIC flavor: the shipped medical disclaimer.
@Composable
fun Disclaimer() {
    Text(
        text = "Advisory only. Not a medical device. Never dose from this app without " +
            "independent confirmation.",
        modifier = Modifier.padding(12.dp),
    )
}
