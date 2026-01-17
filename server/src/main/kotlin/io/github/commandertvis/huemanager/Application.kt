package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.api.*
import io.github.commandertvis.huemanager.auth.SessionManager
import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.automation.UserState
import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.ConfigLoader
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LinkResult
import io.github.commandertvis.huemanager.models.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("Application")

fun main() {
    val config = try {
        ConfigLoader.load()
    } catch (e: Exception) {
        logger.error("Failed to load configuration: ${e.message}")
        logger.error("Please copy .env.example to .env and configure your settings")
        return
    }

    val hueService = HueService(config)
    val sessionManager = SessionManager(config)
    val automationManager = AutomationManager(config, hueService)

    runBlocking {
        val initialized = hueService.initialize()
        if (initialized) {
            // Set all discovered lamps as automated by default
            val lamps = hueService.getLights()
            automationManager.setAutomatedLamps(lamps.keys)
            logger.info("Connected to Hue bridge with ${lamps.size} lamps")
        } else {
            logger.info("Hue bridge not configured. Use the web UI or API to configure bridge connection.")
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        automationManager.shutdown()
        hueService.close()
    })

    // TODO: Implement HTTPS support using keystore (requires Ktor 3.x API adjustment for applicationEngineEnvironment)
    // val keystoreFile = File("keystore.jks")
    // if (keystoreFile.exists() && config.keystorePassword != null) { ... }

    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0") {
        module(config, hueService, sessionManager, automationManager)
    }.start(wait = true)
}

