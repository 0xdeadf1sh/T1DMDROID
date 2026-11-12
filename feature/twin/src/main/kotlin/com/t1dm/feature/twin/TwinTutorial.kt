package com.t1dm.feature.twin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private class Step(val title: String, val body: String)

private val STEPS = listOf(
    Step("SCAN", "Scan your face to build your twin"),
    Step("ORBIT", "Drag to turn it · pinch to zoom"),
    Step("TUNE", "Edit reshapes it · Trends tracks each scan"),
)

/** Three cards over a dimmed Twin; consumes taps so the scene beneath stays still. */
@Composable
internal fun TwinTutorial(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var step by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background.copy(alpha = 0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .border(1.dp, cs.primary.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .background(cs.surface.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                .padding(20.dp),
        ) {
            AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tutorial") { i ->
                Column {
                    Text("${i + 1}/${STEPS.size} · ${STEPS[i].title}", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                    Spacer(Modifier.height(8.dp))
                    Text(STEPS[i].body, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    STEPS.indices.forEach { i ->
                        Box(
                            Modifier.size(6.dp).background(
                                if (i == step) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape,
                            ),
                        )
                    }
                }
                Row {
                    if (step < STEPS.lastIndex) TextButton(onClick = onDone) { Text("Skip") }
                    TextButton(onClick = { if (step < STEPS.lastIndex) step++ else onDone() }) {
                        Text(if (step < STEPS.lastIndex) "Next" else "Done")
                    }
                }
            }
        }
    }
}
