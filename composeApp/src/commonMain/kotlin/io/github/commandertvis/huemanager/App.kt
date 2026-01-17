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
import io.github.commandertvis.huemanager.ui.LoginScreen
import io.github.commandertvis.huemanager.ui.MainScreen
import io.github.commandertvis.huemanager.ui.ServerConnectScreen
import io.github.commandertvis.huemanager.viewmodel.AuthViewModel

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

                    Crossfade(targetState = authUiState.isLoggedIn) { isLoggedIn ->
                        if (isLoggedIn) {
                            MainScreen(
                                apiClient = apiClient,
                                onLogout = { authViewModel.logout() }
                            )
                        } else {
                            LoginScreen(
                                uiState = authUiState,
                                onLogin = { password -> authViewModel.login(password) },
                                onErrorDismiss = { authViewModel.clearError() }
                            )
                        }
                    }
                }
            }
        }
    }
}
