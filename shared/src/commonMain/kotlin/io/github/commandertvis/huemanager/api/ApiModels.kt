package io.github.commandertvis.huemanager.api

import io.github.commandertvis.huemanager.models.*
import kotlinx.serialization.Serializable

// Status endpoint responses
@Serializable
data class StatusResponse(
    val connected: Boolean,
    val bridgeIp: String?,
    val needsLinking: Boolean,
    val automationState: UserState?,
    val entertainmentActive: Boolean
)

// Lamp endpoints
@Serializable
data class LampsResponse(
    val lamps: List<Lamp>
)

@Serializable
data class LampUpdateRequest(
    val on: Boolean? = null,
    val brightness: Int? = null,
    val hue: Int? = null,
    val saturation: Int? = null,
    val colorTemperature: Int? = null,
    val transitionTime: Int? = null
)

@Serializable
data class AllLampsUpdateRequest(
    val on: Boolean? = null,
    val brightness: Int? = null
)

// Group endpoints
@Serializable
data class GroupsResponse(
    val groups: List<Group>
)

// Automation endpoints
@Serializable
data class WakeUpResponse(
    val success: Boolean,
    val state: UserState
)

@Serializable
data class SleepResponse(
    val success: Boolean,
    val state: UserState
)

@Serializable
data class AutomationStatusResponse(
    val userState: UserState,
    val wakeUpTime: String?,
    val pseudoSunset: String,
    val entertainmentActive: Boolean,
    val overriddenLamps: List<String>,
    val automationMode: String,
    val automationColor: AutomationColorInfo
)

@Serializable
data class AutomationColorInfo(
    val hue: Int?,
    val saturation: Int?,
    val colorTemperature: Int?,
    val brightness: Int,
    val description: String
)

// Settings endpoints
@Serializable
data class SettingsResponse(
    val pseudoSunset: String,
    val latitude: Double,
    val longitude: Double,
    val automatedLampIds: List<String>
)

@Serializable
data class SettingsUpdateRequest(
    val pseudoSunset: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val automatedLampIds: List<String>? = null
)

// Bridge linking
@Serializable
data class LinkStatusResponse(
    val status: LinkStatus,
    val message: String
)

@Serializable
enum class LinkStatus {
    NOT_NEEDED,
    WAITING_FOR_BUTTON,
    SUCCESS,
    FAILED
}

// Bridge discovery
@Serializable
data class DiscoveredBridge(
    val id: String,
    val internalipaddress: String,
    val port: Int? = null
)

// Bridge configuration from client
@Serializable
data class BridgeConfigRequest(
    val bridgeIp: String,
    val username: String? = null  // If null, server will try to link
)

@Serializable
data class BridgeConfigResponse(
    val success: Boolean,
    val connected: Boolean,
    val bridgeIp: String?,
    val needsLinking: Boolean,
    val message: String
)

// Generic API responses
@Serializable
data class ApiError(
    val error: String,
    val code: Int
)

@Serializable
data class ApiSuccess(
    val message: String
)

@Serializable
data class GenericResponse(
    val success: Boolean,
    val message: String
)
