package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.sp
import io.github.commandertvis.huemanager.BUILD_COMMIT
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
    var showSchedulerDialog by remember { mutableStateOf(false) }
    var showAutomatedLampsDialog by remember { mutableStateOf(false) }
    var showSmartButtonDialog by remember { mutableStateOf(false) }

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
                    TextButton(onClick = { showSchedulerDialog = true }) {
                        Text("Schedule")
                    }
                    TextButton(onClick = { showAutomatedLampsDialog = true }) {
                        Text("Lamps")
                    }
                    TextButton(onClick = { showSmartButtonDialog = true }) {
                        Text("Button")
                    }
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
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Status and control bar
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Automation mode display
                            if (uiState.automationMode.isNotEmpty()) {
                                val modeDisplay = when (uiState.automationMode) {
                                    "AUTO_COMPENSATION" -> "Daylight mode"
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
                                    val hueValue = colorInfo.hue
                                    val saturationValue = colorInfo.saturation
                                    val displayColor = if (hueValue != null && saturationValue != null) {
                                        val hue = (hueValue / 65535f) * 360f
                                        val saturation = saturationValue / 254f
                                        val brightness = colorInfo.brightness / 254f
                                        Color.hsv(hue, saturation, brightness)
                                    } else {
                                        Color(0xFFFFE4B5)
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
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
                                text = "Evening time: ${uiState.pseudoSunset}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Prominent Lamps on/off button
                        Button(
                            onClick = {
                                if (uiState.userState == UserState.AWAKE) {
                                    lampsViewModel.goToSleep()
                                } else {
                                    lampsViewModel.wakeUp()
                                }
                            },
                            colors = if (uiState.userState == UserState.AWAKE) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .widthIn(min = 120.dp)
                        ) {
                            Text(
                                if (uiState.userState == UserState.AWAKE) "Lamps off" else "Lamps on",
                                style = MaterialTheme.typography.titleSmall
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
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(uiState.lamps) { lamp ->
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

            // Version commit in bottom-right corner
            Text(
                text = BUILD_COMMIT,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            )
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

    if (showSmartButtonDialog) {
        SmartButtonDialog(
            sensors = uiState.sensors,
            selectedSensorId = uiState.toggleButtonSensorId,
            onDismiss = { showSmartButtonDialog = false },
            onSave = { sensorId ->
                lampsViewModel.updateToggleButton(sensorId)
                showSmartButtonDialog = false
            },
            onRefresh = { lampsViewModel.loadSensors() },
        )
    }

    if (showAutomatedLampsDialog) {
        AutomatedLampsDialog(
            lamps = uiState.lamps,
            excludedLampIds = uiState.excludedLampIds,
            onDismiss = { showAutomatedLampsDialog = false },
            onSave = { excluded ->
                lampsViewModel.updateExcludedLamps(excluded)
                showAutomatedLampsDialog = false
            }
        )
    }

    if (showSchedulerDialog) {
        SchedulerEditorDialog(
            currentSettings = SchedulerSettings(
                pseudoSunset = uiState.pseudoSunset,
                nightTime = uiState.nightTime,
                daylightColor = uiState.daylightColor,
                eveningColor = uiState.eveningColor,
                nightColor = uiState.nightColor,
            ),
            onDismiss = { showSchedulerDialog = false },
            onSave = { settings ->
                lampsViewModel.updateSchedulerSettings(
                    pseudoSunset = settings.pseudoSunset,
                    nightTime = settings.nightTime,
                    daylightColor = settings.daylightColor,
                    eveningColor = settings.eveningColor,
                    nightColor = settings.nightColor,
                )
                showSchedulerDialog = false
            }
        )
    }
}
