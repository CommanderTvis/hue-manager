package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.models.UserState
import io.github.commandertvis.huemanager.network.ApiClient
import io.github.commandertvis.huemanager.viewmodel.LampsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    apiClient: ApiClient,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lampsViewModel = remember { LampsViewModel(apiClient) }
    val uiState by lampsViewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

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
                            Text(
                                text = "Pseudo-sunset: ${uiState.pseudoSunset}",
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { lampsViewModel.turnAllOn() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("All On")
                        }

                        OutlinedButton(
                            onClick = { lampsViewModel.turnAllOff() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("All Off")
                        }

                        OutlinedButton(
                            onClick = { lampsViewModel.refresh() },
                            modifier = Modifier.weight(1f)
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
