package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.ConfigLoader
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LampStateCache
import io.github.commandertvis.huemanager.persistence.SettingsStore
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.io.path.Path

fun main() {
    val config = try {
        ConfigLoader.load()
    } catch (e: Exception) {
        logger.error("Failed to load configuration: ${e.message}")
        logger.error("Please copy .env.example to .env and configure your settings")
        return
    }

    SettingsStore(Path(config.databasePath)).use { settingsStore ->
        HueService(config).use { hueService ->
            LampStateCache(hueService).use { lampStateCache ->
                hueService.setCache(lampStateCache)
                AutomationManager(config, hueService, lampStateCache, settingsStore).use { automationManager ->
                    lampStateCache.setSensorRefreshListener { sensors ->
                        automationManager.onSensorsRefreshed(sensors)
                    }
                    runBlocking {
                        val initialized = hueService.initialize()
                        if (initialized) {
                            lampStateCache.initialize()
                            lampStateCache.startRefreshing()
                            automationManager.resumeFromPersistedState()
                            logger.info("Connected to Philips Hue with ${lampStateCache.getLights().size} lamps")
                        } else {
                            logger.info("Philips Hue not configured. Use the web UI or API to configure connection.")
                        }
                    }

                    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0") {
                        module(config, hueService, automationManager, lampStateCache)
                    }.start(wait = true)
                }
            }
        }
    }
}

fun Application.module(
    config: Config,
    hueService: HueService,
    automationManager: AutomationManager,
    lampStateCache: LampStateCache
) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        anyHost()
    }

    install(SSE)

    routing {
        // MCP routes must be before webRoutes (SPA) to avoid being caught by SPA
        mcpRoutes(config, hueService, automationManager, lampStateCache)
        authRoutes(config)
        apiRoutes(config, hueService, automationManager, lampStateCache)
        // SPA catch-all must be last
        webRoutes()
    }
}
