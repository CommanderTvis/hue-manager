package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.models.Lamp

@Composable
fun AutomatedLampsDialog(
    lamps: List<Lamp>,
    excludedLampIds: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var pendingExcluded by remember(excludedLampIds) { mutableStateOf(excludedLampIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automated lamps") },
        text = {
            val scrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Turn off lamps that should be controlled by external means (e.g. a motion sensor configured in the official Hue app). Excluded lamps are ignored by automation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (lamps.isEmpty()) {
                        Text(
                            text = "No lamps available.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        lamps.forEach { lamp ->
                            val isAutomated = lamp.id !in pendingExcluded
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(lamp.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = if (isAutomated) "Controlled by automation" else "Excluded from automation",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = isAutomated,
                                    onCheckedChange = { automated ->
                                        pendingExcluded = if (automated) {
                                            pendingExcluded - lamp.id
                                        } else {
                                            pendingExcluded + lamp.id
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                VerticalScrollbarCompat(
                    scrollState = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(pendingExcluded) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
