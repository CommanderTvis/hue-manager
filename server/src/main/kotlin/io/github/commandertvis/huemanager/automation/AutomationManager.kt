package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.GeoLocation
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import kotlinx.coroutines.*
import kotlinx.datetime.*
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class UserState {
    AWAKE, ASLEEP
}

data class LampOverride(
    val lampId: String,
    val overrideUntil: Instant
)

class AutomationManager(
    private val config: Config,
    private val hueService: HueService
) {
    private val logger = LoggerFactory.getLogger(AutomationManager::class.java)

    private var userState: UserState = UserState.ASLEEP
    private var wakeUpTime: Instant? = null
    private val lampOverrides = mutableMapOf<String, LampOverride>()
    private val automatedLampIds = mutableSetOf<String>()
    private var pseudoSunset: LocalTime = parsePseudoSunset(config.pseudoSunset)

    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun getUserState(): UserState = userState
    fun getWakeUpTime(): Instant? = wakeUpTime
    fun getPseudoSunset(): LocalTime = pseudoSunset
    fun getLocation(): GeoLocation = config.region
    fun getOverriddenLampIds(): List<String> = lampOverrides.keys.toList()
    fun getAutomatedLampIds(): Set<String> = automatedLampIds.toSet()

    fun isEntertainmentActive(): Boolean {
        // TODO: Check if any entertainment group is active
        return false
    }

    suspend fun wakeUp(): UserState {
        userState = UserState.AWAKE
        wakeUpTime = Clock.System.now()
        logger.info("User woke up at $wakeUpTime")

        applyAutomatedState()
        startHeartbeat()

        return userState
    }

    suspend fun goToSleep(): UserState {
        userState = UserState.ASLEEP
        wakeUpTime = null
        logger.info("User going to sleep")

        stopHeartbeat()

        // Turn off all automated lamps
        for (lampId in automatedLampIds) {
            hueService.setLightState(lampId, HueLightStateUpdate(on = false))
        }

        return userState
    }

    fun addLampOverride(lampId: String) {
        val overrideUntil = Clock.System.now() + 1.hours
        lampOverrides[lampId] = LampOverride(lampId, overrideUntil)
        logger.info("Added override for lamp $lampId until $overrideUntil")
    }

    fun clearLampOverride(lampId: String) {
        lampOverrides.remove(lampId)
        logger.info("Cleared override for lamp $lampId")
    }

    fun setAutomatedLamps(lampIds: Set<String>) {
        automatedLampIds.clear()
        automatedLampIds.addAll(lampIds)
        logger.info("Set automated lamps: $automatedLampIds")
    }

    fun setPseudoSunset(time: LocalTime) {
        pseudoSunset = time
        logger.info("Set pseudo sunset to $time")
    }

    fun setPseudoSunset(timeStr: String) {
        pseudoSunset = parsePseudoSunset(timeStr)
        logger.info("Set pseudo sunset to $pseudoSunset")
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(10.minutes)
                cleanExpiredOverrides()
                if (userState == UserState.AWAKE) {
                    applyAutomatedState()
                }
            }
        }
        logger.info("Started heartbeat")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        logger.info("Stopped heartbeat")
    }

    private fun cleanExpiredOverrides() {
        val now = Clock.System.now()
        val expired = lampOverrides.filter { it.value.overrideUntil < now }
        expired.keys.forEach { lampOverrides.remove(it) }
        if (expired.isNotEmpty()) {
            logger.info("Cleaned ${expired.size} expired overrides")
        }
    }

    private suspend fun applyAutomatedState() {
        if (userState != UserState.AWAKE) return

        val now = Clock.System.now()
        val timeZone = TimeZone.of(config.timezone)
        val localNow = now.toLocalDateTime(timeZone)
        val currentTime = localNow.time

        val desiredState = calculateDesiredState(currentTime)

        for (lampId in automatedLampIds) {
            if (lampOverrides.containsKey(lampId)) {
                continue // Skip overridden lamps
            }
            hueService.setLightState(lampId, desiredState)
        }
    }

    private fun calculateDesiredState(currentTime: LocalTime): HueLightStateUpdate {
        val sunTimes = calculateSunTimes()
        val sunriseTime = sunTimes.first
        val sunsetTime = sunTimes.second

        // Time comparisons
        val isBeforeSunrise = currentTime < sunriseTime
        val isAfterSunset = currentTime > sunsetTime
        val isAfterPseudoSunset = currentTime >= pseudoSunset

        // Calculate pseudo-sunset end (3 hours after pseudo-sunset)
        val pseudoSunsetEnd = LocalTime(
            (pseudoSunset.hour + 3) % 24,
            pseudoSunset.minute
        )
        val isInWindDown = currentTime >= pseudoSunset && currentTime < pseudoSunsetEnd

        return when {
            // Before sunrise: full bright white
            isBeforeSunrise -> {
                HueLightStateUpdate(on = true, bri = 254, ct = 153)
            }

            // Between sunrise and sunset: dimmer, daylight coming in
            !isAfterSunset && !isAfterPseudoSunset -> {
                HueLightStateUpdate(on = true, bri = 100, ct = 200)
            }

            // After actual sunset but before pseudo-sunset: bright compensating
            isAfterSunset && !isAfterPseudoSunset -> {
                HueLightStateUpdate(on = true, bri = 254, ct = 153)
            }

            // In wind-down period (pseudo-sunset to pseudo-sunset+3h)
            isInWindDown -> {
                val minutesIntoWindDown = (currentTime.hour * 60 + currentTime.minute) -
                        (pseudoSunset.hour * 60 + pseudoSunset.minute)
                val progress = (minutesIntoWindDown.toFloat() / 180f).coerceIn(0f, 1f)

                // Transition brightness from 254 to 1
                val brightness = (254 * (1 - progress)).toInt().coerceIn(1, 254)

                // Orange hue for evening
                HueLightStateUpdate(on = true, bri = brightness, hue = 5000, sat = 254)
            }

            // After wind-down: minimal orange light
            else -> {
                HueLightStateUpdate(on = true, bri = 1, hue = 5000, sat = 254)
            }
        }
    }

    private fun calculateSunTimes(): Pair<LocalTime, LocalTime> {
        // Simplified sun calculation
        // For accurate results, use a proper astronomical library
        val lat = config.region.latitude
        val now = Clock.System.now()
        val timeZone = TimeZone.of(config.timezone)
        val dayOfYear = now.toLocalDateTime(timeZone).dayOfYear

        // Approximate sunrise/sunset based on latitude and day of year
        // This is a rough approximation - production code should use proper solar calculations
        val declination = -23.45 * kotlin.math.cos(kotlin.math.PI * 2 * (dayOfYear + 10) / 365)
        val latRad = lat * kotlin.math.PI / 180
        val decRad = declination * kotlin.math.PI / 180

        val hourAngle = kotlin.math.acos(
            -kotlin.math.tan(latRad) * kotlin.math.tan(decRad)
        ).coerceIn(-1.0, 1.0)

        val sunriseHour = 12 - (hourAngle * 180 / kotlin.math.PI / 15)
        val sunsetHour = 12 + (hourAngle * 180 / kotlin.math.PI / 15)

        val sunrise = LocalTime(sunriseHour.toInt().coerceIn(0, 23), ((sunriseHour % 1) * 60).toInt())
        val sunset = LocalTime(sunsetHour.toInt().coerceIn(0, 23), ((sunsetHour % 1) * 60).toInt())

        return sunrise to sunset
    }

    private fun parsePseudoSunset(timeStr: String): LocalTime {
        return try {
            val parts = timeStr.split(":")
            LocalTime(parts[0].toInt(), parts[1].toInt())
        } catch (e: Exception) {
            LocalTime(21, 5) // Default: 21:05
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
