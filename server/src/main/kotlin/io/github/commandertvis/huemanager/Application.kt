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
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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
        val sessionManager = SessionManager(config)

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
                module(config, hueService, sessionManager, automationManager)
            }.start(wait = true)
        }
    }
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
        post("/api/session") {
            val request = call.receive<LoginRequest>()
            val session = sessionManager.authenticate(request.password)
            if (session != null) {
                call.respond(LoginResponse.success(session.token))
            } else {
                call.respond(HttpStatusCode.Unauthorized, LoginResponse.failure("Invalid password"))
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

        // --- MCP (Model Context Protocol) ---
        val mcpHandler = McpHandler(hueService, automationManager, config.password)

        // MCP Authentication page for Claude Desktop
        get("/api/mcp/auth") {
            val scheme = call.request.headers["X-Forwarded-Proto"] ?: if (call.request.local.serverPort == 443) "https" else "http"
            val host = call.request.headers["X-Forwarded-Host"] ?: call.request.local.serverHost
            val mcpUrl = "$scheme://$host/api/mcp"

            call.respondText("""
                <!DOCTYPE html>
                <html>
                <head>
                    <title>MCP Authentication - Hue Manager</title>
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
                        button:disabled {
                            background: #ccc;
                            cursor: not-allowed;
                        }
                        .result {
                            display: none;
                            margin-top: 20px;
                            padding: 15px;
                            border-radius: 4px;
                        }
                        .success {
                            background: #d4edda;
                            color: #155724;
                            border: 1px solid #c3e6cb;
                        }
                        .error {
                            background: #f8d7da;
                            color: #721c24;
                            border: 1px solid #f5c6cb;
                        }
                        .token-display {
                            margin-top: 15px;
                            padding: 12px;
                            background: #f8f9fa;
                            border: 1px solid #ddd;
                            border-radius: 4px;
                            font-family: monospace;
                            word-break: break-all;
                            font-size: 14px;
                        }
                        .copy-btn {
                            margin-top: 10px;
                            background: #28a745;
                            font-size: 14px;
                            padding: 8px 16px;
                            width: auto;
                        }
                        .copy-btn:hover {
                            background: #218838;
                        }
                        .instructions {
                            margin-top: 20px;
                            padding: 15px;
                            background: #e7f3ff;
                            border-left: 4px solid #007AFF;
                            font-size: 14px;
                            line-height: 1.6;
                        }
                        .instructions code {
                            background: #fff;
                            padding: 2px 6px;
                            border-radius: 3px;
                            font-family: monospace;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>MCP Authentication</h1>
                        <p>Enter your password to get a session token for Claude Desktop</p>

                        <form id="authForm">
                            <input type="password" id="password" placeholder="Password" autocomplete="current-password" required>
                            <button type="submit" id="submitBtn">Get Token</button>
                        </form>

                        <div id="result" class="result"></div>

                        <div class="instructions">
                            <strong>How to use:</strong><br>
                            1. Enter your password and click "Get Token"<br>
                            2. Copy the generated token<br>
                            3. Add to your Claude Desktop MCP config:<br>
                            <pre style="margin: 10px 0; padding: 10px; background: #fff; border-radius: 4px; overflow-x: auto;">
{
  "mcpServers": {
    "hue-manager": {
      "url": "$mcpUrl",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN_HERE"
      }
    }
  }
}</pre>
                        </div>
                    </div>

                    <script>
                        document.getElementById('authForm').addEventListener('submit', async (e) => {
                            e.preventDefault();
                            const password = document.getElementById('password').value;
                            const submitBtn = document.getElementById('submitBtn');
                            const result = document.getElementById('result');

                            submitBtn.disabled = true;
                            submitBtn.textContent = 'Authenticating...';
                            result.style.display = 'none';

                            try {
                                const response = await fetch('/api/session', {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({ password })
                                });

                                const data = await response.json();

                                if (data.success && data.token) {
                                    result.className = 'result success';
                                    result.innerHTML = `
                                        <strong>✓ Authentication successful!</strong>
                                        <div class="token-display" id="token">${"$"}{data.token}</div>
                                        <button class="copy-btn" onclick="copyToken()">Copy Token</button>
                                    `;
                                    result.style.display = 'block';
                                    document.getElementById('password').value = '';
                                } else {
                                    result.className = 'result error';
                                    result.innerHTML = '<strong>✗ Authentication failed</strong><br>Invalid password';
                                    result.style.display = 'block';
                                }
                            } catch (error) {
                                result.className = 'result error';
                                result.innerHTML = '<strong>✗ Error</strong><br>Failed to authenticate';
                                result.style.display = 'block';
                            } finally {
                                submitBtn.disabled = false;
                                submitBtn.textContent = 'Get Token';
                            }
                        });

                        function copyToken() {
                            const token = document.getElementById('token').textContent;
                            navigator.clipboard.writeText(token).then(() => {
                                const btn = event.target;
                                const originalText = btn.textContent;
                                btn.textContent = 'Copied!';
                                setTimeout(() => { btn.textContent = originalText; }, 2000);
                            });
                        }
                    </script>
                </body>
                </html>
            """.trimIndent(), ContentType.Text.Html)
        }

        // MCP endpoint using Streamable HTTP transport
        // Note: Authentication is handled via Bearer token in Authorization header
        val mcpSessions = java.util.concurrent.ConcurrentHashMap<String, StreamableHttpServerTransport>()

        route("/api/mcp") {
            // Handle GET requests for SSE streaming
            sse {
                val sessionId = call.request.header("mcp-session-id")
                val transport = sessionId?.let { mcpSessions[it] }
                if (transport != null) {
                    transport.handleRequest(this, call)
                } else {
                    call.respond(HttpStatusCode.BadRequest, "Missing or invalid mcp-session-id header")
                }
            }

            // Handle POST requests for JSON-RPC messages
            post {
                val sessionId = call.request.header("mcp-session-id")

                if (sessionId != null && mcpSessions.containsKey(sessionId)) {
                    // Existing session
                    val transport = mcpSessions[sessionId]!!
                    transport.handleRequest(null, call)
                } else {
                    // New session (initialization request)
                    val transport = StreamableHttpServerTransport(enableJsonResponse = false)

                    transport.setOnSessionInitialized { newSessionId ->
                        mcpSessions[newSessionId] = transport
                        logger.info("MCP session initialized: $newSessionId")
                    }

                    transport.setOnSessionClosed { closedSessionId ->
                        mcpSessions.remove(closedSessionId)
                        logger.info("MCP session closed: $closedSessionId")
                    }

                    val server = mcpHandler.createServer()

                    CoroutineScope(Dispatchers.IO).launch {
                        server.createSession(transport)
                    }

                    // Small delay to ensure transport is ready
                    kotlinx.coroutines.delay(100)

                    transport.handleRequest(null, call)
                }
            }

            // Handle DELETE requests for session termination
            delete {
                val sessionId = call.request.header("mcp-session-id")
                val transport = sessionId?.let { mcpSessions[it] }
                if (transport != null) {
                    transport.handleRequest(null, call)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Session not found")
                }
            }
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
