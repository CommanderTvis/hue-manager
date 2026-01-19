package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.colorpicker.AlphaTile
import io.github.commandertvis.huemanager.colorpicker.HsvColorPicker
import io.github.commandertvis.huemanager.colorpicker.rememberColorPickerController
import io.github.commandertvis.huemanager.models.Lamp

@Composable
fun LampCard(
    lamp: Lamp,
    isOverridden: Boolean,
    isLoading: Boolean = false,
    onToggle: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onColorChange: ((Int, Int) -> Unit)? = null,
    onClearOverride: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember(lamp.brightness) {
        mutableStateOf((lamp.brightness ?: 254).toFloat())
    }
    
    var isColorPickerExpanded by remember { mutableStateOf(false) }
    val controller = rememberColorPickerController()
    var hexCode by remember { mutableStateOf("") }

    // Update controller enabled state based on loading
    LaunchedEffect(isLoading) {
        controller.enabled = !isLoading
    }

    // Initialize controller with lamp color if available
    LaunchedEffect(isColorPickerExpanded) {
        if (isColorPickerExpanded) {
            val color = getLampColor(lamp)
            controller.selectByColor(color, true)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .alpha(if (isLoading) 0.5f else 1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status indicator
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !lamp.reachable -> Color.Gray
                                    lamp.on -> getLampColor(lamp)
                                    else -> Color.DarkGray
                                }
                            )
                    )

                    Column {
                        Text(
                            text = lamp.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = when {
                                !lamp.reachable -> "Unreachable"
                                lamp.on -> "On (${((lamp.brightness ?: 254) * 100 / 254)}%)"
                                else -> "Off"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lamp.on && lamp.reachable && onColorChange != null) {
                        IconButton(
                            onClick = { isColorPickerExpanded = !isColorPickerExpanded },
                            enabled = !isLoading
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Color Picker",
                                tint = if (isColorPickerExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Switch(
                        checked = lamp.on,
                        onCheckedChange = { onToggle() },
                        enabled = lamp.reachable && !isLoading
                    )
                }
            }

            // Brightness slider (only show when lamp is on and reachable)
            if (lamp.on && lamp.reachable && lamp.brightness != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Brightness",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(72.dp)
                    )

                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            onBrightnessChange(sliderValue.toInt())
                        },
                        valueRange = 1f..254f,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    )

                    Text(
                        text = "${(sliderValue * 100 / 254).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(36.dp)
                    )
                }
            }
            
            // Color Picker
            if (isColorPickerExpanded && onColorChange != null && lamp.on && lamp.reachable) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("Color Control", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        HsvColorPicker(
                            modifier = Modifier.fillMaxSize(),
                            controller = controller,
                            onColorChanged = { envelope ->
                                hexCode = envelope.hexCode.rgbHex

                                // Apply color immediately when picker is used
                                val color = envelope.color
                                val (hue, sat) = rgbToHueApiValues(color.red, color.green, color.blue)
                                onColorChange(hue, sat)
                            }
                        )
                    }

                    Column(
                        modifier = Modifier.width(120.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AlphaTile(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            controller = controller
                        )

                        OutlinedTextField(
                            value = hexCode,
                            onValueChange = { newValue ->
                                // Only allow hex characters (0-9, a-f, A-F) and max 6 characters
                                val filtered = newValue.filter { it.isHexChar() }.take(6)
                                hexCode = filtered

                                // If we have a valid 6-character hex, apply it immediately
                                if (filtered.length == 6) {
                                    try {
                                        val (r, g, b) = hexToRgb(filtered)
                                        controller.selectByColor(Color(r, g, b), true)
                                    } catch (_: IllegalArgumentException) {
                                        // Invalid hex, ignore
                                    }
                                }
                            },
                            label = { Text("Hex") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = { Text("RRGGBB") },
                            enabled = !isLoading
                        )
                    }
                }
            }

            // Override indicator
            if (isOverridden) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Manual override active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    TextButton(
                        onClick = {
                            onClearOverride()
                            isColorPickerExpanded = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        enabled = !isLoading
                    ) {
                        Text("Clear", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun getLampColor(lamp: Lamp): Color {
    val hueValue = lamp.hue
    val satValue = lamp.saturation
    val ctValue = lamp.colorTemperature

    // Simple color approximation based on lamp state
    return when {
        hueValue != null && satValue != null -> {
            // Convert Hue API hue (0-65535) to HSL hue (0-360)
            val hue = hueValue * 360f / 65535f
            Color.hsv(hue, satValue / 254f, 1f)
        }
        ctValue != null -> {
            // Color temperature - warmer = more orange, cooler = more blue
            val warmth = (ctValue - 153f) / (500f - 153f)
            Color(
                red = 1f,
                green = 0.8f + (1f - warmth) * 0.2f,
                blue = 0.6f + (1f - warmth) * 0.4f
            )
        }
        else -> Color.Yellow
    }
}

private fun Char.isHexChar(): Boolean {
    return this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

private val String.rgbHex: String
    get() = if (length == 8) substring(2) else this

private fun hexToRgb(hex: String): Triple<Float, Float, Float> {
    require(hex.length == 6) { "Hex must be 6 characters" }
    val r = hex.substring(0, 2).toInt(16) / 255f
    val g = hex.substring(2, 4).toInt(16) / 255f
    val b = hex.substring(4, 6).toInt(16) / 255f
    return Triple(r, g, b)
}

private fun rgbToHueApiValues(r: Float, g: Float, b: Float): Pair<Int, Int> {
    // Convert RGB to HSV
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

    // Convert HSV to Hue API values
    // Hue: 0-1 -> 0-65535 (Hue API uses 0-65535 for 0-360 degrees)
    val hue = (h * 65535).toInt()
    // Saturation: 0-1 -> 0-254
    val sat = (s * 254).toInt()

    return Pair(hue, sat)
}
