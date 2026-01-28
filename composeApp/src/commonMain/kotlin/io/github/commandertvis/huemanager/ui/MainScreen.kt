package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.getPlatform
import io.github.commandertvis.huemanager.models.UserState
import io.github.commandertvis.huemanager.network.ApiClient
import io.github.commandertvis.huemanager.viewmodel.LampsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    apiClient: ApiClient,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lampsViewModel = remember { LampsViewModel(apiClient) }
    val uiState by lampsViewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showMcpDialog by remember { mutableStateOf(false) }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            lampsViewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hue Manager") },
                actions = {
                    TextButton(onClick = { showMcpDialog = true }) {
                        Text("MCP")
                    }
                    TextButton(onClick = onLogout) {
                        Text("Logout")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Status and control bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Wake/Sleep button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (uiState.userState == UserState.AWAKE) "Lamps on" else "Lamps off",
                                style = MaterialTheme.typography.titleMedium
                            )

                            // Automation mode display
                            if (uiState.automationMode.isNotEmpty()) {
                                val modeDisplay = when (uiState.automationMode) {
                                    "AUTO_COMPENSATION" -> "Auto-compensating"
                                    "EVENING" -> "Evening light"
                                    "NIGHT" -> "Night mode"
                                    "USER_ASLEEP" -> "Lamps off"
                                    else -> uiState.automationMode
                                }
                                Text(
                                    text = "Automation: $modeDisplay",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            // Color information
                            uiState.automationColor?.let { colorInfo ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Small colored circle
                                    val hueValue = colorInfo.hue
                                    val saturationValue = colorInfo.saturation
                                    val displayColor = if (hueValue != null && saturationValue != null) {
                                        // Convert Hue (0-65535) and Saturation (0-254) to RGB
                                        val hue = (hueValue / 65535f) * 360f
                                        val saturation = saturationValue / 254f
                                        val brightness = colorInfo.brightness / 254f
                                        Color.hsv(hue, saturation, brightness)
                                    } else {
                                        // Color temperature mode - use warm white
                                        Color(0xFFFFE4B5)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(displayColor)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    )
                                    Text(
                                        text = "Target: ${colorInfo.description}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = "Evening light: ${uiState.pseudoSunset}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                if (uiState.userState == UserState.AWAKE) {
                                    lampsViewModel.goToSleep()
                                } else {
                                    lampsViewModel.wakeUp()
                                }
                            }
                        ) {
                            Text(
                                if (uiState.userState == UserState.AWAKE) "Lamps off" else "Lamps on"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // All lamps controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "All Lamps",
                            style = MaterialTheme.typography.titleMedium
                        )

                        // Disable switch when any lamp has pending operation
                        val anyPending = uiState.pendingLampIds.isNotEmpty()
                        Switch(
                            checked = uiState.lamps.any { it.on },
                            onCheckedChange = { lampsViewModel.setAllLamps(it) },
                            enabled = !uiState.isLoading && !anyPending
                        )
                    }
                }
            }

            // Lamp list
            if (uiState.isLoading && uiState.lamps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.lamps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No lamps found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.lamps) { lamp ->
                        // Lamp is pending if it's in the pending set (from this or another client)
                        val isLampPending = lamp.id in uiState.pendingLampIds

                        LampCard(
                            lamp = lamp,
                            isOverridden = lamp.id in uiState.overriddenLampIds,
                            isLoading = isLampPending,
                            onToggle = { lampsViewModel.toggleLamp(lamp) },
                            onBrightnessChange = { brightness ->
                                lampsViewModel.setBrightness(lamp, brightness)
                            },
                            onColorChange = { hue, saturation ->
                                lampsViewModel.setLampColor(lamp, hue, saturation)
                            },
                            onClearOverride = { lampsViewModel.clearOverride(lamp.id) }
                        )
                    }
                }
            }
        }
    }

    if (showMcpDialog) {
        val mcpUrl = "${apiClient.getBaseUrl().trimEnd('/')}/mcp"

        var copiedUrl by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                showMcpDialog = false
                copiedUrl = false
            },
            title = { Text("MCP Configuration") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "MCP clients connect via HTTP OAuth. Add this URL as a connector and complete the OAuth prompt:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = mcpUrl,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Button(
                        onClick = {
                            getPlatform().copyToClipboard(mcpUrl)
                            copiedUrl = true
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(if (copiedUrl) "Copied!" else "Copy URL")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showMcpDialog = false
                    copiedUrl = false
                }) {
                    Text("Close")
                }
            }
        )
    }
}
