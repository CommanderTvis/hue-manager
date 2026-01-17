package io.github.commandertvis.huemanager

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.commandertvis.huemanager.auth.SessionStorage
import io.github.commandertvis.huemanager.network.ApiClient
import io.github.commandertvis.huemanager.storage.createServerUrlStorage
import io.github.commandertvis.huemanager.ui.*
import io.github.commandertvis.huemanager.viewmodel.AuthViewModel
import io.github.commandertvis.huemanager.viewmodel.BridgePairingViewModel
import kotlinx.coroutines.launch

@Composable
@Preview
fun App(
    initialServerUrl: String? = null
) {
    val serverUrlStorage = remember { createServerUrlStorage() }
    val storedUrl = remember { serverUrlStorage.getServerUrl() }
    var serverUrl by remember { mutableStateOf(initialServerUrl ?: storedUrl) }

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (serverUrl == null) {
                ServerConnectScreen(
                    storage = serverUrlStorage,
                    initialUrl = "http://localhost:$SERVER_PORT",
                    onConnect = { serverUrl = it }
                )
            } else {
                key(serverUrl) {
                    // We need to store the client to close it when URL changes or app dies
                    val apiClient = remember(serverUrl) { ApiClient(serverUrl!!) }
                    
                    DisposableEffect(apiClient) {
                        onDispose { apiClient.close() }
                    }

                    val sessionStorage = remember { SessionStorage() }
                    val authViewModel = remember(apiClient, sessionStorage) { 
                        AuthViewModel(apiClient, sessionStorage) 
                    }

                    val authUiState by authViewModel.uiState.collectAsState()
                    
                    val platform = remember { getPlatform() }
                    val scope = rememberCoroutineScope()
                    
                    // Track bridge pairing status
                    var bridgeStatus by remember { mutableStateOf<BridgeStatus?>(null) }
                    
                    // Check bridge status when logged in
                    LaunchedEffect(authUiState.isLoggedIn) {
                        if (authUiState.isLoggedIn) {
                            scope.launch {
                                apiClient.getStatus().onSuccess { status ->
                                    bridgeStatus = if (status.needsLinking) {
                                        BridgeStatus.NeedsPairing
                                    } else if (status.connected) {
                                        BridgeStatus.Connected
                                    } else {
                                        BridgeStatus.NeedsPairing
                                    }
                                }
                            }
                        }
                    }

                    when {
                        !authUiState.isLoggedIn -> {
                            LoginScreen(
                                uiState = authUiState,
                                onLogin = { password -> authViewModel.login(password) },
                                onErrorDismiss = { authViewModel.clearError() }
                            )
                        }
                        
                        bridgeStatus == BridgeStatus.NeedsPairing && platform.isWeb -> {
                            // Web app: show "Please pair" screen
                            PleasePairScreen(
                                onRetry = {
                                    scope.launch {
                                        apiClient.getStatus().onSuccess { status ->
                                            bridgeStatus = if (status.connected && !status.needsLinking) {
                                                BridgeStatus.Connected
                                            } else {
                                                BridgeStatus.NeedsPairing
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        
                        bridgeStatus == BridgeStatus.NeedsPairing && !platform.isWeb -> {
                            // Desktop/mobile: show bridge pairing screen
                            val bridgePairingViewModel = remember(apiClient) {
                                BridgePairingViewModel(apiClient)
                            }
                            val pairingUiState by bridgePairingViewModel.uiState.collectAsState()
                            
                            // Auto-start discovery
                            LaunchedEffect(Unit) {
                                if (pairingUiState.discoveredBridges.isEmpty() && !pairingUiState.isDiscovering) {
                                    bridgePairingViewModel.discoverBridges()
                                }
                            }
                            
                            // When pairing is complete, update bridge status
                            LaunchedEffect(pairingUiState.isComplete) {
                                if (pairingUiState.isComplete) {
                                    bridgeStatus = BridgeStatus.Connected
                                }
                            }
                            
                            BridgePairingScreen(
                                uiState = pairingUiState,
                                onDiscoverBridges = { bridgePairingViewModel.discoverBridges() },
                                onSelectBridge = { ip -> bridgePairingViewModel.selectBridge(ip) },
                                onStartLinking = { bridgePairingViewModel.startLinking() },
                                onRetry = { bridgePairingViewModel.retry() }
                            )
                        }
                        
                        bridgeStatus == BridgeStatus.Connected -> {
                            MainScreen(
                                apiClient = apiClient,
                                onLogout = { authViewModel.logout() }
                            )
                        }
                        
                        else -> {
                            // Loading state while checking bridge status
                            // This will be shown briefly while bridgeStatus is null
                        }
                    }
                }
            }
        }
    }
}

enum class BridgeStatus {
    NeedsPairing,
    Connected
}
