package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.commandertvis.huemanager.BUILD_COMMIT
import io.github.commandertvis.huemanager.getPlatform
import io.github.commandertvis.huemanager.models.UserState
import io.github.commandertvis.huemanager.models.Lamp
import io.github.commandertvis.huemanager.network.ApiClient
import io.github.commandertvis.huemanager.viewmodel.LampsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    apiClient: ApiClient,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    compactWindowHeader: Boolean = false,
    desktop: Boolean = false,
    serverUrl: String? = null,
    onTitleBarDoubleClick: () -> Unit = {},
) {
    val lampsViewModel = remember { LampsViewModel(apiClient) }
    val uiState by lampsViewModel.uiState.collectAsState()
    val titleBarDoubleClick by rememberUpdatedState(onTitleBarDoubleClick)

    val snackbarHostState = remember { SnackbarHostState() }
    var showMcpDialog by remember { mutableStateOf(false) }
    var showSchedulerDialog by remember { mutableStateOf(false) }
    var showAutomatedLampsDialog by remember { mutableStateOf(false) }
    var showSmartButtonDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var scheduleLamp by remember { mutableStateOf<Lamp?>(null) }

    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            lampsViewModel.clearError()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 1200.dp
        val inlineLampAutomation = (desktop && maxWidth >= 480.dp) || wideLayout
        Scaffold(
            topBar = {
                val menu: @Composable () -> Unit = {
                    if (!wideLayout) {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Schedule") },
                                onClick = {
                                    showOverflowMenu = false
                                    showSchedulerDialog = true
                                }
                            )
                            if (!inlineLampAutomation) DropdownMenuItem(
                                text = { Text("Lamps") },
                                onClick = {
                                    showOverflowMenu = false
                                    showAutomatedLampsDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Button") },
                                onClick = {
                                    showOverflowMenu = false
                                    showSmartButtonDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("MCP") },
                                onClick = {
                                    showOverflowMenu = false
                                    showMcpDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Logout") },
                                onClick = {
                                    showOverflowMenu = false
                                    onLogout()
                                }
                            )
                        }
                    }
                }
                if (compactWindowHeader) {
                    Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 80.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { titleBarDoubleClick() })
                        }, contentAlignment = Alignment.CenterStart) {
                        Text(serverUrl.orEmpty(), modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Box { menu() }
                    }
                } else {
                    TopAppBar(title = { Text("Hue Manager", maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, actions = { menu() })
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Box(
                modifier = Modifier.fillMaxSize()
                    .padding(paddingValues)
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (wideLayout) {
                        Surface(modifier = Modifier.width(360.dp).fillMaxHeight().padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Settings", style = MaterialTheme.typography.titleLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { showSmartButtonDialog = true }) { Text("Button") }
                                    TextButton(onClick = { showMcpDialog = true }) { Text("MCP") }
                                }
                                HorizontalDivider()
                                Box(Modifier.weight(1f)) {
                                    SchedulerEditorDialog(
                                        currentSettings = SchedulerSettings(uiState.pseudoSunset, uiState.nightTime,
                                            uiState.daylightColor, uiState.eveningColor, uiState.nightColor),
                                        onDismiss = {},
                                        onSave = { settings ->
                                            lampsViewModel.updateSchedulerSettings(settings.pseudoSunset, settings.nightTime,
                                                settings.daylightColor, settings.eveningColor, settings.nightColor)
                                        },
                                        inline = true,
                                    )
                                }
                                HorizontalDivider()
                                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                                    Text("Logout")
                                }
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val availableWidth = (maxWidth.value - 32f).coerceAtLeast(1f)
                                val columns = kotlin.math.ceil(availableWidth / 600f).toInt()
                                    .coerceAtMost((availableWidth / 400f).toInt().coerceAtLeast(1))
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(columns),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(uiState.lamps, key = { it.id }) { lamp ->
                                        val isLampPending = lamp.id in uiState.pendingLampIds

                                        LampCard(
                                            lamp = lamp,
                                            hasSchedule = uiState.lampSchedules.any { it.lampId == lamp.id && it.enabled },
                                            onEditSchedule = { scheduleLamp = lamp },
                                            controlMode = when {
                                                uiState.lampSchedules.any { it.lampId == lamp.id && it.enabled } -> LampControlMode.SCHEDULE
                                                lamp.id in uiState.excludedLampIds -> LampControlMode.MANUAL
                                                else -> LampControlMode.CYCLE
                                            },
                                            savingAutomation = uiState.isSavingSchedule,
                                            onControlModeChange = if (inlineLampAutomation) { mode ->
                                                if (mode == LampControlMode.SCHEDULE && uiState.lampSchedules.none { it.lampId == lamp.id }) {
                                                    scheduleLamp = lamp
                                                } else {
                                                    val excluded = if (mode == LampControlMode.MANUAL) uiState.excludedLampIds + lamp.id
                                                        else uiState.excludedLampIds - lamp.id
                                                    val schedules = uiState.lampSchedules.map {
                                                        if (it.lampId == lamp.id) it.copy(enabled = mode == LampControlMode.SCHEDULE) else it
                                                    }
                                                    lampsViewModel.updateLampAutomation(excluded, schedules) {}
                                                }
                                            } else null,
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
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(mcpUrl, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            IconButton(onClick = {
                                getPlatform().copyToClipboard(mcpUrl)
                                copiedUrl = true
                            }) {
                                Icon(if (copiedUrl) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = if (copiedUrl) "Copied" else "Copy URL",
                                    modifier = Modifier.size(20.dp))
                            }
                        }
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
            schedules = uiState.lampSchedules,
            saving = uiState.isSavingSchedule,
            onDismiss = { showAutomatedLampsDialog = false },
            onSave = { excluded, schedules ->
                lampsViewModel.updateLampAutomation(excluded, schedules) { showAutomatedLampsDialog = false }
            }
        )
    }

    scheduleLamp?.let { lamp ->
        LampScheduleDialog(
            lamp = lamp,
            current = uiState.lampSchedules.find { it.lampId == lamp.id },
            timezone = uiState.timezone,
            saving = uiState.isSavingSchedule,
            onDismiss = { scheduleLamp = null },
            onSave = { schedule -> lampsViewModel.updateLampSchedule(lamp.id, schedule) { scheduleLamp = null } },
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
