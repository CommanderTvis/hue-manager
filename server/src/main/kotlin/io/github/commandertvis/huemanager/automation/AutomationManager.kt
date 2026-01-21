package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.GeoLocation
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import kotlinx.coroutines.*
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class UserState {
    AWAKE, ASLEEP
}

enum class AutomationMode {
    AUTO_COMPENSATION,  // Compensating for lack of daylight (dark mornings, after sunset, 0% daylight)
    EVENING,            // Warm orange light (pseudo-sunset period)
    NIGHT,              // Dim warm light
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
        val localNow = Clock.System.now().toLocalDateTime(timeZone)
        val currentTime = localNow.time

        val pseudoSunsetEnd = LocalTime(
            (pseudoSunset.hour + 3) % 24,
            pseudoSunset.minute
        )

        // Handle midnight rollover: if pseudoSunsetEnd crosses midnight (e.g., 21:05 + 3h = 00:05),
        // we need special logic to determine if we're in the evening window
        val crossesMidnight = pseudoSunsetEnd < pseudoSunset
        val isInEvening = if (crossesMidnight) {
            // Evening window spans midnight: e.g., 21:05 to 00:05
            currentTime >= pseudoSunset || currentTime < pseudoSunsetEnd
        } else {
            currentTime >= pseudoSunset && currentTime < pseudoSunsetEnd
        }

        // Night mode: after evening window ends until next pseudo-sunset
        val isInNightMode = if (crossesMidnight) {
            currentTime >= pseudoSunsetEnd && currentTime < pseudoSunset
        } else {
            currentTime >= pseudoSunsetEnd
        }

        return when {
            isInEvening -> AutomationMode.EVENING
            isInNightMode -> AutomationMode.NIGHT
            else -> AutomationMode.AUTO_COMPENSATION
        }
    }

    fun getAutomationColor(): LampColorInfo {
        val mode = getCurrentAutomationMode()

        return when (mode) {
            AutomationMode.AUTO_COMPENSATION -> LampColorInfo(
                hue = null,
                saturation = null,
                colorTemperature = 153,
                brightness = 254,
                description = "Bright white"
            )

            AutomationMode.EVENING -> LampColorInfo(
                hue = 5000,
                saturation = 254,
                colorTemperature = null,
                brightness = 254,
                description = "Bright orange"
            )

            AutomationMode.NIGHT -> LampColorInfo(
                hue = 5000,
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
        logger.info("Added override for lamp $lampId until $overrideUntil")
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
            if (lampOverrides.containsKey(lampId)) {
                continue // Skip overridden lamps
            }
            if (entertainmentLamps.contains(lampId)) {
                continue // Skip lamps in active entertainment areas
            }
            hueService.setLightState(lampId, desiredState)
        }
    }

    private fun calculateDesiredState(currentTime: LocalTime): HueLightStateUpdate {
        // Calculate pseudo-sunset end (3 hours after pseudo-sunset)
        val pseudoSunsetEnd = LocalTime(
            (pseudoSunset.hour + 3) % 24,
            pseudoSunset.minute
        )

        // Handle midnight rollover for evening window
        val crossesMidnight = pseudoSunsetEnd < pseudoSunset
        val isInEvening = if (crossesMidnight) {
            currentTime >= pseudoSunset || currentTime < pseudoSunsetEnd
        } else {
            currentTime >= pseudoSunset && currentTime < pseudoSunsetEnd
        }

        val isInNightMode = if (crossesMidnight) {
            currentTime >= pseudoSunsetEnd && currentTime < pseudoSunset
        } else {
            currentTime >= pseudoSunsetEnd
        }

        return when {
            // Evening mode: bright orange (pseudo-sunset to pseudo-sunset+3h)
            isInEvening -> {
                HueLightStateUpdate(on = true, bri = 254, hue = 5000, sat = 254)
            }

            // Night mode: dim orange (after pseudo-sunset+3h until next pseudo-sunset)
            isInNightMode -> {
                HueLightStateUpdate(on = true, bri = 1, hue = 5000, sat = 254)
            }

            // Auto compensation: bright white (before pseudo-sunset)
            else -> {
                HueLightStateUpdate(on = true, bri = 254, ct = 153)
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
