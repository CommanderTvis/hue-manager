package io.github.commandertvis.huemanager.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.commandertvis.huemanager.api.AllLampsUpdateRequest
import io.github.commandertvis.huemanager.api.AutomationColorInfo
import io.github.commandertvis.huemanager.api.LampUpdateRequest
import io.github.commandertvis.huemanager.models.Lamp
import io.github.commandertvis.huemanager.models.UserState
import io.github.commandertvis.huemanager.network.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LampsUiState(
    val lamps: List<Lamp> = emptyList(),
    val userState: UserState = UserState.ASLEEP,
    val isLoading: Boolean = false,
    val error: String? = null,
    val overriddenLampIds: List<String> = emptyList(),
    val pseudoSunset: String = "21:05",
    val automationMode: String = "",
    val automationColor: AutomationColorInfo? = null
)

class LampsViewModel(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(LampsUiState())
    val uiState: StateFlow<LampsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Fetch lamps
            val lampsResult = apiClient.getLamps()
            lampsResult.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(lamps = response.lamps)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )

            // Fetch automation status
            val automationResult = apiClient.getAutomationStatus()
            automationResult.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(
                        userState = response.userState,
                        overriddenLampIds = response.overriddenLamps,
                        pseudoSunset = response.pseudoSunset,
                        automationMode = response.automationMode,
                        automationColor = response.automationColor
                    )
                },
                onFailure = { /* ignore */ }
            )

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun toggleLamp(lamp: Lamp) {
        viewModelScope.launch {
            val update = LampUpdateRequest(on = !lamp.on)
            val result = apiClient.updateLamp(lamp.id, update)
            result.fold(
                onSuccess = {
                    // Update local state optimistically
                    val updatedLamps = _uiState.value.lamps.map {
                        if (it.id == lamp.id) it.copy(on = !it.on) else it
                    }
                    _uiState.value = _uiState.value.copy(
                        lamps = updatedLamps,
                        overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun setBrightness(lamp: Lamp, brightness: Int) {
        viewModelScope.launch {
            val update = LampUpdateRequest(brightness = brightness)
            val result = apiClient.updateLamp(lamp.id, update)
            result.fold(
                onSuccess = {
                    val updatedLamps = _uiState.value.lamps.map {
                        if (it.id == lamp.id) it.copy(brightness = brightness) else it
                    }
                    _uiState.value = _uiState.value.copy(
                        lamps = updatedLamps,
                        overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun setLampColor(lamp: Lamp, hue: Int, saturation: Int) {
        viewModelScope.launch {
            val update = LampUpdateRequest(hue = hue, saturation = saturation)
            val result = apiClient.updateLamp(lamp.id, update)
            result.fold(
                onSuccess = {
                    val updatedLamps = _uiState.value.lamps.map {
                        if (it.id == lamp.id) it.copy(hue = hue, saturation = saturation) else it
                    }
                    _uiState.value = _uiState.value.copy(
                        lamps = updatedLamps,
                        overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun turnAllOn() {
        viewModelScope.launch {
            val result = apiClient.updateAllLamps(AllLampsUpdateRequest(on = true))
            result.fold(
                onSuccess = {
                    val updatedLamps = _uiState.value.lamps.map { it.copy(on = true) }
                    _uiState.value = _uiState.value.copy(lamps = updatedLamps)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun turnAllOff() {
        viewModelScope.launch {
            val result = apiClient.updateAllLamps(AllLampsUpdateRequest(on = false))
            result.fold(
                onSuccess = {
                    val updatedLamps = _uiState.value.lamps.map { it.copy(on = false) }
                    _uiState.value = _uiState.value.copy(lamps = updatedLamps)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun wakeUp() {
        viewModelScope.launch {
            val result = apiClient.wakeUp()
            result.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(userState = response.state)
                    refresh() // Refresh to get updated lamp states
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun goToSleep() {
        viewModelScope.launch {
            val result = apiClient.sleep()
            result.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(userState = response.state)
                    refresh() // Refresh to get updated lamp states
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun clearOverride(lampId: String) {
        viewModelScope.launch {
            val result = apiClient.clearLampOverride(lampId)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        overriddenLampIds = _uiState.value.overriddenLampIds - lampId
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
