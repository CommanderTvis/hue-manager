package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.api.AutomationModeColorConfig
import io.github.commandertvis.huemanager.models.*
import kotlin.math.roundToInt

@Composable
fun LampScheduleDialog(
    lamp: Lamp,
    current: LampSchedule?,
    timezone: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (LampSchedule?) -> Unit,
) {
    var schedule by remember(lamp.id) {
        mutableStateOf(current ?: LampSchedule(lamp.id, intervals = listOf(newInterval(lamp))))
    }
    val errors = lampScheduleErrors(schedule)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Schedule · ${lamp.name}") },
        text = {
            val scrollState = rememberScrollState()
            val containerColor = AlertDialogDefaults.containerColor
            Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scrollState).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Times use $timezone. Outside these intervals the lamp is off. This schedule runs independently of Lamps on/off and the day/night cycle.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = schedule.enabled, enabled = !saving, onCheckedChange = { schedule = schedule.copy(enabled = it) })
                        Text("Use individual schedule")
                    }
                    Text("Disabling or deleting it returns the lamp to the day/night cycle, unless excluded in Lamps.", style = MaterialTheme.typography.bodySmall)
                    schedule.intervals.forEachIndexed { index, interval ->
                        key(schedule.intervals.size, index) {
                            ScheduleIntervalEditor(
                                interval = interval,
                                lamp = lamp,
                                index = index,
                                errors = errors.filter { it.intervalIndex == index },
                                onChange = { updated ->
                                    if (!saving) schedule = schedule.copy(intervals = schedule.intervals.mapIndexed { i, item -> if (i == index) updated else item })
                                },
                                onRemove = { if (!saving) schedule = schedule.copy(intervals = schedule.intervals.filterIndexed { i, _ -> i != index }) },
                            )
                        }
                    }
                    TextButton(enabled = !saving, onClick = {
                        schedule = schedule.copy(intervals = schedule.intervals + newInterval(lamp).copy(start = schedule.intervals.lastOrNull()?.end.orEmpty()))
                    }) { Text("Add interval") }
                    errors.filter { it.intervalIndex == null }.forEach {
                        Text(it.message, color = MaterialTheme.colorScheme.error)
                    }
                    Text("An interval crossing midnight starts on the selected day. Manual changes last one hour; Hue Sync takes priority.", style = MaterialTheme.typography.bodySmall)
                }
                if (scrollState.canScrollBackward) {
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp)
                        .background(Brush.verticalGradient(listOf(containerColor, containerColor.copy(alpha = 0f)))))
                }
                if (scrollState.canScrollForward) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                        .background(Brush.verticalGradient(listOf(containerColor.copy(alpha = 0f), containerColor))))
                }
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbarCompat(scrollState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && errors.isEmpty(), onClick = { onSave(schedule) }) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(enabled = !saving, onClick = { onSave(null) }) { Text("Delete") }
                TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun newInterval(lamp: Lamp): LampScheduleInterval {
    val useWhite = lamp.colorTemperature != null && lamp.colorMode != ColorMode.HS
    return LampScheduleInterval(
        brightness = ((lamp.brightness ?: 254) * 100.0 / 254).roundToInt().coerceIn(1, 100),
        temperatureKelvin = if (useWhite) lamp.colorTemperature?.let { (1_000_000 / it.coerceAtLeast(1)).coerceIn(2000, 6500) } else null,
        hue = if (useWhite) null else lamp.hue,
        saturation = if (useWhite) null else lamp.saturation,
    )
}

@Composable
private fun ScheduleIntervalEditor(
    interval: LampScheduleInterval,
    lamp: Lamp,
    index: Int,
    errors: List<LampScheduleValidationError>,
    onChange: (LampScheduleInterval) -> Unit,
    onRemove: () -> Unit,
) {
    fun errorFor(field: LampScheduleField) = errors.firstOrNull { it.field == field }?.message
    val startError = errorFor(LampScheduleField.START)
    val endError = errorFor(LampScheduleField.END)
    val temperatureError = errorFor(LampScheduleField.TEMPERATURE)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Interval ${index + 1}", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(value = interval.start, onValueChange = { onChange(interval.copy(start = it)) },
                label = "Start", modifier = Modifier.weight(1f), error = startError)
            TimeField(value = interval.end, onValueChange = { onChange(interval.copy(end = it)) },
                label = "End", modifier = Modifier.weight(1f), error = endError)
        }
        errorFor(LampScheduleField.INTERVAL)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Column {
            listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { day ->
                        FilterChip(
                            selected = day in interval.days,
                            onClick = { onChange(interval.copy(days = if (day in interval.days) interval.days - day else interval.days + day)) },
                            label = { Text(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[day - 1]) },
                        )
                    }
                }
            }
        }
        errorFor(LampScheduleField.DAYS)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Brightness · ${interval.brightness}%")
        Slider(value = interval.brightness.toFloat(), valueRange = 1f..100f,
            onValueChange = { onChange(interval.copy(brightness = it.roundToInt())) })
        errorFor(LampScheduleField.BRIGHTNESS)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (lamp.colorTemperature != null || lamp.hue != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val currentTemperature = lamp.colorTemperature
                if (currentTemperature != null) FilterChip(selected = interval.temperatureKelvin != null,
                    onClick = { onChange(interval.copy(temperatureKelvin = (1_000_000 / currentTemperature.coerceAtLeast(1)).coerceIn(2000, 6500), hue = null, saturation = null)) },
                    label = { Text("White") })
                if (lamp.hue != null) FilterChip(selected = interval.temperatureKelvin == null,
                    onClick = { onChange(interval.copy(temperatureKelvin = null, hue = 0, saturation = 254)) },
                    label = { Text("RGB") })
            }
            errorFor(LampScheduleField.COLOR)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val kelvin = interval.temperatureKelvin
            if (kelvin != null) {
                OutlinedTextField(value = if (kelvin == 0) "" else kelvin.toString(),
                    onValueChange = { onChange(interval.copy(temperatureKelvin = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                    label = { Text("Temperature K · 2000–6500") }, singleLine = true,
                    isError = temperatureError != null,
                    supportingText = temperatureError?.let { message -> { Text(message) } })
            } else {
                key(index, "rgb") {
                    ModeColorPickerContent(
                        showBrightness = false,
                        config = AutomationModeColorConfig(hue = interval.hue, saturation = interval.saturation,
                            brightness = (interval.brightness * 254.0 / 100).roundToInt().coerceAtLeast(1)),
                        onConfigChange = { color -> onChange(interval.copy(hue = color.hue, saturation = color.saturation,
                            brightness = (color.brightness * 100.0 / 254).roundToInt().coerceIn(1, 100))) },
                    )
                }
            }
        } else {
            Text("This lamp uses its built-in white light.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
