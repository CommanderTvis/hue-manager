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
    val excludedLampIds: Set<String> = emptySet(),
    val sensors: List<SensorInfo> = emptyList(),
    val toggleButtonSensorId: String? = null,
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
                val previousVersion = _uiState.value.syncVersion
                _uiState.value = _uiState.value.copy(
                    userState = response.userState,
                    automationMode = response.automationMode,
                    automationColor = response.automationColor,
                    overriddenLampIds = response.overriddenLamps,
                    pendingLampIds = response.pendingLampIds.toSet(),
                    syncVersion = response.version
                )
                // Server signals that lamp state may have changed (wake/sleep,
                // smart-button press, override mutations). Refresh lamps now
                // instead of waiting for the next slow poll.
                if (response.version != previousVersion) {
                    pollLamps()
                }
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
                        excludedLampIds = response.excludedLampIds.toSet(),
                        toggleButtonSensorId = response.toggleButtonSensorId,
                    )
                },
                onFailure = { /* ignore */ }
            )

            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Wrap an API call with pending-state bookkeeping: register the lamps as pending on the
     * server and locally before the call, clear them after, and surface failures as errors.
     * On success, [onSuccess] runs with the call result before pending is cleared locally.
     */
    private fun <T> withPending(
        lampIds: List<String>,
        apiCall: suspend () -> Result<T>,
        onSuccess: suspend (T) -> Unit,
    ): Job = viewModelScope.launch {
        val pendingSet = lampIds.toSet()
        apiClient.addPendingOperations(lampIds)
        _uiState.value = _uiState.value.copy(
            pendingLampIds = _uiState.value.pendingLampIds + pendingSet
        )

        val result = apiCall()
        apiClient.clearPendingOperations(lampIds)

        result.fold(
            onSuccess = { value ->
                onSuccess(value)
                _uiState.value = _uiState.value.copy(
                    pendingLampIds = _uiState.value.pendingLampIds - pendingSet
                )
            },
            onFailure = { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.message,
                    pendingLampIds = _uiState.value.pendingLampIds - pendingSet
                )
            }
        )
    }

    fun toggleLamp(lamp: Lamp) {
        withPending(
            lampIds = listOf(lamp.id),
            apiCall = { apiClient.updateLamp(lamp.id, LampUpdateRequest(on = !lamp.on)) },
        ) {
            _uiState.value = _uiState.value.copy(
                lamps = _uiState.value.lamps.map { if (it.id == lamp.id) it.copy(on = !it.on) else it },
                overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id,
            )
        }
    }

    fun setBrightness(lamp: Lamp, brightness: Int) {
        withPending(
            lampIds = listOf(lamp.id),
            apiCall = { apiClient.updateLamp(lamp.id, LampUpdateRequest(brightness = brightness)) },
        ) {
            _uiState.value = _uiState.value.copy(
                lamps = _uiState.value.lamps.map { if (it.id == lamp.id) it.copy(brightness = brightness) else it },
                overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id,
            )
        }
    }

    fun setLampColor(lamp: Lamp, hue: Int, saturation: Int) {
        withPending(
            lampIds = listOf(lamp.id),
            apiCall = { apiClient.updateLamp(lamp.id, LampUpdateRequest(hue = hue, saturation = saturation)) },
        ) {
            _uiState.value = _uiState.value.copy(
                lamps = _uiState.value.lamps.map {
                    if (it.id == lamp.id) it.copy(hue = hue, saturation = saturation) else it
                },
                overriddenLampIds = _uiState.value.overriddenLampIds + lamp.id,
            )
        }
    }

    fun setAllLamps(on: Boolean) {
        val allLampIds = _uiState.value.lamps.map { it.id }
        withPending(
            lampIds = allLampIds,
            apiCall = { apiClient.updateAllLamps(AllLampsUpdateRequest(on = on)) },
        ) {
            _uiState.value = _uiState.value.copy(
                lamps = _uiState.value.lamps.map { it.copy(on = on) },
                overriddenLampIds = (_uiState.value.overriddenLampIds + allLampIds).distinct(),
            )
        }
    }

    fun wakeUp() {
        val allLampIds = _uiState.value.lamps.map { it.id }
        withPending(
            lampIds = allLampIds,
            apiCall = { apiClient.wakeUp() },
        ) { response ->
            _uiState.value = _uiState.value.copy(userState = response.state)
            // Immediately refresh lamp and sync state after wake/sleep change
            pollSync()
            pollLamps()
        }
    }

    fun goToSleep() {
        val allLampIds = _uiState.value.lamps.map { it.id }
        withPending(
            lampIds = allLampIds,
            apiCall = { apiClient.sleep() },
        ) { response ->
            _uiState.value = _uiState.value.copy(userState = response.state)
            pollSync()
            pollLamps()
        }
    }

    fun clearOverride(lampId: String) {
        withPending(
            lampIds = listOf(lampId),
            apiCall = { apiClient.clearLampOverride(lampId) },
        ) {
            _uiState.value = _uiState.value.copy(
                overriddenLampIds = _uiState.value.overriddenLampIds - lampId,
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

    fun loadSensors() {
        viewModelScope.launch {
            val result = apiClient.getSensors()
            result.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(sensors = response.sensors)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            )
        }
    }

    fun updateToggleButton(sensorId: String?) {
        viewModelScope.launch {
            val previous = _uiState.value.toggleButtonSensorId
            _uiState.value = _uiState.value.copy(toggleButtonSensorId = sensorId)
            // Server uses an empty string to clear the selection, since null means "no change".
            val result = apiClient.updateSettings(
                SettingsUpdateRequest(toggleButtonSensorId = sensorId ?: "")
            )
            result.fold(
                onSuccess = { },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        toggleButtonSensorId = previous,
                        error = e.message,
                    )
                }
            )
        }
    }

    fun updateExcludedLamps(excludedLampIds: Set<String>) {
        viewModelScope.launch {
            val previous = _uiState.value.excludedLampIds
            _uiState.value = _uiState.value.copy(excludedLampIds = excludedLampIds)
            val result = apiClient.updateSettings(
                SettingsUpdateRequest(excludedLampIds = excludedLampIds.toList())
            )
            result.fold(
                onSuccess = { /* state already updated optimistically */ },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        excludedLampIds = previous,
                        error = e.message,
                    )
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
