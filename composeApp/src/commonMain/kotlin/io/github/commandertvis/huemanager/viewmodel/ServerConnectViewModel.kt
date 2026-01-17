package io.github.commandertvis.huemanager.viewmodel

import io.github.commandertvis.huemanager.network.ApiClient
import io.github.commandertvis.huemanager.storage.ServerUrlStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServerConnectUiState(
    val url: String = "",
    val isConnecting: Boolean = false,
    val error: String? = null
)

class ServerConnectViewModel(
    private val storage: ServerUrlStorage,
    initialUrl: String?
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private val _uiState = MutableStateFlow(
        ServerConnectUiState(url = initialUrl ?: "")
    )
    val uiState: StateFlow<ServerConnectUiState> = _uiState.asStateFlow()
    
    fun updateUrl(url: String) {
        _uiState.value = _uiState.value.copy(url = url, error = null)
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun connect(onSuccess: (String) -> Unit) {
        val url = _uiState.value.url.trim()
        
        if (url.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "URL cannot be empty")
            return
        }
        
        _uiState.value = _uiState.value.copy(isConnecting = true, error = null)
        
        scope.launch {
            // Create a temporary client to test connectivity
            val testClient = ApiClient(url)
            
            try {
                val result = testClient.getStatus()
                
                if (result.isSuccess) {
                    // Connection successful, store URL and proceed
                    storage.setServerUrl(url)
                    _uiState.value = _uiState.value.copy(isConnecting = false)
                    onSuccess(url)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        error = "Failed to connect: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    error = "Failed to connect: ${e.message ?: "Unknown error"}"
                )
            } finally {
                testClient.close()
            }
        }
    }
}
