package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.GeoLocation
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LampStateCache
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class UserState {
    AWAKE, ASLEEP
}

enum class AutomationMode {
    AUTO_COMPENSATION,  // Sun down = warm white on, sun up = off
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

/**
 * Tracks lamp power state to detect recently turned-on lamps.
 */
data class LampPowerState(
    val lampId: String,
    val isOn: Boolean,
    val lastOnAt: Instant?
)

private const val BRIGHTNESS_TOLERANCE = 25
private const val HUE_TOLERANCE = 3000
private const val SATURATION_TOLERANCE = 25
private const val COLOR_TEMPERATURE_TOLERANCE = 30
private const val DEFAULT_SUNRISE_MINUTES = 6 * 60
private const val PSEUDO_SUNSET_WINDOW_HOURS = 3

// Default automation color configs
private const val DEFAULT_MAX_BRIGHTNESS = 254
private const val DEFAULT_MIN_BRIGHTNESS = 1
private const val DEFAULT_WARM_WHITE_CT = 350
private const val DEFAULT_ORANGE_HUE = 5000
private const val DEFAULT_FULL_SATURATION = 254

data class ModeColorConfig(
    val hue: Int? = null,
    val saturation: Int? = null,
    val colorTemperature: Int? = null,
    val brightness: Int,
)

class AutomationManager(
    private val config: Config,
    private val hueService: HueService,
    private val lampStateCache: LampStateCache
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(AutomationManager::class.java)
    private val overrideDuration = 1.hours
    private val recentOnGracePeriod = 5.seconds
    private val pendingOperationTimeout = 5.seconds
    private val timeZone = TimeZone.of(config.timezone)

    private var userState: UserState = UserState.ASLEEP
    private var wakeUpTime: Instant? = null
    private val lampOverrides = mutableMapOf<String, LampOverride>()
    private val automatedLampIds = mutableSetOf<String>()
    private var pseudoSunset: LocalTime = parsePseudoSunset(config.pseudoSunset)
    private var nightTime: LocalTime = defaultNightTime(pseudoSunset)

    // Configurable automation mode colors
    private var daylightColor = ModeColorConfig(
        colorTemperature = DEFAULT_WARM_WHITE_CT, brightness = DEFAULT_MAX_BRIGHTNESS
    )
    private var eveningColor = ModeColorConfig(
        hue = DEFAULT_ORANGE_HUE, saturation = DEFAULT_FULL_SATURATION, brightness = DEFAULT_MAX_BRIGHTNESS
    )
    private var nightColor = ModeColorConfig(
        hue = DEFAULT_ORANGE_HUE, saturation = DEFAULT_FULL_SATURATION, brightness = DEFAULT_MIN_BRIGHTNESS
    )

    // Pending operations for real-time sync across clients
    private val pendingOperations = mutableMapOf<String, PendingOperation>()

    // Track lamp reachability to detect when lamps come back online
    private val lampReachability = mutableMapOf<String, LampReachabilityState>()
    private val lampPowerStates = mutableMapOf<String, LampPowerState>()

    @Volatile
    private var syncVersion: Long = 0L

    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun getUserState(): UserState = userState
    fun getWakeUpTime(): Instant? = wakeUpTime
    fun getPseudoSunset(): LocalTime = pseudoSunset
    fun getNightTime(): LocalTime = nightTime
    fun getLocation(): GeoLocation = config.region

    fun getOverriddenLampIds(): List<String> {
        cleanExpiredOverrides()
        val manualOverrides = lampOverrides.keys.toSet()
        val outOfSyncLamps = getOutOfSyncLamps()
        return (manualOverrides + outOfSyncLamps).toList()
    }

    fun getAutomatedLampIds(): Set<String> = automatedLampIds.toSet()

    fun getDaylightColor(): ModeColorConfig = daylightColor
    fun getEveningColor(): ModeColorConfig = eveningColor
    fun getNightColor(): ModeColorConfig = nightColor

    fun setDaylightColor(config: ModeColorConfig) {
        daylightColor = config
        logger.info("Set daylight color to $config")
    }

    fun setEveningColor(config: ModeColorConfig) {
        eveningColor = config
        logger.info("Set evening color to $config")
    }

    fun setNightColor(config: ModeColorConfig) {
        nightColor = config
        logger.info("Set night color to $config")
    }

    private fun getOutOfSyncLamps(): Set<String> {
        val now = Clock.System.now()
        val targetState = if (userState == UserState.AWAKE) {
            val localTime = now.toLocalDateTime(timeZone).time
            val sunTimes = SunCalculator.calculateSunTimes(now, config.region, timeZone)
            calculateDesiredState(localTime, sunTimes)
        } else {
            null
        }

        val outOfSync = mutableSetOf<String>()
        val entertainmentLamps = getActiveEntertainmentLamps()

        for (lampId in automatedLampIds) {
            val override = lampOverrides[lampId]
            if (override != null && override.overrideUntil >= now) continue // Active manual override

            val light = lampStateCache.getLight(lampId) ?: continue
            val isReachable = light.state.reachable == true
            if (!isReachable) continue

            updateLampPowerState(lampId, light.state.on, now)
            // Ignore transient mismatches right after a lamp turns on (unless Hue Sync is active).
            if (isRecentlyTurnedOn(lampId, now) && lampId !in entertainmentLamps) continue

            if (userState != UserState.AWAKE) {
                if (light.state.on) {
                    outOfSync.add(lampId)
                }
            } else {
                val desiredState = targetState ?: continue
                if (!isLampInSync(light.state, desiredState)) {
                    outOfSync.add(lampId)
                }
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
                val actualBri = actualState.bri ?: DEFAULT_MAX_BRIGHTNESS
                if (kotlin.math.abs(actualBri - targetState.bri) > BRIGHTNESS_TOLERANCE) {
                    return false
                }
            }

            // Check color mode: hue/sat vs color temperature
            if (targetState.hue != null && targetState.sat != null) {
                // Target is using hue/sat mode
                val actualHue = actualState.hue ?: 0
                val actualSat = actualState.sat ?: 0

                if (kotlin.math.abs(actualHue - targetState.hue) > HUE_TOLERANCE ||
                    kotlin.math.abs(actualSat - targetState.sat) > SATURATION_TOLERANCE
                ) {
                    return false
                }
            } else if (targetState.ct != null) {
                // Target is using color temperature mode
                val actualCt = actualState.ct ?: DEFAULT_WARM_WHITE_CT
                if (kotlin.math.abs(actualCt - targetState.ct) > COLOR_TEMPERATURE_TOLERANCE) {
                    return false
                }
            }
        }

        return true
    }

    fun getCurrentAutomationMode(): AutomationMode {
        val now = Clock.System.now()
        val localTime = now.toLocalDateTime(timeZone).time
        val sunTimes = SunCalculator.calculateSunTimes(now, config.region, timeZone)
        return calculateAutomationMode(localTime, sunTimes)
    }

    private fun calculateAutomationMode(
        currentTime: LocalTime,
        sunTimes: SunCalculator.SunTimes?
    ): AutomationMode {
        if (userState != UserState.AWAKE) {
            return AutomationMode.USER_ASLEEP
        }

        val currentMinutes = minutesSinceMidnight(currentTime)
        val pseudoSunsetMinutes = minutesSinceMidnight(pseudoSunset)
        val nightTimeMinutes = minutesSinceMidnight(nightTime)
        val sunriseMinutes = sunTimes?.sunrise?.let { minutesSinceMidnight(it) } ?: DEFAULT_SUNRISE_MINUTES

        val isEvening = isWithinRange(currentMinutes, pseudoSunsetMinutes, nightTimeMinutes)
        if (isEvening) {
            return AutomationMode.EVENING
        }

        val crossesMidnight = nightTimeMinutes < pseudoSunsetMinutes
        val isNight = if (crossesMidnight) {
            currentMinutes in nightTimeMinutes until sunriseMinutes
        } else {
            currentMinutes >= nightTimeMinutes || currentMinutes < sunriseMinutes
        }

        if (isNight) {
            return AutomationMode.NIGHT
        }

        return AutomationMode.AUTO_COMPENSATION
    }

    fun getAutomationColor(): LampColorInfo {
        val now = Clock.System.now()
        val localTime = now.toLocalDateTime(timeZone).time
        val sunTimes = SunCalculator.calculateSunTimes(now, config.region, timeZone)
        val mode = calculateAutomationMode(localTime, sunTimes)

        return when (mode) {
            AutomationMode.AUTO_COMPENSATION -> {
                val sunIsUp = isSunUp(localTime, sunTimes)
                if (sunIsUp) {
                    LampColorInfo(
                        hue = null,
                        saturation = null,
                        colorTemperature = null,
                        brightness = 0,
                        description = "Off (sun is up)"
                    )
                } else {
                    LampColorInfo(
                        hue = daylightColor.hue,
                        saturation = daylightColor.saturation,
                        colorTemperature = daylightColor.colorTemperature,
                        brightness = daylightColor.brightness,
                        description = "Warm white"
                    )
                }
            }

            AutomationMode.EVENING -> LampColorInfo(
                hue = eveningColor.hue,
                saturation = eveningColor.saturation,
                colorTemperature = eveningColor.colorTemperature,
                brightness = eveningColor.brightness,
                description = "Bright orange"
            )

            AutomationMode.NIGHT -> LampColorInfo(
                hue = nightColor.hue,
                saturation = nightColor.saturation,
                colorTemperature = nightColor.colorTemperature,
                brightness = nightColor.brightness,
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
     * Check if the sun is currently up (between sunrise and sunset).
     */
    private fun isSunUp(
        currentTime: LocalTime,
        sunTimes: SunCalculator.SunTimes?
    ): Boolean {
        val resolvedSunTimes = sunTimes ?: return false
        val currentMinutes = minutesSinceMidnight(currentTime)
        val sunriseMinutes = minutesSinceMidnight(resolvedSunTimes.sunrise)
        val sunsetMinutes = minutesSinceMidnight(resolvedSunTimes.sunset)

        return currentMinutes in sunriseMinutes..sunsetMinutes
    }

    fun isEntertainmentActive(): Boolean {
        return getActiveEntertainmentLamps().isNotEmpty()
    }

    private fun getActiveEntertainmentLamps(): Set<String> {
        val entertainmentGroups = lampStateCache.getEntertainmentGroups()
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
        lampStateCache.forceRefresh()
        startHeartbeat()

        return userState
    }

    suspend fun goToSleep(): UserState {
        userState = UserState.ASLEEP
        wakeUpTime = null
        logger.info("User going to sleep")

        stopHeartbeat()

        // Clear all manual overrides - user explicitly wants lamps off
        if (lampOverrides.isNotEmpty()) {
            logger.info("Clearing ${lampOverrides.size} manual override(s)")
            lampOverrides.clear()
            incrementSyncVersion()
        }

        // Turn off all automated lamps
        for (lampId in automatedLampIds) {
            hueService.setLightState(lampId, HueLightStateUpdate(on = false))
        }

        lampStateCache.forceRefresh()
        return userState
    }

    fun addLampOverride(lampId: String) {
        val overrideUntil = Clock.System.now() + overrideDuration
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
        val expired = pendingOperations.filter {
            (now - it.value.startedAt) > pendingOperationTimeout
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
        incrementSyncVersion()
        logger.info("Cleared override for lamp $lampId")

        // Immediately apply automation state to this lamp
        if (lampId in automatedLampIds) {
            val entertainmentLamps = getActiveEntertainmentLamps()
            if (!entertainmentLamps.contains(lampId)) {
                if (userState == UserState.AWAKE) {
                    // User is awake: apply calculated automation state
                    val now = Clock.System.now()
                    val localTime = now.toLocalDateTime(timeZone).time
                    val sunTimes = SunCalculator.calculateSunTimes(now, config.region, timeZone)
                    val desiredState = calculateDesiredState(localTime, sunTimes)
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
        lampReachability.keys.retainAll(automatedLampIds)
        lampPowerStates.keys.retainAll(automatedLampIds)
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

    fun setNightTime(time: LocalTime) {
        nightTime = time
        logger.info("Set night time to $time")
    }

    fun setNightTime(timeStr: String) {
        nightTime = parsePseudoSunset(timeStr)
        logger.info("Set night time to $nightTime")
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(10.minutes)
                cleanExpiredOverrides()
                discoverNewLamps()
                if (userState == UserState.AWAKE) {
                    applyAutomatedState()
                }
            }
        }
        logger.info("Started heartbeat")
    }

    /**
     * Discover new lamps that were added to the Hue bridge while the server was running.
     * New lamps are automatically added to automation.
     *
     * @param knownLampIds optional set of lamp IDs already fetched (to avoid extra API call)
     * @return set of newly discovered lamp IDs
     */
    fun discoverNewLamps(knownLampIds: Set<String>? = null): Set<String> {
        val currentLamps = knownLampIds ?: lampStateCache.getLights().keys
        val newLamps = currentLamps - automatedLampIds

        if (newLamps.isNotEmpty()) {
            automatedLampIds.addAll(newLamps)
            logger.info("Discovered ${newLamps.size} new lamp(s): $newLamps")
        }

        return newLamps
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
            incrementSyncVersion()
            logger.info("Cleaned ${expired.size} expired overrides")
        }
    }

    private suspend fun applyAutomatedState() {
        if (userState != UserState.AWAKE) return

        val now = Clock.System.now()
        val localTime = now.toLocalDateTime(timeZone).time
        val sunTimes = SunCalculator.calculateSunTimes(now, config.region, timeZone)
        val desiredState = calculateDesiredState(localTime, sunTimes)
        val entertainmentLamps = getActiveEntertainmentLamps()

        for (lampId in automatedLampIds) {
            if (entertainmentLamps.contains(lampId)) {
                continue // Skip lamps in active entertainment areas
            }

            // Check current lamp state for reachability tracking (from cache)
            val light = lampStateCache.getLight(lampId)
            val isReachable = light?.state?.reachable == true
            val becameReachable = updateLampReachability(lampId, isReachable, now)

            if (light != null && isReachable) {
                updateLampPowerState(lampId, light.state.on, now)
            }

            // If lamp just became reachable, apply automation immediately (ignore overrides)
            if (isReachable && becameReachable) {
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

    private fun modeColorToLightState(config: ModeColorConfig): HueLightStateUpdate {
        return HueLightStateUpdate(
            on = true,
            bri = config.brightness,
            hue = config.hue,
            sat = config.saturation,
            ct = config.colorTemperature
        )
    }

    private fun calculateDesiredState(
        currentTime: LocalTime,
        sunTimes: SunCalculator.SunTimes?
    ): HueLightStateUpdate {
        return when (calculateAutomationMode(currentTime, sunTimes)) {
            AutomationMode.AUTO_COMPENSATION -> {
                if (isSunUp(currentTime, sunTimes)) {
                    HueLightStateUpdate(on = false)
                } else {
                    modeColorToLightState(daylightColor)
                }
            }

            AutomationMode.EVENING -> modeColorToLightState(eveningColor)
            AutomationMode.NIGHT -> modeColorToLightState(nightColor)
            AutomationMode.USER_ASLEEP -> HueLightStateUpdate(on = false)
        }
    }

    private fun updateLampReachability(lampId: String, isReachable: Boolean, now: Instant): Boolean {
        val previous = lampReachability[lampId]
        val becameReachable = previous?.wasReachable == false && isReachable
        lampReachability[lampId] = LampReachabilityState(lampId, isReachable, now)
        return becameReachable
    }

    private fun updateLampPowerState(lampId: String, isOn: Boolean, now: Instant) {
        val previous = lampPowerStates[lampId]
        val lastOnAt = when {
            !isOn -> null
            previous == null -> now // Treat first observation as recent to avoid false overrides
            !previous.isOn && isOn -> now
            else -> previous.lastOnAt
        }
        lampPowerStates[lampId] = LampPowerState(lampId, isOn, lastOnAt)
    }

    private fun isRecentlyTurnedOn(lampId: String, now: Instant): Boolean {
        val state = lampPowerStates[lampId] ?: return false
        val lastOnAt = state.lastOnAt ?: return false
        return state.isOn && (now - lastOnAt) <= recentOnGracePeriod
    }

    private fun minutesSinceMidnight(time: LocalTime): Int {
        return time.hour * 60 + time.minute
    }

    private fun isWithinRange(current: Int, start: Int, end: Int): Boolean {
        return if (end < start) {
            current >= start || current < end
        } else {
            current in start until end
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

    private fun defaultNightTime(eveningTime: LocalTime): LocalTime {
        return LocalTime((eveningTime.hour + PSEUDO_SUNSET_WINDOW_HOURS) % 24, eveningTime.minute)
    }

    override fun close() = scope.cancel()
}