fun Application.module(
    config: Config,
    hueService: HueService,
    sessionManager: SessionManager,
    automationManager: AutomationManager
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

    routing {
        // --- Static Web UI ---
        val webDir = File("web")
        if (webDir.exists() && webDir.isDirectory) {
            singlePageApplication {
                filesPath = "web"
                defaultPage = "index.html"
            }
        } else {
            get("/") {
                call.respondText("Hue Manager Server v1.0.0 - Web UI not available")
            }
        }

        // --- Authentication ---
        post("/api/session") {
            val request = call.receive<LoginRequest>()
            val session = sessionManager.authenticate(request.password)
            if (session != null) {
                call.respond(LoginResponse.success(session.token))
            } else {
                call.respond(HttpStatusCode.Unauthorized, LoginResponse.failure("Invalid password"))
            }
        }

        // --- Bridge Configuration (from client) ---
        post("/api/bridge/configure") {
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!sessionManager.validateSession(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid session", 401))
                return@post
            }

            val request = call.receive<BridgeConfigRequest>()

            val username = request.username
            if (username != null) {
                // Client provided username, try to connect directly
                val success = hueService.configureBridge(request.bridgeIp, username)
                if (success) {
                    // Initialize automation with discovered lamps
                    val lamps = hueService.getLights()
                    automationManager.setAutomatedLamps(lamps.keys)

                    call.respond(
                        BridgeConfigResponse(
                            success = true,
                            connected = true,
                            bridgeIp = request.bridgeIp,
                            needsLinking = false,
                            message = "Connected to bridge with ${lamps.size} lamps"
                        )
                    )
                } else {
                    call.respond(
                        BridgeConfigResponse(
                            success = false,
                            connected = false,
                            bridgeIp = request.bridgeIp,
                            needsLinking = true,
                            message = "Failed to connect - invalid credentials or bridge unreachable"
                        )
                    )
                }
            } else {
                // No username, need to link - start linking process
                call.respond(
                    BridgeConfigResponse(
                        success = true,
                        connected = false,
                        bridgeIp = request.bridgeIp,
                        needsLinking = true,
                        message = "Bridge IP set. Press link button on bridge and call /api/bridge/link"
                    )
                )
            }
        }

        post("/api/bridge/link") {
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!sessionManager.validateSession(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid session", 401))
                return@post
            }

            val request = call.receive<BridgeConfigRequest>()

            when (val result = hueService.tryLinkOnce(request.bridgeIp)) {
                is LinkResult.Success -> {
                    val lamps = hueService.getLights()
                    automationManager.setAutomatedLamps(lamps.keys)

                    call.respond(
                        BridgeConfigResponse(
                            success = true,
                            connected = true,
                            bridgeIp = request.bridgeIp,
                            needsLinking = false,
                            message = "Successfully linked! Found ${lamps.size} lamps"
                        )
                    )
                }
                is LinkResult.Error -> {
                    call.respond(
                        BridgeConfigResponse(
                            success = false,
                            connected = false,
                            bridgeIp = request.bridgeIp,
                            needsLinking = true,
                            message = result.message
                        )
                    )
                }
                LinkResult.LinkButtonNotPressed -> {
                    call.respond(
                        BridgeConfigResponse(
                            success = false,
                            connected = false,
                            bridgeIp = request.bridgeIp,
                            needsLinking = true,
                            message = "Link button was not pressed"
                        )
                    )
                }
            }
        }

        // --- Status ---
        get("/api/status") {
            val userState = when (automationManager.getUserState()) {
                UserState.AWAKE -> io.github.commandertvis.huemanager.models.UserState.AWAKE
                UserState.ASLEEP -> io.github.commandertvis.huemanager.models.UserState.ASLEEP
            }
            call.respond(
                StatusResponse(
                    connected = hueService.isConnected,
                    bridgeIp = hueService.getBridgeIp(),
                    needsLinking = hueService.needsLinking,
                    automationState = userState,
                    entertainmentActive = automationManager.isEntertainmentActive()
                )
            )
        }

        // --- Lamps ---
        get("/api/lamps") {
            val lights = hueService.getLights()
            val lamps = lights.map { (id, light) ->
                Lamp(
                    id = id,
                    name = light.name,
                    on = light.state.on,
                    brightness = light.state.bri,
                    hue = light.state.hue,
                    saturation = light.state.sat,
                    colorTemperature = light.state.ct,
                    colorMode = ColorMode.fromString(light.state.colormode),
                    reachable = light.state.reachable ?: false,
                    type = LampType.fromHueType(light.type)
                )
            }
            call.respond(LampsResponse(lamps))
        }

        get("/api/lamps/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val light = hueService.getLight(id)
            if (light == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("Lamp not found", 404))
            } else {
                call.respond(
                    Lamp(
                        id = id,
                        name = light.name,
                        on = light.state.on,
                        brightness = light.state.bri,
                        hue = light.state.hue,
                        saturation = light.state.sat,
                        colorTemperature = light.state.ct,
                        colorMode = ColorMode.fromString(light.state.colormode),
                        reachable = light.state.reachable ?: false,
                        type = LampType.fromHueType(light.type)
                    )
                )
            }
        }

        put("/api/lamps/{id}") {
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!sessionManager.validateSession(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid session", 401))
                return@put
            }

            val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<LampUpdateRequest>()

            // Add override for this lamp (manual control for 1 hour)
            automationManager.addLampOverride(id)

            val state = HueLightStateUpdate(
                on = request.on,
                bri = request.brightness,
                hue = request.hue,
                sat = request.saturation,
                ct = request.colorTemperature,
                transitiontime = request.transitionTime
            )

            val success = hueService.setLightState(id, state)
            if (success) {
                call.respond(ApiSuccess("Lamp updated"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Failed to update lamp", 500))
            }
        }

        put("/api/lamps/all") {
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!sessionManager.validateSession(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid session", 401))
                return@put
            }

            val request = call.receive<AllLampsUpdateRequest>()

            val state = HueLightStateUpdate(
                on = request.on,
                bri = request.brightness
            )

            val success = hueService.setAllLightsState(state)
            if (success) {
                call.respond(ApiSuccess("All lamps updated"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, ApiError("Failed to update lamps", 500))
            }
        }

        // --- Groups ---
        get("/api/groups") {
            val groups = hueService.getGroups()
            val response = GroupsResponse(
                groups = groups.map { (id, group) ->
                    Group(
                        id = id,
                        name = group.name,
                        type = GroupType.fromString(group.type),
                        lampIds = group.lights,
                        allOn = group.state?.all_on ?: false,
                        anyOn = group.state?.any_on ?: false
                    )
                }
            )
            call.respond(response)
        }

        // --- Automation ---
        post("/api/wakeup") {
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!sessionManager.validateSession(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid session", 401))
                return@post
            }

            val state = automationManager.wakeUp()
            val modelState = when (state) {
                UserState.AWAKE -> io.github.commandertvis.huemanager.models.UserState.AWAKE
                UserState.ASLEEP -> io.github.commandertvis.huemanager.models.UserState.ASLEEP
            }
            call.respond(WakeUpResponse(success = true, state = modelState))
        }

        post("/api/sleep") {
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!sessionManager.validateSession(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid session", 401))
                return@post
            }

            val state = automationManager.goToSleep()
            val modelState = when (state) {
                UserState.AWAKE -> io.github.commandertvis.huemanager.models.UserState.AWAKE
                UserState.ASLEEP -> io.github.commandertvis.huemanager.models.UserState.ASLEEP
            }
            call.respond(SleepResponse(success = true, state = modelState))
        }

        get("/api/automation") {
            val state = automationManager.getUserState()
            val modelState = when (state) {
                UserState.AWAKE -> io.github.commandertvis.huemanager.models.UserState.AWAKE
                UserState.ASLEEP -> io.github.commandertvis.huemanager.models.UserState.ASLEEP
            }
            call.respond(
                AutomationStatusResponse(
                    userState = modelState,
                    wakeUpTime = automationManager.getWakeUpTime()?.toString(),
                    pseudoSunset = automationManager.getPseudoSunset().toString(),
                    entertainmentActive = automationManager.isEntertainmentActive(),
                    overriddenLamps = automationManager.getOverriddenLampIds()
                )
            )
        }

        // --- Settings ---
        get("/api/settings") {
            val location = automationManager.getLocation()
            call.respond(
                SettingsResponse(
                    pseudoSunset = automationManager.getPseudoSunset().toString(),
                    latitude = location.latitude,
                    longitude = location.longitude,
                    automatedLampIds = automationManager.getAutomatedLampIds().toList()
                )
            )
        }

        put("/api/settings") {
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!sessionManager.validateSession(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid session", 401))
                return@put
            }

            val request = call.receive<SettingsUpdateRequest>()

            request.pseudoSunset?.let { automationManager.setPseudoSunset(it) }
            request.automatedLampIds?.let { automationManager.setAutomatedLamps(it.toSet()) }

            call.respond(ApiSuccess("Settings updated"))
        }

        // --- Override management ---
        delete("/api/lamps/{id}/override") {
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!sessionManager.validateSession(token)) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Invalid session", 401))
                return@delete
            }

            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            automationManager.clearLampOverride(id)
            call.respond(ApiSuccess("Override cleared"))
        }
    }
}