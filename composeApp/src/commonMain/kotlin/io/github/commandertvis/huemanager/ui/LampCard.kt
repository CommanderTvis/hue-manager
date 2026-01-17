package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.models.Lamp

@Composable
fun LampCard(
    lamp: Lamp,
    isOverridden: Boolean,
    onToggle: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onClearOverride: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember(lamp.brightness) {
        mutableStateOf((lamp.brightness ?: 254).toFloat())
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
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

                Switch(
                    checked = lamp.on,
                    onCheckedChange = { onToggle() },
                    enabled = lamp.reachable
                )
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
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = "${(sliderValue * 100 / 254).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(36.dp)
                    )
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
                        onClick = onClearOverride,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Clear", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
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
