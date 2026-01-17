package io.github.commandertvis.huemanager.models

import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

@Serializable
data class AutomationState(
    val userState: UserState,
    val wakeUpTime: Instant?,
    val pseudoSunset: LocalTime,
    val location: GeoLocation,
    val lampOverrides: Map<String, LampOverride>,
    val entertainmentActive: Boolean
)

@Serializable
enum class UserState {
    AWAKE,
    ASLEEP;

    fun toggle(): UserState = when (this) {
        AWAKE -> ASLEEP
        ASLEEP -> AWAKE
    }
}

@Serializable
data class LampOverride(
    val lampId: String,
    val overrideUntil: Instant,
    val manualState: LampState
)

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class SunTimes(
    val sunrise: Instant,
    val sunset: Instant,
    val solarNoon: Instant
)

@Serializable
data class AutomationSettings(
    val pseudoSunset: LocalTime,
    val location: GeoLocation,
    val automatedLampIds: Set<String>
)

// Represents what the automation thinks the lamp state should be right now
@Serializable
data class DesiredLampState(
    val lampId: String,
    val state: LampState,
    val reason: AutomationReason
)

@Serializable
enum class AutomationReason {
    WAKE_UP_COMPENSATION,  // Compensating for sun not being up yet
    DAYLIGHT,              // Sun is up, minimal light needed
    EVENING_TRANSITION,    // Transitioning to orange/dim
    NIGHT_MODE,            // Very dim orange light
    MANUAL_OVERRIDE,       // User manually controlled this lamp
    ENTERTAINMENT_PAUSE,   // Paused for Hue Sync
    USER_ASLEEP            // User is asleep, lamps should be off
}
