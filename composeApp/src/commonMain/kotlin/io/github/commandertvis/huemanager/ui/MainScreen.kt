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
                                text = if (uiState.userState == UserState.AWAKE) "You're awake" else "You're asleep",
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
                                if (uiState.userState == UserState.AWAKE) "I'm asleep!" else "I woke up!"
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // All lamps controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "All Lamps",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Switch(
                                checked = uiState.lamps.any { it.on },
                                onCheckedChange = { lampsViewModel.setAllLamps(it) },
                                enabled = !uiState.isLoading && !uiState.isGlobalToggling
                            )
                        }

                        OutlinedButton(
                            onClick = { lampsViewModel.refresh() },
                            enabled = !uiState.isLoading
                        ) {
                            Text("Refresh")
                        }
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
                        LampCard(
                            lamp = lamp,
                            isOverridden = lamp.id in uiState.overriddenLampIds,
                            isLoading = lamp.id in uiState.loadingLampIds || uiState.isGlobalToggling,
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
        val mcpUrl = "${apiClient.getBaseUrl()}mcp"

        val mcpJson = """
            {
              "mcpServers": {
                "hue-manager": {
                  "url": "$mcpUrl"
                }
              }
            }
        """.trimIndent()

        var copiedJson by remember { mutableStateOf(false) }
        var copiedUrl by remember { mutableStateOf(false) }
        var copiedRemote by remember { mutableStateOf(false) }
        var selectedTab by remember { mutableStateOf(0) }

        val mcpRemoteJson = """
            {
              "mcpServers": {
                "hue-manager": {
                  "command": "npx",
                  "args": [
                    "mcp-remote",
                    "$mcpUrl"
                  ]
                }
              }
            }
        """.trimIndent()

        AlertDialog(
            onDismissRequest = {
                showMcpDialog = false
                copiedJson = false
                copiedUrl = false
                copiedRemote = false
                selectedTab = 0
            },
            title = { Text("MCP Configuration") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PrimaryTabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Claude Desktop") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("http") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("mcp-remote") }
                        )
                    }

                    when (selectedTab) {
                        0 -> {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Use this URL to add as a Claude Desktop connector:",
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
                        }

                        1 -> {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Add this to your MCP client config:",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = mcpJson,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = "Replace YOUR_PASSWORD_HERE with your server password.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        getPlatform().copyToClipboard(mcpJson)
                                        copiedJson = true
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(if (copiedJson) "Copied!" else "Copy JSON")
                                }
                            }
                        }

                        2 -> {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Use mcp-remote to connect via SSE (for clients that don't support HTTP directly):",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = mcpRemoteJson,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = "Replace YOUR_PASSWORD_HERE with your server password.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        getPlatform().copyToClipboard(mcpRemoteJson)
                                        copiedRemote = true
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text(if (copiedRemote) "Copied!" else "Copy JSON")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showMcpDialog = false
                    copiedJson = false
                    copiedUrl = false
                    copiedRemote = false
                }) {
                    Text("Close")
                }
            }
        )
    }
}
