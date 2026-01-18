package io.github.commandertvis.huemanager

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
                                        BridgeStatus.NeedsAuthorization
                                    } else if (status.connected) {
                                        BridgeStatus.Connected
                                    } else {
                                        BridgeStatus.NeedsAuthorization
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
                        
                        bridgeStatus == BridgeStatus.NeedsAuthorization && platform.isWeb -> {
                            // Web app: show "Please pair" screen
                            PleaseAuthorizeScreen(
                                onRetry = {
                                    scope.launch {
                                        apiClient.getStatus().onSuccess { status ->
                                            bridgeStatus = if (status.connected && !status.needsLinking) {
                                                BridgeStatus.Connected
                                            } else {
                                                BridgeStatus.NeedsAuthorization
                                            }
                                        }
                                    }
                                },
                                onStartAuthorizing = {
                                    scope.launch {
                                        println("[DEBUG_LOG] Start Pairing clicked (Web)")
                                        apiClient.getAuthorizationUrl().onSuccess { url ->
                                            println("[DEBUG_LOG] Received URL (Web): $url")
                                            if (url.isNotEmpty()) {
                                                platform.openUrl(url)
                                            } else {
                                                println("[DEBUG_LOG] Received empty URL (Web)")
                                            }
                                        }.onFailure { e ->
                                            println("[DEBUG_LOG] Failed to get URL (Web): ${e.message}")
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            )
                        }
                        
                        bridgeStatus == BridgeStatus.NeedsAuthorization && !platform.isWeb -> {
                            // Desktop/mobile: OAuth is done via browser, show same screen as web
                            PleaseAuthorizeScreen(
                                onRetry = {
                                    scope.launch {
                                        apiClient.getStatus().onSuccess { status ->
                                            bridgeStatus = if (status.connected && !status.needsLinking) {
                                                BridgeStatus.Connected
                                            } else {
                                                BridgeStatus.NeedsAuthorization
                                            }
                                        }
                                    }
                                },
                                onStartAuthorizing = {
                                    scope.launch {
                                        println("[DEBUG_LOG] Start Pairing clicked (Desktop/Mobile)")
                                        apiClient.getAuthorizationUrl().onSuccess { url ->
                                            println("[DEBUG_LOG] Received URL (Desktop/Mobile): $url")
                                            if (url.isNotEmpty()) {
                                                platform.openUrl(url)
                                            } else {
                                                println("[DEBUG_LOG] Received empty URL (Desktop/Mobile)")
                                            }
                                        }.onFailure { e ->
                                            println("[DEBUG_LOG] Failed to get URL (Desktop/Mobile): ${e.message}")
                                            e.printStackTrace()
                                        }
                                    }
                                }
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
    NeedsAuthorization,
    Connected
}
