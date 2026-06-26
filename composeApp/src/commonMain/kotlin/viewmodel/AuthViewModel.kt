package io.github.commandertvis.huemanager.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.commandertvis.huemanager.auth.AuthStorage
import io.github.commandertvis.huemanager.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null
)

class AuthViewModel(
    private val apiClient: ApiClient,
    private val authStorage: AuthStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        // Reuse a stored session token, if any (re-login on expiry).
        authStorage.token.value?.let { token ->
            apiClient.setAuthToken(token)
            _uiState.value = AuthUiState(isLoggedIn = true)
        }
        // When an authenticated request 401s (token expired or HUE_JWT_SECRET rotated), drop the
        // stale session and return to the login screen with a clear message.
        apiClient.onSessionExpired = ::sessionExpired
    }

    private fun sessionExpired() {
        authStorage.clearToken()
        apiClient.setAuthToken(null)
        _uiState.value = AuthUiState(isLoggedIn = false, error = "Session expired — please log in again")
    }

    fun login(password: String) {
        if (password.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Password cannot be empty")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val result = apiClient.login(password)

            result.fold(
                onSuccess = { response ->
                    if (response.success && response.token != null) {
                        authStorage.setToken(response.token)
                        _uiState.value = AuthUiState(isLoggedIn = true)
                    } else {
                        _uiState.value = AuthUiState(
                            error = response.error ?: "Login failed"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.value = AuthUiState(
                        error = e.message ?: "Connection failed"
                    )
                }
            )
        }
    }

    fun logout() {
        authStorage.clearToken()
        apiClient.setAuthToken(null)
        _uiState.value = AuthUiState(isLoggedIn = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
