package io.github.commandertvis.huemanager.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.commandertvis.huemanager.api.*
import io.github.commandertvis.huemanager.models.Lamp
import io.github.commandertvis.huemanager.models.UserState
import io.github.commandertvis.huemanager.network.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LampsUiState(
    val lamps: List<Lamp> = emptyList(),
    val userState: UserState = UserState.ASLEEP,
    val isLoading: Boolean = false,
    val error: String? = null,
    val overriddenLampIds: List<String> = emptyList(),
    val pseudoSunset: String = "21:05",
    val nightTime: String = "00:05",
    val automationMode: String = "",
    val automationColor: AutomationColorInfo? = null,
    val pendingLampIds: Set<String> = emptySet(),
    val syncVersion: Long = 0L,
    val daylightColor: AutomationModeColorConfig = AutomationModeColorConfig(colorTemperature = 350, brightness = 254),
    val eveningColor: AutomationModeColorConfig = AutomationModeColorConfig(hue = 5000, saturation = 254, brightness = 254),
    val nightColor: AutomationModeColorConfig = AutomationModeColorConfig(hue = 5000, saturation = 254, brightness = 1),
)

class LampsViewModel(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(LampsUiState())
    val uiState: StateFlow<LampsUiState> = _uiState.asStateFlow()

    private var fastPollJob: Job? = null
    private var slowPollJob: Job? = null

    companion object {
        private const val FAST_POLL_INTERVAL_MS = 500L  // Sync state every 500ms
        private const val SLOW_POLL_INTERVAL_MS = 10_000L  // Lamp state every 10s
    }

    init {
        // Initial full load
        refresh()
        // Start auto-polling
        startPolling()
    }

    private fun startPolling() {
        // Fast polling for sync state (lightweight, no Hue API calls)
        fastPollJob?.cancel()
        fastPollJob = viewModelScope.launch {
            while (isActive) {
                delay(FAST_POLL_INTERVAL_MS)
                pollSync()
            }
        }

        // Slow polling for lamp states (respects Hue rate limits)
        slowPollJob?.cancel()
        slowPollJob = viewModelScope.launch {
            while (isActive) {
                delay(SLOW_POLL_INTERVAL_MS)
                pollLamps()
            }
        }
    }

    private suspend fun pollSync() {
        val syncResult = apiClient.getSync()
        syncResult.fold(
            onSuccess = { response ->
                _uiState.value = _uiState.value.copy(
                    userState = response.userState,
                    automationMode = response.automationMode,
                    automationColor = response.automationColor,
                    overriddenLampIds = response.overriddenLamps,
                    pendingLampIds = response.pendingLampIds.toSet(),
                    syncVersion = response.version
                )
            },
            onFailure = { /* Silently ignore sync failures to avoid spamming errors */ }
        )
    }

    private suspend fun pollLamps() {
        val lampsResult = apiClient.getLamps()
        lampsResult.fold(
            onSuccess = { response ->
                _uiState.value = _uiState.value.copy(lamps = response.lamps)
            },
            onFailure = { /* Silently ignore lamp poll failures */ }
        )
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

            // Fetch sync state
            val syncResult = apiClient.getSync()
            syncResult.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(
                        userState = response.userState,
                        automationMode = response.automationMode,
                        automationColor = response.automationColor,
                        overriddenLampIds = response.overriddenLamps,
                        pendingLampIds = response.pendingLampIds.toSet(),
                        syncVersion = response.version,
                        pseudoSunset = "21:05" // TODO: get from settings
                    )
                },
                onFailure = { /* ignore */ }
            )

            // Fetch settings (pseudo-sunset + mode colors)
            val settingsResult = apiClient.getSettings()
            settingsResult.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(
                        pseudoSunset = response.pseudoSunset,
                        nightTime = response.nightTime,
                        daylightColor = response.daylightColor,
                        eveningColor = response.eveningColor,
                        nightColor = response.nightColor,
                    )
                },
                onFailure = { /* ignore */ }
            )

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun toggleLamp(lamp: Lamp) {
        viewModelScope.launch {
            // Mark pending on server first, then update local state immediately
            apiClient.addPendingOperations(listOf(lamp.id))
            _uiState.value = _uiState.value.copy(
                pendingLampIds = _uiState.value.pendingLampIds + lamp.id
            )

            val update = LampUpdateRequest(on = !lamp.on)
            val result = apiClient.updateLamp(lamp.id, update)

            // Clear pending on server - local state will sync via poll
            apiClient.clearPendingOperations(listOf(lamp.id))

            result.fold(
                onSuccess = {
                    val updatedLamps = _uiState.value.lamps.map {
                        if (it.id == lamp.id) it.copy(on = !it.on) else it
                    }
                    _uiState.value = _uiState.value.copy(
                        lamps = updatedLamps,
                        overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id,
                        pendingLampIds = _uiState.value.pendingLampIds - lamp.id
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        pendingLampIds = _uiState.value.pendingLampIds - lamp.id
                    )
                }
            )
        }
    }

    fun setBrightness(lamp: Lamp, brightness: Int) {
        viewModelScope.launch {
            apiClient.addPendingOperations(listOf(lamp.id))
            _uiState.value = _uiState.value.copy(
                pendingLampIds = _uiState.value.pendingLampIds + lamp.id
            )

            val update = LampUpdateRequest(brightness = brightness)
            val result = apiClient.updateLamp(lamp.id, update)

            apiClient.clearPendingOperations(listOf(lamp.id))

            result.fold(
                onSuccess = {
                    val updatedLamps = _uiState.value.lamps.map {
                        if (it.id == lamp.id) it.copy(brightness = brightness) else it
                    }
                    _uiState.value = _uiState.value.copy(
                        lamps = updatedLamps,
                        overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id,
                        pendingLampIds = _uiState.value.pendingLampIds - lamp.id
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        pendingLampIds = _uiState.value.pendingLampIds - lamp.id
                    )
                }
            )
        }
    }

    fun setLampColor(lamp: Lamp, hue: Int, saturation: Int) {
        viewModelScope.launch {
            apiClient.addPendingOperations(listOf(lamp.id))
            _uiState.value = _uiState.value.copy(
                pendingLampIds = _uiState.value.pendingLampIds + lamp.id
            )

            val update = LampUpdateRequest(hue = hue, saturation = saturation)
            val result = apiClient.updateLamp(lamp.id, update)

            apiClient.clearPendingOperations(listOf(lamp.id))

            result.fold(
                onSuccess = {
                    val updatedLamps = _uiState.value.lamps.map {
                        if (it.id == lamp.id) it.copy(hue = hue, saturation = saturation) else it
                    }
                    _uiState.value = _uiState.value.copy(
                        lamps = updatedLamps,
                        overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id,
                        pendingLampIds = _uiState.value.pendingLampIds - lamp.id
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        pendingLampIds = _uiState.value.pendingLampIds - lamp.id
                    )
                }
            )
        }
    }

    fun setAllLamps(on: Boolean) {
        viewModelScope.launch {
            val allLampIds = _uiState.value.lamps.map { it.id }
            apiClient.addPendingOperations(allLampIds)
            _uiState.value = _uiState.value.copy(
                pendingLampIds = _uiState.value.pendingLampIds + allLampIds
            )

            val result = apiClient.updateAllLamps(AllLampsUpdateRequest(on = on))

            apiClient.clearPendingOperations(allLampIds)

            result.fold(
                onSuccess = {
                    val updatedLamps = _uiState.value.lamps.map { it.copy(on = on) }
                    val newOverriddenIds = (_uiState.value.overriddenLampIds + allLampIds).distinct()

                    _uiState.value = _uiState.value.copy(
                        lamps = updatedLamps,
                        overriddenLampIds = newOverriddenIds,
                        pendingLampIds = _uiState.value.pendingLampIds - allLampIds.toSet()
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        pendingLampIds = _uiState.value.pendingLampIds - allLampIds.toSet()
                    )
                }
            )
        }
    }

    fun wakeUp() {
        viewModelScope.launch {
            val allLampIds = _uiState.value.lamps.map { it.id }
            apiClient.addPendingOperations(allLampIds)
            _uiState.value = _uiState.value.copy(
                pendingLampIds = _uiState.value.pendingLampIds + allLampIds
            )

            val result = apiClient.wakeUp()

            apiClient.clearPendingOperations(allLampIds)

            result.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(
                        userState = response.state,
                        pendingLampIds = _uiState.value.pendingLampIds - allLampIds.toSet()
                    )
                    // Immediately refresh lamp and sync state after wake/sleep change
                    pollSync()
                    pollLamps()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        pendingLampIds = _uiState.value.pendingLampIds - allLampIds.toSet()
                    )
                }
            )
        }
    }

    fun goToSleep() {
        viewModelScope.launch {
            val allLampIds = _uiState.value.lamps.map { it.id }
            apiClient.addPendingOperations(allLampIds)
            _uiState.value = _uiState.value.copy(
                pendingLampIds = _uiState.value.pendingLampIds + allLampIds
            )

            val result = apiClient.sleep()

            apiClient.clearPendingOperations(allLampIds)

            result.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(
                        userState = response.state,
                        pendingLampIds = _uiState.value.pendingLampIds - allLampIds.toSet()
                    )
                    // Immediately refresh lamp and sync state after wake/sleep change
                    pollSync()
                    pollLamps()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        pendingLampIds = _uiState.value.pendingLampIds - allLampIds.toSet()
                    )
                }
            )
        }
    }

    fun clearOverride(lampId: String) {
        viewModelScope.launch {
            apiClient.addPendingOperations(listOf(lampId))
            _uiState.value = _uiState.value.copy(
                pendingLampIds = _uiState.value.pendingLampIds + lampId
            )

            val result = apiClient.clearLampOverride(lampId)

            apiClient.clearPendingOperations(listOf(lampId))

            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        overriddenLampIds = _uiState.value.overriddenLampIds - lampId,
                        pendingLampIds = _uiState.value.pendingLampIds - lampId
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        error = e.message,
                        pendingLampIds = _uiState.value.pendingLampIds - lampId
                    )
                }
            )
        }
    }

    fun updateSchedulerSettings(
        pseudoSunset: String,
        nightTime: String,
        daylightColor: AutomationModeColorConfig,
        eveningColor: AutomationModeColorConfig,
        nightColor: AutomationModeColorConfig,
    ) {
        viewModelScope.launch {
            val result = apiClient.updateSettings(
                SettingsUpdateRequest(
                    pseudoSunset = pseudoSunset,
                    nightTime = nightTime,
                    daylightColor = daylightColor,
                    eveningColor = eveningColor,
                    nightColor = nightColor,
                )
            )
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        pseudoSunset = pseudoSunset,
                        nightTime = nightTime,
                        daylightColor = daylightColor,
                        eveningColor = eveningColor,
                        nightColor = nightColor,
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

    override fun onCleared() {
        super.onCleared()
        fastPollJob?.cancel()
        slowPollJob?.cancel()
    }
}
