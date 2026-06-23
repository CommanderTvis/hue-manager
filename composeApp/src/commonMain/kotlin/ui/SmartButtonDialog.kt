package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.api.SensorInfo

private val SWITCH_TYPES = setOf("ZLLSwitch", "ZGPSwitch", "ZLLRelativeRotary")

@Composable
fun SmartButtonDialog(
    sensors: List<SensorInfo>,
    selectedSensorId: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }

    var pendingSelection by remember(selectedSensorId) { mutableStateOf(selectedSensorId) }

    val switches = sensors.filter { it.type in SWITCH_TYPES }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Smart button") },
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
                        text = "Pick a physical Hue switch or smart button to toggle lamps on/off. Any button press on the selected device will toggle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    SensorOption(
                        label = "None",
                        sublabel = "Disable smart-button toggle",
                        selected = pendingSelection == null,
                        onSelect = { pendingSelection = null },
                    )

                    if (switches.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No switches or smart buttons detected on this bridge.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        switches.forEach { sensor ->
                            val sublabel = buildString {
                                append(sensor.productName ?: sensor.modelId ?: sensor.type)
                                sensor.battery?.let { append(" • Battery $it%") }
                                if (!sensor.reachable) append(" • Unreachable")
                            }
                            SensorOption(
                                label = sensor.name,
                                sublabel = sublabel,
                                selected = pendingSelection == sensor.id,
                                onSelect = { pendingSelection = sensor.id },
                            )
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
            TextButton(onClick = { onSave(pendingSelection) }) {
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

@Composable
private fun SensorOption(
    label: String,
    sublabel: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sublabel.isNotEmpty()) {
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
