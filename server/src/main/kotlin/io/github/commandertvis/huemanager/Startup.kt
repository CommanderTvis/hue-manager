package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LampStateCache
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.jboss.logging.Logger

/**
 * Boot-time orchestration, replacing the manual wiring in the old Ktor `main()`.
 *
 * Quarkus self-boots and CDI handles construction/injection; this observer only drives the
 * runtime initialization sequence that the beans cannot do during construction (they need each
 * other fully wired first): connect the Hue client, populate the cache, start the 5s refresh
 * loop, wire the sensor-refresh listener, and resume any persisted AWAKE automation state.
 */
@ApplicationScoped
class Startup @Inject constructor(
    private val hueService: HueService,
    private val lampStateCache: LampStateCache,
    private val automationManager: AutomationManager,
) {
    private val logger = Logger.getLogger(Startup::class.java)

    fun onStart(@Observes event: StartupEvent) {
        lampStateCache.setSensorRefreshListener { sensors ->
            automationManager.onSensorsRefreshed(sensors)
        }

        runBlocking {
            if (hueService.initialize()) {
                lampStateCache.initialize()
                lampStateCache.startRefreshing()
                automationManager.resumeFromPersistedState()
                logger.info("Connected to Philips Hue with ${lampStateCache.getLights().size} lamps")
            } else {
                logger.info("Philips Hue not configured. Use the web UI or API to configure connection.")
            }
        }
    }
}
