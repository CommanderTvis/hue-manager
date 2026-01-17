package io.github.commandertvis.huemanager.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.commandertvis.huemanager.api.BridgeConfigRequest
import io.github.commandertvis.huemanager.network.ApiClient
import io.github.commandertvis.huemanager.ui.BridgePairingUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BridgePairingViewModel(
    private val apiClient: ApiClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(BridgePairingUiState())
    val uiState: StateFlow<BridgePairingUiState> = _uiState.asStateFlow()

    fun discoverBridges() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, errorMessage = null) }
            
            apiClient.discoverBridges()
                .onSuccess { bridges ->
                    if (bridges.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                isDiscovering = false,
                                discoveredBridges = emptyList(),
                                errorMessage = "No Hue bridges found on your network."
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isDiscovering = false,
                                discoveredBridges = bridges,
                                errorMessage = null
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDiscovering = false,
                            errorMessage = "Failed to discover bridges: ${error.message}"
                        )
                    }
                }
        }
    }

    fun selectBridge(bridgeIp: String) {
        _uiState.update { it.copy(selectedBridgeIp = bridgeIp) }
    }

    fun startLinking() {
        val bridgeIp = _uiState.value.selectedBridgeIp ?: return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLinking = true, linkingAttempt = 0, errorMessage = null) }
            
            // Try linking for up to 30 attempts (60 seconds with 2 second delay)
            var attempt = 0
            val maxAttempts = 30
            
            while (attempt < maxAttempts) {
                attempt++
                _uiState.update { it.copy(linkingAttempt = attempt) }
                
                val result = apiClient.linkBridge(BridgeConfigRequest(bridgeIp = bridgeIp))
                
                result.onSuccess { response ->
                    if (response.success && response.connected) {
                        _uiState.update {
                            it.copy(
                                isLinking = false,
                                isComplete = true,
                                errorMessage = null
                            )
                        }
                        return@launch
                    } else if (!response.needsLinking) {
                        // Some other error occurred
                        _uiState.update {
                            it.copy(
                                isLinking = false,
                                errorMessage = response.message
                            )
                        }
                        return@launch
                    }
                    // If needsLinking is true, continue waiting
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLinking = false,
                            errorMessage = "Linking failed: ${error.message}"
                        )
                    }
                    return@launch
                }
                
                delay(2000) // Wait 2 seconds before next attempt
            }
            
            // Timeout
            _uiState.update {
                it.copy(
                    isLinking = false,
                    errorMessage = "Linking timeout. Please try again and press the button on your bridge."
                )
            }
        }
    }

    fun retry() {
        _uiState.update {
            BridgePairingUiState()
        }
        discoverBridges()
    }
}
