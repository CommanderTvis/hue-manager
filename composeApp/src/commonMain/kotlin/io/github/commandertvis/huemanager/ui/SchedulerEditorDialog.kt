package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.skydoves.colorpicker.compose.AlphaTile
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import io.github.commandertvis.huemanager.api.AutomationModeColorConfig

data class SchedulerSettings(
    val pseudoSunset: String,
    val nightTime: String,
    val daylightColor: AutomationModeColorConfig,
    val eveningColor: AutomationModeColorConfig,
    val nightColor: AutomationModeColorConfig,
)

@Composable
fun SchedulerEditorDialog(
    currentSettings: SchedulerSettings,
    onDismiss: () -> Unit,
    onSave: (SchedulerSettings) -> Unit,
) {
    var pseudoSunsetHour by remember { mutableStateOf(currentSettings.pseudoSunset.substringBefore(":")) }
    var pseudoSunsetMinute by remember { mutableStateOf(currentSettings.pseudoSunset.substringAfter(":")) }
    var nightTimeHour by remember { mutableStateOf(currentSettings.nightTime.substringBefore(":")) }
    var nightTimeMinute by remember { mutableStateOf(currentSettings.nightTime.substringAfter(":")) }

    var daylightConfig by remember { mutableStateOf(currentSettings.daylightColor) }
    var eveningConfig by remember { mutableStateOf(currentSettings.eveningColor) }
    var nightConfig by remember { mutableStateOf(currentSettings.nightColor) }

    var expandedMode by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Editor") },
        text = {
            val scrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                // Pseudo-sunset time
                Text(
                    text = "Evening time",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = pseudoSunsetHour,
                        onValueChange = { v ->
                            val filtered = v.filter { it.isDigit() }.take(2)
                            if (filtered.isEmpty() || filtered.toIntOrNull()?.let { it in 0..23 } == true) {
                                pseudoSunsetHour = filtered
                            }
                        },
                        label = { Text("HH") },
                        singleLine = true,
                        modifier = Modifier.width(72.dp),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = pseudoSunsetMinute,
                        onValueChange = { v ->
                            val filtered = v.filter { it.isDigit() }.take(2)
                            if (filtered.isEmpty() || filtered.toIntOrNull()?.let { it in 0..59 } == true) {
                                pseudoSunsetMinute = filtered
                            }
                        },
                        label = { Text("MM") },
                        singleLine = true,
                        modifier = Modifier.width(72.dp),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                }

                // Night time
                Text(
                    text = "Night time",
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = nightTimeHour,
                        onValueChange = { v ->
                            val filtered = v.filter { it.isDigit() }.take(2)
                            if (filtered.isEmpty() || filtered.toIntOrNull()?.let { it in 0..23 } == true) {
                                nightTimeHour = filtered
                            }
                        },
                        label = { Text("HH") },
                        singleLine = true,
                        modifier = Modifier.width(72.dp),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = nightTimeMinute,
                        onValueChange = { v ->
                            val filtered = v.filter { it.isDigit() }.take(2)
                            if (filtered.isEmpty() || filtered.toIntOrNull()?.let { it in 0..59 } == true) {
                                nightTimeMinute = filtered
                            }
                        },
                        label = { Text("MM") },
                        singleLine = true,
                        modifier = Modifier.width(72.dp),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                }

                HorizontalDivider()

                // Daylight mode color
                ModeColorEditor(
                    label = "Daylight",
                    description = "Sun down, before evening",
                    config = daylightConfig,
                    isExpanded = expandedMode == "daylight",
                    onExpandToggle = { expandedMode = if (expandedMode == "daylight") null else "daylight" },
                    onConfigChange = { daylightConfig = it }
                )

                // Evening mode color
                ModeColorEditor(
                    label = "Evening",
                    description = "Evening time to night time",
                    config = eveningConfig,
                    isExpanded = expandedMode == "evening",
                    onExpandToggle = { expandedMode = if (expandedMode == "evening") null else "evening" },
                    onConfigChange = { eveningConfig = it }
                )

                // Night mode color
                ModeColorEditor(
                    label = "Night",
                    description = "After evening until sunrise",
                    config = nightConfig,
                    isExpanded = expandedMode == "night",
                    onExpandToggle = { expandedMode = if (expandedMode == "night") null else "night" },
                    onConfigChange = { nightConfig = it }
                )
            }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val hour = pseudoSunsetHour.padStart(2, '0')
                    val minute = pseudoSunsetMinute.padStart(2, '0')
                    val nHour = nightTimeHour.padStart(2, '0')
                    val nMinute = nightTimeMinute.padStart(2, '0')
                    onSave(
                        SchedulerSettings(
                            pseudoSunset = "$hour:$minute",
                            nightTime = "$nHour:$nMinute",
                            daylightColor = daylightConfig,
                            eveningColor = eveningConfig,
                            nightColor = nightConfig,
                        )
                    )
                },
                enabled = pseudoSunsetHour.isNotEmpty() && pseudoSunsetMinute.isNotEmpty()
                        && nightTimeHour.isNotEmpty() && nightTimeMinute.isNotEmpty()
            ) {
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
private fun ModeColorEditor(
    label: String,
    description: String,
    config: AutomationModeColorConfig,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onConfigChange: (AutomationModeColorConfig) -> Unit,
) {
    val previewColor = configToColor(config)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(previewColor)
                )
                Column {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TextButton(onClick = onExpandToggle) {
                Text(if (isExpanded) "Collapse" else "Edit")
            }
        }

        if (isExpanded) {
            ModeColorPickerContent(config = config, onConfigChange = onConfigChange)
        }
    }
}

