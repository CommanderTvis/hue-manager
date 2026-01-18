package io.github.commandertvis.huemanager.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.commandertvis.huemanager.api.BridgeConfigRequest
import io.github.commandertvis.huemanager.hue.HueBridgeClient
import io.github.commandertvis.huemanager.hue.LinkResult
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

            try {
                // Use local network scanning instead of Philips cloud API
                val bridges = HueBridgeClient.discoverBridgesOnLocalNetwork()

                if (bridges.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isDiscovering = false,
                            discoveredBridges = emptyList(),
                            errorMessage = "No Hue bridges found on your network. Make sure your bridge is powered on and connected."
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        errorMessage = "Failed to scan network: ${e.message}"
                    )
                }
            }
        }
    }

    fun selectBridge(bridgeIp: String) {
        _uiState.update { it.copy(selectedBridgeIp = bridgeIp) }
        startLinking()
    }

    private fun startLinking() {
        val bridgeIp = _uiState.value.selectedBridgeIp ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLinking = true, linkingAttempt = 0, errorMessage = null) }

            // Create direct client to bridge (local network connection)
            val bridgeClient = HueBridgeClient(bridgeIp)

            try {
                // Try linking for up to 30 attempts (60 seconds with 2 second delay)
                var attempt = 0
                val maxAttempts = 30

                while (attempt < maxAttempts) {
                    attempt++
                    _uiState.update { it.copy(linkingAttempt = attempt) }

                    when (val result = bridgeClient.createUser()) {
                        is LinkResult.Success -> {
                            // Successfully linked locally! Now ask for public IP
                            _uiState.update {
                                it.copy(
                                    isLinking = false,
                                    linkedUsername = result.username,
                                    needsPublicIp = true,
                                    errorMessage = null
                                )
                            }
                            return@launch
                        }

                        is LinkResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLinking = false,
                                    errorMessage = "Linking error: ${result.message}"
                                )
                            }
                            return@launch
                        }

                        LinkResult.LinkButtonNotPressed -> {
                            // Button not pressed yet, continue waiting
                            delay(2000)
                        }
                    }
                }

                // Timeout
                _uiState.update {
                    it.copy(
                        isLinking = false,
                        errorMessage = "Linking timeout. Please try again and press the button on your bridge."
                    )
                }
            } finally {
                bridgeClient.close()
            }
        }
    }

    fun submitPublicIp(publicIp: String) {
        val username = _uiState.value.linkedUsername ?: return
        
        viewModelScope.launch {
            _uiState.update { it.copy(needsPublicIp = false, isLinking = true, errorMessage = null) }
            
            val configResult = apiClient.configureBridge(
                BridgeConfigRequest(
                    bridgeIp = publicIp,
                    username = username
                )
            )
            
            configResult.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        isLinking = false,
                        isComplete = true,
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLinking = false,
                        needsPublicIp = true,
                        errorMessage = "Failed to configure server: ${error.message}"
                    )
                }
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
