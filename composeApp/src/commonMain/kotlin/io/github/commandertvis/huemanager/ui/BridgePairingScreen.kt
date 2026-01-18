package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
    onRetry: () -> Unit,
    onSubmitPublicIp: (String) -> Unit
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
            uiState.needsPublicIp -> {
                var publicIp by remember { mutableStateOf("") }
                
                Text(
                    text = "Bridge Linked Successfully!",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Text(
                    text = "The bridge was paired on your local network (${uiState.selectedBridgeIp}).",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "Now enter the PUBLIC IP address or VPN address where the server can reach your bridge:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                OutlinedTextField(
                    value = publicIp,
                    onValueChange = { publicIp = it },
                    label = { Text("Public Bridge IP") },
                    placeholder = { Text("e.g., 203.0.113.45 or vpn.home.local") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    singleLine = true
                )
                
                Button(
                    onClick = { onSubmitPublicIp(publicIp) },
                    enabled = publicIp.isNotBlank()
                ) {
                    Text("Configure Server")
                }
            }
            
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
                SelectionContainer {
                    Text(
                        text = uiState.errorMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
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

    }
}

data class BridgePairingUiState(
    val isDiscovering: Boolean = false,
    val discoveredBridges: List<DiscoveredBridge> = emptyList(),
    val selectedBridgeIp: String? = null,
    val isLinking: Boolean = false,
    val linkingAttempt: Int = 0,
    val errorMessage: String? = null,
    val isComplete: Boolean = false,
    val linkedUsername: String? = null,
    val needsPublicIp: Boolean = false
)
