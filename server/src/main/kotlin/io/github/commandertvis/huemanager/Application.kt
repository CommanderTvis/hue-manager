package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.api.*
import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.automation.UserState
import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.ConfigLoader
import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LinkResult
import io.github.commandertvis.huemanager.mcp.McpHandler
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
import io.ktor.server.sse.*
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.isDirectory

private val logger = LoggerFactory.getLogger("Application")

fun main() {
    val config = try {
        ConfigLoader.load()
    } catch (e: Exception) {
        logger.error("Failed to load configuration: ${e.message}")
        logger.error("Please copy .env.example to .env and configure your settings")
        return
    }

    HueService(config).use { hueService ->
        AutomationManager(config, hueService).use { automationManager ->
            runBlocking {
                val initialized = hueService.initialize()
                if (initialized) {
                    // Set all discovered lamps as automated by default
                    val lamps = hueService.getLights()
                    automationManager.setAutomatedLamps(lamps.keys)
                    logger.info("Connected to Philips Hue with ${lamps.size} lamps")
                } else {
                    logger.info("Philips Hue not configured. Use the web UI or API to configure connection.")
                }
            }

            embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0") {
                module(config, hueService, automationManager)
            }.start(wait = true)
        }
    }
}

fun Application.module(
    config: Config,
    hueService: HueService,
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

    install(SSE)

    routing {
        // --- Static Web UI ---
        val webDir = Path("web")
        if (webDir.isDirectory()) {
            singlePageApplication {
                filesPath = "web"
            }
        } else {
            get("/") {
                call.respondText("Hue Manager Server v1.0.0 - Web UI not available")
            }
        }

        // --- Authentication ---
        post("/api/auth") {
            val request = call.receive<AuthRequest>()
            if (request.password == config.password) {
                call.respond(AuthResponse.success())
            } else {
                call.respond(HttpStatusCode.Unauthorized, AuthResponse.failure("Invalid password"))
            }
        }

        // --- OAuth2 for Philips Hue Remote API ---
        get("/api/hue/authorize") {
            val redirectUri = call.resolveHueRedirectUri(config)
            val state = java.util.UUID.randomUUID().toString()

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
                """.trimIndent(), ContentType.Text.Html, status = HttpStatusCode.BadRequest)
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

        // --- Status ---
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
                    automationState = userState,
                    entertainmentActive = automationManager.isEntertainmentActive()
                ).also {
                    // For debugging OAuth URL issues
                    logger.debug("StatusResponse: connected=${it.connected}, needsLinking=${it.needsLinking}")
                }
            )
        }

        // --- Lamps ---
        get("/api/lamps") {
            val lights = hueService.getLights()
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
            if (!call.requirePassword(config)) return@put

            val request = call.receive<SettingsUpdateRequest>()

            request.pseudoSunset?.let { automationManager.setPseudoSunset(it) }
            request.automatedLampIds?.let { automationManager.setAutomatedLamps(it.toSet()) }

            call.respond(ApiSuccess("Settings updated"))
        }

        // --- Override management ---
        delete("/api/lamps/{id}/override") {
            if (!call.requirePassword(config)) return@delete

            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            automationManager.clearLampOverride(id)
            call.respond(ApiSuccess("Override cleared"))
        }

        // --- MCP (Model Context Protocol) ---
        val mcpHandler = McpHandler(hueService, automationManager)

        // MCP OAuth-style authorization (password-only)
        val mcpOauthCodes = ConcurrentHashMap<String, McpOauthCode>()

        get("/api/mcp/oauth") {
            val redirectUri = call.parameters["redirect_uri"]
            val responseType = call.parameters["response_type"]
            if (redirectUri.isNullOrBlank() && responseType.isNullOrBlank()) {
                call.respond(buildMcpOauthMetadata(call))
                return@get
            }
            if (redirectUri.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "redirect_uri query parameter is required")
                return@get
            }

            val state = call.parameters["state"]
            val clientId = call.parameters["client_id"]
            val codeChallenge = call.parameters["code_challenge"]
            val codeChallengeMethod = call.parameters["code_challenge_method"]
            val effectiveResponseType = responseType ?: "code"

            call.respondText(
                renderMcpOauthPage(
                    redirectUri = redirectUri,
                    state = state,
                    responseType = effectiveResponseType,
                    clientId = clientId,
                    codeChallenge = codeChallenge,
                    codeChallengeMethod = codeChallengeMethod,
                    errorMessage = null
                ),
                ContentType.Text.Html
            )
        }

        post("/api/mcp/oauth") {
            val params = call.receiveParameters()
            val redirectUri = params["redirect_uri"]
            if (redirectUri.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "redirect_uri form parameter is required")
                return@post
            }

            val responseType = params["response_type"] ?: "code"
            val state = params["state"]
            val clientId = params["client_id"]
            val codeChallenge = params["code_challenge"]
            val codeChallengeMethod = params["code_challenge_method"]
            val password = params["password"]
            if (password != config.password) {
                call.respondText(
                    renderMcpOauthPage(
                        redirectUri = redirectUri,
                        state = state,
                        responseType = responseType,
                        clientId = clientId,
                        codeChallenge = codeChallenge,
                        codeChallengeMethod = codeChallengeMethod,
                        errorMessage = "Invalid password"
                    ),
                    ContentType.Text.Html,
                    status = HttpStatusCode.Unauthorized
                )
                return@post
            }

            when (responseType) {
                "code" -> {
                    val code = createMcpOauthCode(mcpOauthCodes, redirectUri)
                    val redirectUrl = buildMcpOauthCodeRedirect(redirectUri, code, state)
                    call.respondRedirect(redirectUrl, permanent = false)
                }

                "token" -> {
                    val redirectUrl = buildMcpOauthTokenRedirect(redirectUri, password, state)
                    call.respondRedirect(redirectUrl, permanent = false)
                }

                else -> call.respond(HttpStatusCode.BadRequest, "Unsupported response_type: $responseType")
            }
        }

        get("/api/mcp/oauth/.well-known/oauth-authorization-server") {
            call.respond(buildMcpOauthMetadata(call))
        }

        get("/.well-known/oauth-authorization-server") {
            call.respond(buildMcpOauthMetadata(call))
        }

        post("/api/mcp/oauth/register") {
            val request = runCatching { call.receive<OAuthRegistrationRequest>() }.getOrNull()
            val response = OAuthRegistrationResponse(
                client_id = java.util.UUID.randomUUID().toString(),
                client_id_issued_at = System.currentTimeMillis() / 1000,
                token_endpoint_auth_method = "none",
                grant_types = listOf("authorization_code"),
                response_types = listOf("code"),
                redirect_uris = request?.redirect_uris
            )
            call.respond(response)
        }

        post("/api/mcp/oauth/token") {
            val params = call.receiveParameters()
            val grantType = params["grant_type"]
            if (grantType != "authorization_code") {
                call.respond(HttpStatusCode.BadRequest, "Unsupported grant_type: $grantType")
                return@post
            }

            val code = params["code"]
            if (code.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "code form parameter is required")
                return@post
            }

            val redirectUri = params["redirect_uri"]
            val now = System.currentTimeMillis()
            val codeEntry = mcpOauthCodes.remove(code)
            if (codeEntry == null || now > codeEntry.expiresAtMillis) {
                call.respond(HttpStatusCode.BadRequest, "Invalid or expired code")
                return@post
            }

            if (!redirectUri.isNullOrBlank() && redirectUri != codeEntry.redirectUri) {
                call.respond(HttpStatusCode.BadRequest, "redirect_uri does not match")
                return@post
            }

            call.respond(
                buildJsonObject {
                    put("access_token", config.password)
                    put("token_type", "Bearer")
                    put("expires_in", 0)
                }
            )
        }

        // MCP endpoint using SSE transport
        // SSE connection: GET /api/mcp (establishes connection, receives server messages)
        // Client messages: POST /api/mcp?sessionId=<id> (sends client messages)
        // Authentication: Bearer password
        val mcpSessions = ConcurrentHashMap<String, SseServerTransport>()
        val mcpEndpoint = "/api/mcp"

        sse(mcpEndpoint) {
            if (!call.requirePassword(config)) return@sse

            val transport = SseServerTransport(mcpEndpoint, this)
            mcpSessions[transport.sessionId] = transport

            val server = mcpHandler.createServer()
            server.onClose {
                mcpSessions.remove(transport.sessionId)
            }

            server.createSession(transport)
            awaitCancellation()
        }

        post(mcpEndpoint) {
            if (!call.requirePassword(config)) return@post

            val sessionId = call.request.queryParameters["sessionId"]
            if (sessionId == null) {
                call.respond(HttpStatusCode.BadRequest, "sessionId query parameter is not provided")
                return@post
            }

            val transport = mcpSessions[sessionId]
            if (transport == null) {
                call.respond(HttpStatusCode.NotFound, "Session not found")
                return@post
            }

            transport.handlePostMessage(call)
        }
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
    val port = forwardedPort ?: request.port().toString()

    val hostWithPort = if (":" in host) {
        host
    } else {
        val isDefaultPort = (scheme == "http" && port == "80") || (scheme == "https" && port == "443")
        if (isDefaultPort) host else "$host:$port"
    }

    return "$scheme://$hostWithPort/api/hue/callback"
}

private fun ApplicationCall.resolveBaseUrl(): String {
    val forwardedProto = request.headers["X-Forwarded-Proto"]
    val forwardedHost = request.headers["X-Forwarded-Host"]
    val forwardedPort = request.headers["X-Forwarded-Port"]

    val scheme = forwardedProto ?: if (request.port() == 443) "https" else "http"
    val host = forwardedHost ?: request.host()
    val port = forwardedPort ?: request.port().toString()

    val hostWithPort = if (":" in host) {
        host
    } else {
        val isDefaultPort = (scheme == "http" && port == "80") || (scheme == "https" && port == "443")
        if (isDefaultPort) host else "$host:$port"
    }

    return "$scheme://$hostWithPort/"
}

private suspend fun ApplicationCall.requirePassword(config: Config): Boolean {
    val token = request.header(HttpHeaders.Authorization)
        ?.removePrefix("Bearer ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    if (token == null || token != config.password) {
        respond(HttpStatusCode.Unauthorized, ApiError("Invalid password", 401))
        return false
    }

    return true
}

private const val MCP_OAUTH_CODE_TTL_MILLIS = 5 * 60 * 1000L

private data class McpOauthCode(
    val redirectUri: String,
    val expiresAtMillis: Long
)

@Serializable
private data class OAuthRegistrationRequest(
    val redirect_uris: List<String>? = null,
    val client_name: String? = null,
    val token_endpoint_auth_method: String? = null,
    val grant_types: List<String>? = null,
    val response_types: List<String>? = null
)

@Serializable
private data class OAuthRegistrationResponse(
    val client_id: String,
    val client_id_issued_at: Long,
    val token_endpoint_auth_method: String,
    val grant_types: List<String>,
    val response_types: List<String>,
    val redirect_uris: List<String>? = null
)

private fun createMcpOauthCode(
    codes: ConcurrentHashMap<String, McpOauthCode>,
    redirectUri: String
): String {
    val now = System.currentTimeMillis()
    codes.entries.removeIf { it.value.expiresAtMillis <= now }
    val code = java.util.UUID.randomUUID().toString()
    codes[code] = McpOauthCode(
        redirectUri = redirectUri,
        expiresAtMillis = now + MCP_OAUTH_CODE_TTL_MILLIS
    )
    return code
}

private fun buildMcpOauthCodeRedirect(
    redirectUri: String,
    code: String,
    state: String?
): String {
    val separator = if (redirectUri.contains("?")) "&" else "?"
    val params = mutableListOf("code=${code.encodeURLParameter()}")
    if (!state.isNullOrBlank()) {
        params.add("state=${state.encodeURLParameter()}")
    }
    return redirectUri + separator + params.joinToString("&")
}

private fun buildMcpOauthTokenRedirect(
    redirectUri: String,
    accessToken: String,
    state: String?
): String {
    val redirectParts = redirectUri.split("#", limit = 2)
    val baseUri = redirectParts[0]
    val existingFragment = redirectParts.getOrNull(1)
    val params = mutableListOf(
        "access_token=${accessToken.encodeURLParameter()}",
        "token_type=Bearer"
    )
    if (!state.isNullOrBlank()) {
        params.add("state=${state.encodeURLParameter()}")
    }
    val fragment = when {
        existingFragment.isNullOrBlank() -> params.joinToString("&")
        else -> existingFragment + "&" + params.joinToString("&")
    }
    return "$baseUri#$fragment"
}

private fun buildMcpOauthMetadata(call: ApplicationCall) = buildJsonObject {
    val baseUrl = call.resolveBaseUrl()
    val issuer = "${baseUrl}api/mcp/oauth"
    put("issuer", issuer)
    put("authorization_endpoint", issuer)
    put("token_endpoint", "${baseUrl}api/mcp/oauth/token")
    put("registration_endpoint", "${baseUrl}api/mcp/oauth/register")
    putJsonArray("response_types_supported") {
        add("code")
    }
    putJsonArray("grant_types_supported") {
        add("authorization_code")
    }
    putJsonArray("token_endpoint_auth_methods_supported") {
        add("none")
    }
    putJsonArray("code_challenge_methods_supported") {
        add("S256")
        add("plain")
    }
}

private fun renderMcpOauthPage(
    redirectUri: String,
    state: String?,
    responseType: String,
    clientId: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    errorMessage: String?
): String {
    val errorBlock = if (errorMessage != null) {
        """
        <div class="error">${errorMessage.escapeHtml()}</div>
        """.trimIndent()
    } else {
        ""
    }

    val stateInput = if (!state.isNullOrBlank()) {
        """<input type="hidden" name="state" value="${state.escapeHtml()}">"""
    } else {
        ""
    }
    val clientIdInput = if (!clientId.isNullOrBlank()) {
        """<input type="hidden" name="client_id" value="${clientId.escapeHtml()}">"""
    } else {
        ""
    }
    val codeChallengeInput = if (!codeChallenge.isNullOrBlank()) {
        """<input type="hidden" name="code_challenge" value="${codeChallenge.escapeHtml()}">"""
    } else {
        ""
    }
    val codeChallengeMethodInput = if (!codeChallengeMethod.isNullOrBlank()) {
        """<input type="hidden" name="code_challenge_method" value="${codeChallengeMethod.escapeHtml()}">"""
    } else {
        ""
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <title>MCP Authorization - Hue Manager</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                    max-width: 500px;
                    margin: 80px auto;
                    padding: 20px;
                    background: #f5f5f5;
                }
                .container {
                    background: white;
                    padding: 40px;
                    border-radius: 8px;
                    box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                }
                h1 {
                    margin: 0 0 10px 0;
                    font-size: 24px;
                    color: #333;
                }
                p {
                    color: #666;
                    margin: 0 0 30px 0;
                }
                input[type="password"] {
                    width: 100%;
                    padding: 12px;
                    border: 1px solid #ddd;
                    border-radius: 4px;
                    font-size: 16px;
                    box-sizing: border-box;
                    margin-bottom: 20px;
                }
                button {
                    width: 100%;
                    padding: 12px;
                    background: #007AFF;
                    color: white;
                    border: none;
                    border-radius: 4px;
                    font-size: 16px;
                    font-weight: 600;
                    cursor: pointer;
                }
                button:hover {
                    background: #0051D5;
                }
                .error {
                    margin-top: 16px;
                    padding: 12px;
                    border-radius: 4px;
                    background: #f8d7da;
                    color: #721c24;
                    border: 1px solid #f5c6cb;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>Authorize MCP Access</h1>
                <p>Enter your Hue Manager password to authorize this MCP client.</p>
                <form method="post" action="/api/mcp/oauth">
                    <input type="password" name="password" placeholder="Password" autocomplete="current-password" required>
                    <input type="hidden" name="redirect_uri" value="${redirectUri.escapeHtml()}">
                    <input type="hidden" name="response_type" value="${responseType.escapeHtml()}">
                    $stateInput
                    $clientIdInput
                    $codeChallengeInput
                    $codeChallengeMethodInput
                    <button type="submit">Authorize</button>
                </form>
                $errorBlock
            </div>
        </body>
        </html>
    """.trimIndent()
}

private fun String.escapeHtml(): String {
    return buildString(length) {
        for (char in this@escapeHtml) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}
