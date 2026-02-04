package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.api.*
import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.automation.UserState
import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LinkResult
import io.github.commandertvis.huemanager.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.apiRoutes(
    config: Config,
    hueService: HueService,
    automationManager: AutomationManager
) {
    // OAuth2 for Philips Hue Remote API
    get("/api/hue/authorize") {
        val redirectUri = call.resolveHueRedirectUri(config)
        val state = UUID.randomUUID().toString()

        logger.info("Generating authorization URL for redirectUri: $redirectUri")
        val authUrl = hueService.getAuthorizationUrl(redirectUri, state)
        if (authUrl != null) {
            logger.info("Generated URL: $authUrl")
            call.respond(
                mapOf(
                    "authorizationUrl" to authUrl,
                    "state" to state
                )
            )
        } else {
            logger.warn("Failed to generate authorization URL - HueService returned null")
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("OAuth2 not configured. Set HUE_CLIENT_ID, HUE_CLIENT_SECRET, and HUE_APP_ID in .env", 503)
            )
        }
    }

    get("/api/hue/callback") {
        // OAuth2 callback from Philips Hue
        // Expected parameters: code, state, and optionally pkce
        val code = call.parameters["code"]
        val state = call.parameters["state"]
        val pkce = call.parameters["pkce"]
        val error = call.parameters["error"]
        val errorDescription = call.parameters["error_description"]

        logger.info("Received OAuth2 callback")
        logger.debug("Callback parameters: code=${code?.take(10)}..., state=${state?.take(8)}..., pkce=$pkce")

        if (error != null) {
            logger.error("OAuth2 authorization failed: $error - $errorDescription")
            call.respondText(
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Hue Authorization Failed</title></head>
                    <body>
                        <h1>Authorization Failed</h1>
                        <p>Error: $error</p>
                        ${errorDescription?.let { "<p>Description: $it</p>" } ?: ""}
                        <p><a href="/api/hue/authorize">Try Again</a></p>
                    </body>
                    </html>
                """.trimIndent(), ContentType.Text.Html, status = HttpStatusCode.BadRequest
            )
            return@get
        }

        if (code == null) {
            logger.error("OAuth2 callback missing authorization code")
            call.respondText(
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Hue Authorization Failed</title></head>
                    <body>
                        <h1>Authorization Failed</h1>
                        <p>Missing authorization code in callback</p>
                        <p><a href="/api/hue/authorize">Try Again</a></p>
                    </body>
                    </html>
                """.trimIndent(), ContentType.Text.Html, status = HttpStatusCode.BadRequest
            )
            return@get
        }

        // PKCE is optional - log it if present
        if (pkce != null) {
            logger.debug("PKCE extension used: $pkce")
        }

        val redirectUri = call.resolveHueRedirectUri(config)
        logger.debug("Using redirect_uri for token exchange: $redirectUri")

        val success = hueService.handleOAuthCallback(code, redirectUri)

        if (success) {
            logger.info("Successfully exchanged authorization code for tokens")
            // After getting tokens, we need to link to the bridge
            call.respondText(
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Hue Authorization</title></head>
                    <body>
                        <h1>Authorization Successful!</h1>
                        <p>Now press the link button on your Hue Bridge, then click the button below.</p>
                        <button onclick="linkBridge()">Complete Setup</button>
                        <p id="status"></p>
                        <script>
                            async function linkBridge() {
                                document.getElementById('status').textContent = 'Linking...';
                                const response = await fetch('/api/hue/link', { method: 'POST' });
                                const result = await response.json();
                                if (result.success) {
                                    document.getElementById('status').textContent = 'Success! You can close this window.';
                                } else {
                                    document.getElementById('status').textContent = 'Error: ' + result.message;
                                }
                            }
                        </script>
                    </body>
                    </html>
                """.trimIndent(), ContentType.Text.Html
            )
        } else {
            logger.error("Failed to exchange authorization code for tokens")
            call.respondText(
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Hue Authorization Failed</title></head>
                    <body>
                        <h1>Token Exchange Failed</h1>
                        <p>Failed to exchange authorization code for access tokens.</p>
                        <p>Check server logs for details.</p>
                        <p><a href="/api/hue/authorize">Try Again</a></p>
                    </body>
                    </html>
                """.trimIndent(), ContentType.Text.Html, status = HttpStatusCode.InternalServerError
            )
        }
    }

    post("/api/hue/link") {
        when (val result = hueService.linkRemoteBridge()) {
            is LinkResult.Success -> {
                val lamps = hueService.getLights()
                automationManager.setAutomatedLamps(lamps.keys)
                call.respond(GenericResponse(success = true, message = "Linked! Found ${lamps.size} lamps"))
            }

            is LinkResult.Error -> {
                call.respond(GenericResponse(success = false, message = result.message))
            }

            LinkResult.LinkButtonNotPressed -> {
                call.respond(
                    GenericResponse(
                        success = false,
                        message = "Press the link button on your Hue Bridge first"
                    )
                )
            }
        }
    }

    // Lightweight sync endpoint for frequent polling (no Hue API calls)
    get("/api/sync") {
        val state = automationManager.getUserState()
        val modelState = when (state) {
            UserState.AWAKE -> io.github.commandertvis.huemanager.models.UserState.AWAKE
            UserState.ASLEEP -> io.github.commandertvis.huemanager.models.UserState.ASLEEP
        }
        val mode = automationManager.getCurrentAutomationMode()
        val color = automationManager.getAutomationColor()
        val overriddenLamps = automationManager.getOverriddenLampIds()

        call.respond(
            SyncResponse(
                version = automationManager.getSyncVersion(),
                userState = modelState,
                automationMode = mode.name,
                automationColor = AutomationColorInfo(
                    hue = color.hue,
                    saturation = color.saturation,
                    colorTemperature = color.colorTemperature,
                    brightness = color.brightness,
                    description = color.description
                ),
                overriddenLamps = overriddenLamps,
                pendingLampIds = automationManager.getPendingLampIds(),
                entertainmentActive = automationManager.isEntertainmentActive()
            )
        )
    }

    // Mark lamps as pending (called before starting an operation)
    post("/api/sync/pending") {
        val request = call.receive<PendingOperationRequest>()
        automationManager.addPendingOperations(request.lampIds)
        call.respond(ApiSuccess("Pending operations added"))
    }

    // Clear pending lamps (called after operation completes)
    delete("/api/sync/pending") {
        val request = call.receive<PendingOperationRequest>()
        automationManager.clearPendingOperations(request.lampIds)
        call.respond(ApiSuccess("Pending operations cleared"))
    }

    // Status
    get("/api/status") {
        val userState = when (automationManager.getUserState()) {
            UserState.AWAKE -> io.github.commandertvis.huemanager.models.UserState.AWAKE
            UserState.ASLEEP -> io.github.commandertvis.huemanager.models.UserState.ASLEEP
        }
        call.respond(
            StatusResponse(
                connected = hueService.isConnected,
                bridgeIp = null, // Remote API doesn't expose bridge IP
                needsLinking = hueService.needsLinking,
                needsReauthorization = hueService.needsReauthorization,
                automationState = userState,
                entertainmentActive = automationManager.isEntertainmentActive()
            ).also {
                // For debugging OAuth URL issues
                logger.debug("StatusResponse: connected=${it.connected}, needsLinking=${it.needsLinking}, needsReauthorization=${it.needsReauthorization}")
            }
        )
    }

    // Lamps
    get("/api/lamps") {
        val lights = hueService.getLights()

        // Discover new lamps added to the bridge since server started
        automationManager.discoverNewLamps(lights.keys)

        val entertainmentGroups = hueService.getEntertainmentGroups()
        val entertainmentLamps = mutableSetOf<String>()

        for ((_, group) in entertainmentGroups) {
            if (group.stream?.active == true) {
                entertainmentLamps.addAll(group.lights)
            }
        }

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
                type = LampType.fromHueType(light.type),
                inEntertainment = id in entertainmentLamps
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
            val entertainmentGroups = hueService.getEntertainmentGroups()
            val entertainmentLamps = mutableSetOf<String>()

            for ((_, group) in entertainmentGroups) {
                if (group.stream?.active == true) {
                    entertainmentLamps.addAll(group.lights)
                }
            }

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
                    type = LampType.fromHueType(light.type),
                    inEntertainment = id in entertainmentLamps
                )
            )
        }
    }

    put("/api/lamps/{id}") {
        if (!call.requirePassword(config)) return@put

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
        if (!call.requirePassword(config)) return@put

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

    // Groups
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

    // Automation
    post("/api/wakeup") {
        if (!call.requirePassword(config)) return@post

        val state = automationManager.wakeUp()
        val modelState = when (state) {
            UserState.AWAKE -> io.github.commandertvis.huemanager.models.UserState.AWAKE
            UserState.ASLEEP -> io.github.commandertvis.huemanager.models.UserState.ASLEEP
        }
        call.respond(WakeUpResponse(success = true, state = modelState))
    }

    post("/api/sleep") {
        if (!call.requirePassword(config)) return@post

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
        val mode = automationManager.getCurrentAutomationMode()
        val color = automationManager.getAutomationColor()
        val overriddenLamps = automationManager.getOverriddenLampIds()

        call.respond(
            AutomationStatusResponse(
                userState = modelState,
                wakeUpTime = automationManager.getWakeUpTime()?.toString(),
                pseudoSunset = automationManager.getPseudoSunset().toString(),
                entertainmentActive = automationManager.isEntertainmentActive(),
                overriddenLamps = overriddenLamps,
                automationMode = mode.name,
                automationColor = AutomationColorInfo(
                    hue = color.hue,
                    saturation = color.saturation,
                    colorTemperature = color.colorTemperature,
                    brightness = color.brightness,
                    description = color.description
                )
            )
        )
    }

    // Settings
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
        if (!call.requirePassword(config)) return@put

        val request = call.receive<SettingsUpdateRequest>()

        request.pseudoSunset?.let { automationManager.setPseudoSunset(it) }
        request.automatedLampIds?.let { automationManager.setAutomatedLamps(it.toSet()) }

        call.respond(ApiSuccess("Settings updated"))
    }

    // Override management
    delete("/api/lamps/{id}/override") {
        if (!call.requirePassword(config)) return@delete

        val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        automationManager.clearLampOverride(id)
        call.respond(ApiSuccess("Override cleared"))
    }
}

private fun ApplicationCall.resolveHueRedirectUri(config: Config): String {
    val configured = config.hueRedirectUri?.takeIf { it.isNotBlank() }
    if (configured != null) {
        return configured
    }

    val forwardedProto = request.headers["X-Forwarded-Proto"]
    val forwardedHost = request.headers["X-Forwarded-Host"]
    val forwardedPort = request.headers["X-Forwarded-Port"]

    val scheme = forwardedProto ?: if (request.port() == 443) "https" else "http"
    val host = forwardedHost ?: request.host()
    val port = forwardedPort ?: when {
        forwardedProto != null && scheme == "https" -> "443"
        forwardedProto != null && scheme == "http" -> "80"
        else -> request.port().toString()
    }

    val hostWithPort = if (":" in host) {
        host
    } else {
        val isDefaultPort = (scheme == "http" && port == "80") || (scheme == "https" && port == "443")
        if (isDefaultPort) host else "$host:$port"
    }

    return "$scheme://$hostWithPort/api/hue/callback"
}
