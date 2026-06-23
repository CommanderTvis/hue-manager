package io.github.commandertvis.huemanager.hue

import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.coroutines.*
import org.jboss.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * In-memory cache of lamp and group state from the Philips Hue Cloud API.
 *
 * All read operations return instantly from cached data (zero API calls).
 * A background job refreshes the cache periodically by calling the bulk
 * getLights() and getGroups() endpoints (2 API calls total per refresh).
 *
 * Write operations optimistically update the cache so that subsequent reads
 * immediately reflect the change without waiting for the next refresh cycle.
 *
 * The 5s background refresh is kept on a coroutine loop (started explicitly at app startup),
 * preserving the original behavior rather than switching to `@Scheduled`.
 */
@ApplicationScoped
class LampStateCache @Inject constructor(
    private val hueService: HueService,
) : AutoCloseable {
    private val refreshInterval: Duration = 5.seconds
    private val logger = Logger.getLogger(LampStateCache::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var cachedLights: Map<String, HueLight> = emptyMap()

    @Volatile
    private var cachedGroups: Map<String, HueGroup> = emptyMap()

    @Volatile
    private var cachedSensors: Map<String, HueSensor> = emptyMap()

    @Volatile
    private var refreshListener: (suspend (Map<String, HueSensor>) -> Unit)? = null

    private var refreshJob: Job? = null

    // --- Read accessors (instant, zero API calls) ---

    fun getLights(): Map<String, HueLight> = cachedLights

    fun getLight(id: String): HueLight? = cachedLights[id]

    fun getGroups(): Map<String, HueGroup> = cachedGroups

    fun getEntertainmentGroups(): Map<String, HueGroup> =
        cachedGroups.filter { it.value.type == "Entertainment" }

    fun getSensors(): Map<String, HueSensor> = cachedSensors

    fun getSensor(id: String): HueSensor? = cachedSensors[id]

    /**
     * Register a callback invoked after every successful refresh, with the freshly fetched sensors.
     * Used by AutomationManager to react to Smart Button presses.
     */
    fun setSensorRefreshListener(listener: suspend (Map<String, HueSensor>) -> Unit) {
        refreshListener = listener
    }

    // --- Lifecycle ---

    /**
     * Populate the cache with an initial bulk fetch from Philips Cloud.
     * Call once during server startup, before accepting requests.
     */
    suspend fun initialize() {
        refresh()
        logger.info("Cache initialized with ${cachedLights.size} lights and ${cachedGroups.size} groups")
    }

    /**
     * Start the background refresh loop.
     */
    fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(refreshInterval)
                refresh()
            }
        }
        logger.info("Background cache refresh started (interval: $refreshInterval)")
    }

    /**
     * Force an immediate refresh from Philips Cloud.
     * Use after operations that fundamentally change lamp state (wakeUp, goToSleep, link).
     */
    suspend fun forceRefresh() {
        refresh()
    }

    // --- Optimistic updates ---

    /**
     * Optimistically patch the cached state for a single lamp after a successful write.
     * The next background refresh will reconcile with the actual bridge state.
     */
    fun updateLightState(id: String, update: HueLightStateUpdate) {
        val current = cachedLights[id] ?: return
        val patched = current.copy(
            state = current.state.copy(
                on = update.on ?: current.state.on,
                bri = update.bri ?: current.state.bri,
                hue = update.hue ?: current.state.hue,
                sat = update.sat ?: current.state.sat,
                ct = update.ct ?: current.state.ct,
                xy = update.xy ?: current.state.xy,
                colormode = inferColorMode(update) ?: current.state.colormode
            )
        )
        cachedLights = cachedLights + (id to patched)
    }

    @PreDestroy
    override fun close() {
        refreshJob?.cancel()
        scope.cancel()
    }

    // --- Internal ---

    private suspend fun refresh() {
        try {
            val lights = hueService.getLights()
            val groups = hueService.getGroups()
            val sensors = hueService.getSensors()
            if (lights.isNotEmpty() || cachedLights.isNotEmpty()) {
                cachedLights = lights
            }
            cachedGroups = groups
            cachedSensors = sensors
            refreshListener?.invoke(sensors)
        } catch (e: Exception) {
            logger.warn("Cache refresh failed: ${e.message}")
        }
    }

    /**
     * Infer the active color mode from the fields present in an update.
     */
    private fun inferColorMode(update: HueLightStateUpdate): String? = when {
        update.hue != null || update.sat != null -> "hs"
        update.ct != null -> "ct"
        update.xy != null -> "xy"
        else -> null
    }
}
