package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.models.scheduleMinute
import kotlinx.coroutines.flow.drop

internal expect val useClockTimePicker: Boolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    if (!useClockTimePicker) {
        val minute = scheduleMinute(value) ?: 0
        val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
        val onChange by rememberUpdatedState(onValueChange)
        LaunchedEffect(value) {
            scheduleMinute(value)?.let {
                state.hour = it / 60
                state.minute = it % 60
            }
        }
        LaunchedEffect(state) {
            snapshotFlow { state.hour to state.minute }.drop(1).collect { (hour, minute) ->
                onChange("${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}")
            }
        }
        Column(modifier) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 8.dp))
            TimeInput(state)
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedCard(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant)) {
            ListItem(
                headlineContent = { Text(value.ifEmpty { "Select time" }) },
                overlineContent = { Text(label) },
                trailingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
    }
    if (expanded) {
        val minute = scheduleMinute(value) ?: 0
        val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
        var keyboard by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(label) },
            text = { if (keyboard) TimeInput(state) else TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange("${state.hour.toString().padStart(2, '0')}:${state.minute.toString().padStart(2, '0')}")
                    expanded = false
                }) { Text("OK") }
            },
            dismissButton = {
                IconButton(onClick = { keyboard = !keyboard }) {
                    Icon(if (keyboard) Icons.Default.Schedule else Icons.Default.Keyboard,
                        contentDescription = if (keyboard) "Clock picker" else "Keyboard input")
                }
                TextButton(onClick = { expanded = false }) { Text("Cancel") }
            },
        )
    }
}
