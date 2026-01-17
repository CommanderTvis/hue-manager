package io.github.commandertvis.huemanager.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.commandertvis.huemanager.auth.SessionStorage
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
    private val sessionStorage: SessionStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        // Check if we have a stored token
        sessionStorage.token.value?.let { token ->
            apiClient.setAuthToken(token)
            _uiState.value = AuthUiState(isLoggedIn = true)
        }
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
                        sessionStorage.setToken(response.token)
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
        sessionStorage.clearToken()
        apiClient.setAuthToken(null)
        _uiState.value = AuthUiState(isLoggedIn = false)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
