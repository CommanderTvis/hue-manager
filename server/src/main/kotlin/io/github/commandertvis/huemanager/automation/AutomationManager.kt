package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.GeoLocation
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class UserState {
    AWAKE, ASLEEP
}

enum class AutomationMode {
    AUTO_COMPENSATION,  // Compensating for lack of daylight - brightness inversely proportional to sunlight
    EVENING,            // Warm orange light (pseudo-sunset to pseudo-sunset+3h)
    NIGHT,              // Dim warm light (pseudo-sunset+3h until sleep)
    USER_ASLEEP         // User is asleep, lamps off
}

data class LampColorInfo(
    val hue: Int?,
    val saturation: Int?,
    val colorTemperature: Int?,
    val brightness: Int,
    val description: String
)

data class LampOverride(
    val lampId: String,
    val overrideUntil: Instant
)

data class PendingOperation(
    val lampId: String,
    val startedAt: Instant
)

/**
 * Tracks lamp reachability state to detect when lamps come back online.
 * When a lamp transitions from unreachable to reachable, automation should
 * be applied immediately without the 1-hour grace period.
 */
data class LampReachabilityState(
    val lampId: String,
    val wasReachable: Boolean,
    val lastChecked: Instant
)

class AutomationManager(
    private val config: Config,
    private val hueService: HueService
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(AutomationManager::class.java)

    private var userState: UserState = UserState.ASLEEP
    private var wakeUpTime: Instant? = null
    private val lampOverrides = mutableMapOf<String, LampOverride>()
    private val automatedLampIds = mutableSetOf<String>()
    private var pseudoSunset: LocalTime = parsePseudoSunset(config.pseudoSunset)

    // Pending operations for real-time sync across clients
    private val pendingOperations = mutableMapOf<String, PendingOperation>()

    // Track lamp reachability to detect when lamps come back online
    private val lampReachability = mutableMapOf<String, LampReachabilityState>()

    @Volatile
    private var syncVersion: Long = 0L

    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun getUserState(): UserState = userState
    fun getWakeUpTime(): Instant? = wakeUpTime
    fun getPseudoSunset(): LocalTime = pseudoSunset
    fun getLocation(): GeoLocation = config.region

    suspend fun getOverriddenLampIds(): List<String> {
        val manualOverrides = lampOverrides.keys.toSet()
        val outOfSyncLamps = getOutOfSyncLamps()
        return (manualOverrides + outOfSyncLamps).toList()
    }

    fun getAutomatedLampIds(): Set<String> = automatedLampIds.toSet()

    private suspend fun getOutOfSyncLamps(): Set<String> {
        if (userState != UserState.AWAKE) {
            // When user is asleep, check if any automated lamps are on
            val outOfSync = mutableSetOf<String>()
            for (lampId in automatedLampIds) {
                if (lampOverrides.containsKey(lampId)) continue // Already tracked as manual override

                val light = hueService.getLight(lampId) ?: continue
                // Only consider reachable lamps as out-of-sync
                if (light.state.reachable != true) continue
                if (light.state.on) {
                    outOfSync.add(lampId)
                }
            }
            return outOfSync
        }

        // When user is awake, compare actual state with automation target
        val timeZone = TimeZone.of(config.timezone)
        val localNow = Clock.System.now().toLocalDateTime(timeZone)
        val targetState = calculateDesiredState(localNow.time)
        val entertainmentLamps = getActiveEntertainmentLamps()
        val outOfSync = mutableSetOf<String>()

        for (lampId in automatedLampIds) {
            if (lampOverrides.containsKey(lampId)) continue // Already tracked as manual override
            if (entertainmentLamps.contains(lampId)) continue // Skip entertainment lamps

            val light = hueService.getLight(lampId) ?: continue
            // Only consider reachable lamps as out-of-sync
            // Unreachable lamps will be handled when they become reachable again
            if (light.state.reachable != true) continue
            if (!isLampInSync(light.state, targetState)) {
                outOfSync.add(lampId)
            }
        }

        return outOfSync
    }

    private fun isLampInSync(
        actualState: io.github.commandertvis.huemanager.hue.HueLightState,
        targetState: HueLightStateUpdate
    ): Boolean {
        // Check on/off state
        if (targetState.on != null && actualState.on != targetState.on) {
            return false
        }

        // If lamp is off and target is off, consider it in sync
        if (!actualState.on && targetState.on == false) {
            return true
        }

        // If lamp is on, check brightness and color
        if (actualState.on) {
            // Check brightness (allow 10% tolerance)
            if (targetState.bri != null) {
                val actualBri = actualState.bri ?: 254
                val tolerance = 25 // ~10% of 254
                if (kotlin.math.abs(actualBri - targetState.bri) > tolerance) {
                    return false
                }
            }

            // Check color mode: hue/sat vs color temperature
            if (targetState.hue != null && targetState.sat != null) {
                // Target is using hue/sat mode
                val actualHue = actualState.hue ?: 0
                val actualSat = actualState.sat ?: 0
                val hueTolerance = 3000 // ~5% of 65535
                val satTolerance = 25 // ~10% of 254

                if (kotlin.math.abs(actualHue - targetState.hue) > hueTolerance ||
                    kotlin.math.abs(actualSat - targetState.sat) > satTolerance
                ) {
                    return false
                }
            } else if (targetState.ct != null) {
                // Target is using color temperature mode
                val actualCt = actualState.ct ?: 153
                val ctTolerance = 30 // Allow some variation

                if (kotlin.math.abs(actualCt - targetState.ct) > ctTolerance) {
                    return false
                }
            }
        }

        return true
    }

    fun getCurrentAutomationMode(): AutomationMode {
        if (userState != UserState.AWAKE) {
            return AutomationMode.USER_ASLEEP
        }

        val timeZone = TimeZone.of(config.timezone)
        val now = Clock.System.now()
        val localNow = now.toLocalDateTime(timeZone)
        val currentTime = localNow.time

        // Calculate sun times for today
        val sunTimes = SunCalculator.calculateSunTimes(now, config.region, timeZone)

        // Calculate pseudo-sunset window (3 hours after pseudo-sunset)
        val pseudoSunsetEnd = LocalTime(
            (pseudoSunset.hour + 3) % 24,
            pseudoSunset.minute
        )

        // Check evening/night modes first (these take priority over daylight modes)
        val currentMinutes = currentTime.hour * 60 + currentTime.minute
        val pseudoSunsetMinutes = pseudoSunset.hour * 60 + pseudoSunset.minute
        val pseudoSunsetEndMinutes = pseudoSunsetEnd.hour * 60 + pseudoSunsetEnd.minute

        // Handle midnight rollover for pseudo-sunset window
        val crossesMidnight = pseudoSunsetEndMinutes < pseudoSunsetMinutes

        val isInEvening = if (crossesMidnight) {
            // Evening spans midnight: e.g., 21:05 to 00:05
            currentMinutes >= pseudoSunsetMinutes || currentMinutes < pseudoSunsetEndMinutes
        } else {
            currentMinutes in pseudoSunsetMinutes until pseudoSunsetEndMinutes
        }

        if (isInEvening) {
            return AutomationMode.EVENING
        }

        // Night mode: after pseudo-sunset+3h until sunrise
        if (crossesMidnight) {
            // Pseudo-sunset+3h crosses midnight (e.g., 00:05)
            // Night is from 00:05 until sunrise
            if (currentMinutes in pseudoSunsetEndMinutes until pseudoSunsetMinutes) {
                val sunriseMinutes = sunTimes?.sunrise?.let { it.hour * 60 + it.minute } ?: 360
                if (currentMinutes < sunriseMinutes) {
                    return AutomationMode.NIGHT
                }
            }
        } else {
            // Pseudo-sunset+3h doesn't cross midnight
            // Night is from pseudo-sunset+3h until midnight, then midnight until sunrise
            if (currentMinutes >= pseudoSunsetEndMinutes) {
                return AutomationMode.NIGHT
            }
            if (currentMinutes < pseudoSunsetMinutes) {
                val sunriseMinutes = sunTimes?.sunrise?.let { it.hour * 60 + it.minute } ?: 360
                if (currentMinutes < sunriseMinutes) {
                    return AutomationMode.NIGHT
                }
            }
        }

        // Everything else is AUTO_COMPENSATION (with smooth brightness based on sun position)
        return AutomationMode.AUTO_COMPENSATION
    }

    fun getAutomationColor(): LampColorInfo {
        val mode = getCurrentAutomationMode()

        return when (mode) {
            AutomationMode.AUTO_COMPENSATION -> {
                // Calculate smooth brightness based on sun position
                val brightness = calculateAutoCompensationBrightness()
                val description = if (brightness >= 254) "Bright white"
                else if (brightness >= 100) "Dimmed white (${brightness * 100 / 254}%)"
                else if (brightness > 0) "Low white (${brightness * 100 / 254}%)"
                else "Off (bright daylight)"

                LampColorInfo(
                    hue = null,
                    saturation = null,
                    colorTemperature = if (brightness > 0) 153 else null, // Cool white (6500K)
                    brightness = brightness,
                    description = description
                )
            }

            AutomationMode.EVENING -> LampColorInfo(
                hue = 5000, // Orange
                saturation = 254,
                colorTemperature = null,
                brightness = 254,
                description = "Bright orange"
            )

            AutomationMode.NIGHT -> LampColorInfo(
                hue = 5000, // Orange
                saturation = 254,
                colorTemperature = null,
                brightness = 1,
                description = "Dim orange"
            )

            AutomationMode.USER_ASLEEP -> LampColorInfo(
                hue = null,
                saturation = null,
                colorTemperature = null,
                brightness = 0,
                description = "Off"
            )
        }
    }

    /**
     * Calculate brightness for AUTO_COMPENSATION mode based on sun position.
     * - Before sunrise: 100% brightness
     * - Sunrise to solar noon: brightness decreases smoothly (100% -> 0%)
     * - Solar noon to sunset: brightness increases smoothly (0% -> 100%)
     * - After sunset: 100% brightness
     */
    private fun calculateAutoCompensationBrightness(): Int {
        val timeZone = TimeZone.of(config.timezone)
        val now = Clock.System.now()
        val localNow = now.toLocalDateTime(timeZone)
        val currentTime = localNow.time
        val currentMinutes = currentTime.hour * 60 + currentTime.minute

        val sunTimes = SunCalculator.calculateSunTimes(now, config.region, timeZone)
            ?: return 254 // No sun times (polar region) - full brightness

        val sunriseMinutes = sunTimes.sunrise.hour * 60 + sunTimes.sunrise.minute
        val sunsetMinutes = sunTimes.sunset.hour * 60 + sunTimes.sunset.minute
        val solarNoonMinutes = sunTimes.solarNoon.hour * 60 + sunTimes.solarNoon.minute

        // Before sunrise or after sunset: full brightness
        if (currentMinutes < sunriseMinutes || currentMinutes > sunsetMinutes) {
            return 254
        }

        // Calculate sun position as a fraction (0 = sunrise, 0.5 = noon, 1 = sunset)
        val daylightDuration = sunsetMinutes - sunriseMinutes
        if (daylightDuration <= 0) return 254

        val minutesSinceSunrise = currentMinutes - sunriseMinutes
        val sunPosition = minutesSinceSunrise.toDouble() / daylightDuration

        // Convert sun position to brightness (inverted parabola)
        // At sunrise (0) and sunset (1): brightness = 100%
        // At solar noon (0.5): brightness = 0%
        // Using: brightness = 1 - 4 * (position - 0.5)^2 inverted = 4 * (position - 0.5)^2
        val sunIntensity = 1.0 - 4.0 * (sunPosition - 0.5) * (sunPosition - 0.5)

        // Invert: when sun is brightest, lamp should be dimmest
        val lampBrightness = 1.0 - sunIntensity

        // Scale to 0-254 range
        return (lampBrightness * 254).toInt().coerceIn(0, 254)
    }

    fun isEntertainmentActive(): Boolean {
        return runBlocking {
            getActiveEntertainmentLamps().isNotEmpty()
        }
    }

    private suspend fun getActiveEntertainmentLamps(): Set<String> {
        val entertainmentGroups = hueService.getEntertainmentGroups()
        val activeLamps = mutableSetOf<String>()

        for ((_, group) in entertainmentGroups) {
            if (group.stream?.active == true) {
                activeLamps.addAll(group.lights)
            }
        }

        logger.debug("Active entertainment lamps: {}", activeLamps)
        return activeLamps
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
        incrementSyncVersion()
        logger.info("Added override for lamp $lampId until $overrideUntil")
    }

    // Pending operations for cross-client synchronization
    fun getSyncVersion(): Long = syncVersion

    fun getPendingLampIds(): List<String> {
        cleanExpiredPendingOperations()
        return pendingOperations.keys.toList()
    }

    fun addPendingOperations(lampIds: List<String>) {
        val now = Clock.System.now()
        lampIds.forEach { lampId ->
            pendingOperations[lampId] = PendingOperation(lampId, now)
        }
        incrementSyncVersion()
        logger.debug("Added pending operations for lamps: $lampIds")
    }

    fun clearPendingOperations(lampIds: List<String>) {
        lampIds.forEach { pendingOperations.remove(it) }
        incrementSyncVersion()
        logger.debug("Cleared pending operations for lamps: $lampIds")
    }

    fun clearAllPendingOperations() {
        pendingOperations.clear()
        incrementSyncVersion()
    }

    private fun cleanExpiredPendingOperations() {
        val now = Clock.System.now()
        val timeout = 5000L // 5 seconds timeout
        val expired = pendingOperations.filter {
            (now - it.value.startedAt).inWholeMilliseconds > timeout
        }
        if (expired.isNotEmpty()) {
            expired.keys.forEach { pendingOperations.remove(it) }
            incrementSyncVersion()
            logger.debug("Cleaned ${expired.size} expired pending operations")
        }
    }

    private fun incrementSyncVersion() {
        syncVersion++
    }

    suspend fun clearLampOverride(lampId: String) {
        lampOverrides.remove(lampId)
        logger.info("Cleared override for lamp $lampId")

        // Immediately apply automation state to this lamp
        if (lampId in automatedLampIds) {
            val entertainmentLamps = getActiveEntertainmentLamps()
            if (!entertainmentLamps.contains(lampId)) {
                if (userState == UserState.AWAKE) {
                    // User is awake: apply calculated automation state
                    val timeZone = TimeZone.of(config.timezone)
                    val localNow = Clock.System.now().toLocalDateTime(timeZone)
                    val desiredState = calculateDesiredState(localNow.time)
                    hueService.setLightState(lampId, desiredState)
                    logger.info("Applied automation state to lamp $lampId after clearing override")
                } else {
                    // User is asleep: turn off the lamp
                    hueService.setLightState(lampId, HueLightStateUpdate(on = false))
                    logger.info("Turned off lamp $lampId after clearing override (user asleep)")
                }
            }
        }
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
        val entertainmentLamps = getActiveEntertainmentLamps()

        for (lampId in automatedLampIds) {
            if (entertainmentLamps.contains(lampId)) {
                continue // Skip lamps in active entertainment areas
            }

            // Check current lamp state for reachability tracking
            val light = hueService.getLight(lampId)
            val isReachable = light?.state?.reachable ?: false
            val previousState = lampReachability[lampId]
            val wasUnreachable = previousState?.wasReachable == false

            // Update reachability tracking
            lampReachability[lampId] = LampReachabilityState(lampId, isReachable, now)

            // If lamp just became reachable, apply automation immediately (ignore overrides)
            if (isReachable && wasUnreachable) {
                logger.info("Lamp $lampId became reachable, applying automation immediately")
                // Remove any existing override since lamp was unreachable
                lampOverrides.remove(lampId)
                hueService.setLightState(lampId, desiredState)
                continue
            }

            // For normally reachable lamps, respect overrides
            if (lampOverrides.containsKey(lampId)) {
                continue // Skip overridden lamps
            }

            // Apply automation to reachable lamps without overrides
            if (isReachable) {
                hueService.setLightState(lampId, desiredState)
            }
        }
    }

    private fun calculateDesiredState(currentTime: LocalTime): HueLightStateUpdate {
        val mode = getCurrentAutomationMode()

        return when (mode) {
            AutomationMode.AUTO_COMPENSATION -> {
                // Smooth brightness based on sun position
                val brightness = calculateAutoCompensationBrightness()
                if (brightness > 0) {
                    HueLightStateUpdate(on = true, bri = brightness, ct = 153)
                } else {
                    HueLightStateUpdate(on = false)
                }
            }

            AutomationMode.EVENING -> {
                // Bright orange (pseudo-sunset to pseudo-sunset+3h)
                HueLightStateUpdate(on = true, bri = 254, hue = 5000, sat = 254)
            }

            AutomationMode.NIGHT -> {
                // Dim orange (after pseudo-sunset+3h)
                HueLightStateUpdate(on = true, bri = 1, hue = 5000, sat = 254)
            }

            AutomationMode.USER_ASLEEP -> {
                // Off when user is asleep
                HueLightStateUpdate(on = false)
            }
        }
    }

    private fun parsePseudoSunset(timeStr: String): LocalTime {
        return try {
            val parts = timeStr.split(":")
            LocalTime(parts[0].toInt(), parts[1].toInt())
        } catch (e: Exception) {
            LocalTime(21, 5) // Default: 21:05
        }
    }

    override fun close() = scope.cancel()
}
