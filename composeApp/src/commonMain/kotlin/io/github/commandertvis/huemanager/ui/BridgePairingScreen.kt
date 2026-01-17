package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.api.DiscoveredBridge

@Composable
fun BridgePairingScreen(
    uiState: BridgePairingUiState,
    onDiscoverBridges: () -> Unit,
    onSelectBridge: (String) -> Unit,
    onStartLinking: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Hue Bridge Setup",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        when {
            uiState.isDiscovering -> {
                CircularProgressIndicator()
                Text(
                    text = "Discovering Hue bridges...",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            uiState.discoveredBridges.isNotEmpty() && uiState.selectedBridgeIp == null -> {
                Text(
                    text = "Select your Hue bridge:",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.discoveredBridges) { bridge ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelectBridge(bridge.internalipaddress) }
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "Bridge ID: ${bridge.id}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "IP: ${bridge.internalipaddress}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            uiState.isLinking -> {
                CircularProgressIndicator()
                Text(
                    text = "Press the link button on your Hue bridge...",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                Text(
                    text = "Waiting for button press (${uiState.linkingAttempt}/30)...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            uiState.errorMessage != null -> {
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }

            else -> {
                Text(
                    text = "No Hue bridges found on your network.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(onClick = onDiscoverBridges) {
                    Text("Discover Bridges")
                }
            }
        }

        if (uiState.selectedBridgeIp != null && !uiState.isLinking && uiState.errorMessage == null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStartLinking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Pairing")
            }
        }
    }
}

data class BridgePairingUiState(
    val isDiscovering: Boolean = false,
    val discoveredBridges: List<DiscoveredBridge> = emptyList(),
    val selectedBridgeIp: String? = null,
    val isLinking: Boolean = false,
    val linkingAttempt: Int = 0,
    val errorMessage: String? = null,
    val isComplete: Boolean = false
)
