package io.github.commandertvis.huemanager

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.commandertvis.huemanager.auth.SessionStorage
import io.github.commandertvis.huemanager.network.ApiClient
import io.github.commandertvis.huemanager.ui.LoginScreen
import io.github.commandertvis.huemanager.ui.MainScreen
import io.github.commandertvis.huemanager.viewmodel.AuthViewModel

@Composable
@Preview
fun App(
    serverUrl: String = "http://localhost:$SERVER_PORT"
) {
    val apiClient = remember { ApiClient(serverUrl) }
    val sessionStorage = remember { SessionStorage() }
    val authViewModel = remember { AuthViewModel(apiClient, sessionStorage) }

    val authUiState by authViewModel.uiState.collectAsState()

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
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

    DisposableEffect(Unit) {
        onDispose {
            apiClient.close()
        }
    }
}