@Composable
private fun ModeColorPickerContent(
    config: AutomationModeColorConfig,
    onConfigChange: (AutomationModeColorConfig) -> Unit,
) {
    val controller = rememberColorPickerController()
    var hexCode by remember { mutableStateOf(configToHex(config)) }
    var brightnessSlider by remember { mutableStateOf(config.brightness.toFloat()) }

    // Initialize controller
    LaunchedEffect(Unit) {
        controller.selectByColor(configToColor(config), true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Brightness slider
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Brightness",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(72.dp)
            )
            Slider(
                value = brightnessSlider,
                onValueChange = { brightnessSlider = it },
                onValueChangeFinished = {
                    onConfigChange(config.copy(brightness = brightnessSlider.toInt()))
                },
                valueRange = 1f..254f,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(brightnessSlider / 254f * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(36.dp)
            )
        }

        // Color picker + hex input
        Row(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                HsvColorPicker(
                    modifier = Modifier.fillMaxSize(),
                    controller = controller,
                    onColorChanged = { envelope ->
                        hexCode = envelope.hexCode.let { if (it.length == 8) it.substring(2) else it }
                        val color = envelope.color
                        val (hue, sat) = rgbToHueApiValues(color.red, color.green, color.blue)
                        onConfigChange(
                            config.copy(
                                hue = hue,
                                saturation = sat,
                                colorTemperature = null,
                                brightness = brightnessSlider.toInt()
                            )
                        )
                    }
                )
            }

            Column(
                modifier = Modifier.width(100.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AlphaTile(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    controller = controller
                )

                OutlinedTextField(
                    value = hexCode,
                    onValueChange = { newValue ->
                        val filtered = newValue.filter { it.isHexChar() }.take(6)
                        hexCode = filtered
                        if (filtered.length == 6) {
                            try {
                                val (r, g, b) = hexToRgb(filtered)
                                controller.selectByColor(Color(r, g, b), true)
                            } catch (_: IllegalArgumentException) {}
                        }
                    },
                    label = { Text("Hex") },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("RRGGBB") }
                )
            }
        }
    }
}

private fun configToColor(config: AutomationModeColorConfig): Color {
    val hue = config.hue
    val sat = config.saturation
    val ct = config.colorTemperature

    return when {
        hue != null && sat != null -> {
            val h = (hue / 65535f) * 360f
            val s = sat / 254f
            val v = config.brightness / 254f
            Color.hsv(h, s, v.coerceAtLeast(0.3f)) // min brightness for visibility
        }
        ct != null -> {
            val warmth = (ct - 153f) / (500f - 153f)
            Color(
                red = 1f,
                green = 0.8f + (1f - warmth) * 0.2f,
                blue = 0.6f + (1f - warmth) * 0.4f
            )
        }
        else -> Color(0xFFFFE4B5)
    }
}

private fun configToHex(config: AutomationModeColorConfig): String {
    val color = configToColor(config)
    val r = (color.red * 255).toInt().coerceIn(0, 255)
    val g = (color.green * 255).toInt().coerceIn(0, 255)
    val b = (color.blue * 255).toInt().coerceIn(0, 255)
    return r.toString(16).padStart(2, '0').uppercase() +
           g.toString(16).padStart(2, '0').uppercase() +
           b.toString(16).padStart(2, '0').uppercase()
}

// Duplicated from LampCard since they're private there
private fun Char.isHexChar(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun hexToRgb(hex: String): Triple<Float, Float, Float> {
    require(hex.length == 6) { "Hex must be 6 characters" }
    val r = hex.substring(0, 2).toInt(16) / 255f
    val g = hex.substring(2, 4).toInt(16) / 255f
    val b = hex.substring(4, 6).toInt(16) / 255f
    return Triple(r, g, b)
}

private fun rgbToHueApiValues(r: Float, g: Float, b: Float): Pair<Int, Int> {
    val max = maxOf(r, maxOf(g, b))
    val min = minOf(r, minOf(g, b))
    val d = max - min

    var h = 0f
    val s = if (max == 0f) 0f else d / max

    if (max != min) {
        h = when (max) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            b -> (r - g) / d + 4f
            else -> 0f
        }
        h /= 6f
    }

    val hue = (h * 65535).toInt()
    val sat = (s * 254).toInt()
    return Pair(hue, sat)
}
