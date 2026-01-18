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
                            // Successfully linked! Now send credentials to server
                            val configResult = apiClient.configureBridge(
                                BridgeConfigRequest(
                                    bridgeIp = bridgeIp,
                                    username = result.username
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
                                        errorMessage = "Linked to bridge but failed to configure server: ${error.message}"
                                    )
                                }
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

    fun retry() {
        _uiState.update {
            BridgePairingUiState()
        }
        discoverBridges()
    }
}
