package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.automation.AutomationManager
import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.ConfigLoader
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LampStateCache
import io.github.commandertvis.huemanager.mcp.McpHandler
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile

private val mcpTokenRandom = SecureRandom()
private val mcpJson = Json { ignoreUnknownKeys = true }

private const val MCP_ENDPOINT = "/mcp"

fun Route.mcpRoutes(
    config: Config,
    hueService: HueService,
    automationManager: AutomationManager,
    lampStateCache: LampStateCache
) {
    val mcpHandler = McpHandler(hueService, automationManager, lampStateCache)

    // OAuth state storage
    val mcpOauthClients = ConcurrentHashMap<String, McpOauthClient>()
    val mcpOauthCodes = ConcurrentHashMap<String, McpOauthCode>()
    val mcpAccessTokens = ConcurrentHashMap<String, McpAccessToken>()

    val mcpSessions = ConcurrentHashMap<String, SseServerTransport>()

    // Protected Resource Metadata (RFC 9728)
    // Client starts here to discover authorization server
    get("/.well-known/oauth-protected-resource") {
        call.respond(buildMcpProtectedResourceMetadata(call))
    }

    // OAuth Authorization Server Metadata (RFC 8414)
    get("/.well-known/oauth-authorization-server") {
        call.respond(buildMcpOauthMetadata(call))
    }

    // Dynamic Client Registration
    post("$MCP_ENDPOINT/register") {
        val request = runCatching { call.receive<OAuthRegistrationRequest>() }.getOrNull()
        val redirectUris = request?.redirect_uris
            ?.mapNotNull { it.trim().takeIf { trimmed -> trimmed.isNotBlank() } }
            ?: emptyList()
        if (redirectUris.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, "redirect_uris is required")
            return@post
        }
        if (redirectUris.any { !isValidRedirectUri(it) }) {
            call.respond(HttpStatusCode.BadRequest, "Invalid redirect_uri")
            return@post
        }
        val tokenEndpointAuthMethod = request?.token_endpoint_auth_method
        if (!tokenEndpointAuthMethod.isNullOrBlank() && tokenEndpointAuthMethod != "none") {
            call.respond(HttpStatusCode.BadRequest, "Unsupported token_endpoint_auth_method")
            return@post
        }
        val grantTypes = request?.grant_types ?: listOf("authorization_code")
        if (grantTypes.any { it != "authorization_code" && it != "refresh_token" }) {
            call.respond(HttpStatusCode.BadRequest, "Unsupported grant_types")
            return@post
        }
        val responseTypes = request?.response_types ?: listOf("code")
        if (responseTypes.any { it != "code" }) {
            call.respond(HttpStatusCode.BadRequest, "Unsupported response_types")
            return@post
        }

        val clientId = UUID.randomUUID().toString()
        mcpOauthClients[clientId] = McpOauthClient(
            clientId = clientId,
            redirectUris = redirectUris.toSet(),
            grantTypes = grantTypes.toSet(),
            responseTypes = responseTypes.toSet(),
            tokenEndpointAuthMethod = "none"
        )
        logger.info("MCP OAuth client registered clientId={} redirectUris={}", clientId, redirectUris)
        call.respond(
            OAuthRegistrationResponse(
                client_id = clientId,
                client_id_issued_at = System.currentTimeMillis() / 1000,
                token_endpoint_auth_method = "none",
                grant_types = grantTypes,
                response_types = responseTypes,
                redirect_uris = redirectUris
            )
        )
    }

    // Authorization endpoint - GET shows login page, POST processes it
    get("$MCP_ENDPOINT/authorize") {
        val validation = validateMcpAuthRequest(call, call.parameters, mcpOauthClients)
        if (validation.error != null) {
            call.respond(HttpStatusCode.BadRequest, validation.error)
            return@get
        }
        val request = validation.request!!

        // Serve the WASM SPA (it detects /mcp/authorize path and shows OAuth UI)
        val webDir = Path("web")
        val indexFile = webDir.resolve("index.html")
        if (indexFile.isRegularFile()) {
            call.respondFile(indexFile.toFile())
        } else {
            // Fallback to simple HTML form if WASM app not available
            call.respondText(
                renderMcpOauthPage(
                    redirectUri = request.redirectUri,
                    state = request.state,
                    responseType = request.responseType,
                    clientId = request.clientId,
                    codeChallenge = request.codeChallenge,
                    codeChallengeMethod = request.codeChallengeMethod,
                    scope = request.scope,
                    resource = request.resource,
                    errorMessage = null
                ),
                ContentType.Text.Html
            )
        }
    }

    post("$MCP_ENDPOINT/authorize") {
        val params = call.receiveParameters()
        val validation = validateMcpAuthRequest(call, params, mcpOauthClients)
        if (validation.error != null) {
            call.respond(HttpStatusCode.BadRequest, validation.error)
            return@post
        }
        val request = validation.request!!
        val password = params["password"]
        if (password == null || !ConfigLoader.verifyPassword(password, config.passwordHash)) {
            call.respondText(
                renderMcpOauthPage(
                    redirectUri = request.redirectUri,
                    state = request.state,
                    responseType = request.responseType,
                    clientId = request.clientId,
                    codeChallenge = request.codeChallenge,
                    codeChallengeMethod = request.codeChallengeMethod,
                    scope = request.scope,
                    resource = request.resource,
                    errorMessage = "Invalid password"
                ),
                ContentType.Text.Html,
                status = HttpStatusCode.Unauthorized
            )
            return@post
        }

        val code = createMcpOauthCode(
            codes = mcpOauthCodes,
            clientId = request.clientId,
            redirectUri = request.redirectUri,
            resource = request.resource,
            scope = request.scope,
            codeChallenge = request.codeChallenge,
            codeChallengeMethod = request.codeChallengeMethod
        )
        logger.info(
            "MCP OAuth code issued clientId={} resource={} scope={}",
            request.clientId,
            request.resource,
            request.scope
        )
        val redirectUrl = buildMcpOauthCodeRedirect(request.redirectUri, code, request.state)
        call.respondRedirect(redirectUrl, permanent = false)
    }

    // Token endpoint
    post("$MCP_ENDPOINT/token") {
        val params = runCatching { call.receiveParameters() }.getOrNull()
        val jsonBody = if (params == null) {
            runCatching { call.receive<JsonObject>() }.getOrNull()
        } else {
            null
        }
        val grantType = params?.get("grant_type")
            ?: jsonBody?.get("grant_type")?.jsonPrimitive?.contentOrNull
        if (grantType != "authorization_code") {
            logger.warn("MCP token exchange rejected: unsupported grant_type={}", grantType)
            call.respond(HttpStatusCode.BadRequest, "Unsupported grant_type: $grantType")
            return@post
        }

        val code = params?.get("code")
            ?: jsonBody?.get("code")?.jsonPrimitive?.contentOrNull
        if (code.isNullOrBlank()) {
            logger.warn("MCP token exchange rejected: missing code")
            call.respond(HttpStatusCode.BadRequest, "code is required")
            return@post
        }

        val redirectUri = params?.get("redirect_uri")
            ?: jsonBody?.get("redirect_uri")?.jsonPrimitive?.contentOrNull
        val codeVerifier = params?.get("code_verifier")
            ?: jsonBody?.get("code_verifier")?.jsonPrimitive?.contentOrNull
        val clientId = params?.get("client_id")
            ?: jsonBody?.get("client_id")?.jsonPrimitive?.contentOrNull

        if (clientId.isNullOrBlank()) {
            logger.warn("MCP token exchange rejected: missing client_id")
            call.respond(HttpStatusCode.BadRequest, "client_id is required")
            return@post
        }
        if (redirectUri.isNullOrBlank()) {
            logger.warn("MCP token exchange rejected: missing redirect_uri")
            call.respond(HttpStatusCode.BadRequest, "redirect_uri is required")
            return@post
        }

        val now = System.currentTimeMillis()
        val codeEntry = mcpOauthCodes.remove(code)
        if (codeEntry == null || now > codeEntry.expiresAtMillis) {
            logger.warn("MCP token exchange rejected: invalid_or_expired_code")
            call.respond(HttpStatusCode.BadRequest, "Invalid or expired code")
            return@post
        }

        if (redirectUri != codeEntry.redirectUri) {
            logger.warn("MCP token exchange rejected: redirect_uri mismatch")
            call.respond(HttpStatusCode.BadRequest, "redirect_uri does not match")
            return@post
        }
        if (clientId != codeEntry.clientId) {
            logger.warn("MCP token exchange rejected: client_id mismatch")
            call.respond(HttpStatusCode.BadRequest, "client_id does not match")
            return@post
        }

        if (codeVerifier.isNullOrBlank()) {
            logger.warn("MCP token exchange rejected: missing code_verifier")
            call.respond(HttpStatusCode.BadRequest, "code_verifier is required")
            return@post
        }
        val method = codeEntry.codeChallengeMethod ?: "S256"
        if (codeEntry.codeChallenge.isNullOrBlank() || !verifyPkce(codeVerifier, codeEntry.codeChallenge, method)) {
            logger.warn("MCP token exchange rejected: PKCE verification failed")
            call.respond(HttpStatusCode.BadRequest, "Invalid code_verifier")
            return@post
        }

        val accessToken = issueMcpAccessToken(
            tokens = mcpAccessTokens,
            resource = codeEntry.resource,
            scope = codeEntry.scope
        )
        logger.info(
            "MCP OAuth token issued clientId={} resource={} scope={}",
            clientId,
            codeEntry.resource,
            codeEntry.scope
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.Pragma, "no-cache")
        call.respond(
            buildJsonObject {
                put("access_token", accessToken)
                put("token_type", "bearer")
                put("expires_in", MCP_OAUTH_ACCESS_TOKEN_TTL_SECONDS)
            }
        )
    }

    // Main MCP endpoint - handles SSE connections with OAuth tokens
    route(MCP_ENDPOINT) {
        // Intercept to check OAuth token before SSE handler runs
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            // Skip auth for OAuth sub-paths
            if (path.startsWith("$MCP_ENDPOINT/register") ||
                path.startsWith("$MCP_ENDPOINT/authorize") ||
                path.startsWith("$MCP_ENDPOINT/token")
            ) {
                return@intercept
            }

            if (call.request.httpMethod == HttpMethod.Get) {
                // Check for valid OAuth access token
                if (!call.checkMcpAccessToken(mcpAccessTokens)) {
                    call.respondMcpUnauthorized()
                    finish()
                }
            }
        }

        sse {
            val transport = SseServerTransport(MCP_ENDPOINT, this)
            mcpSessions[transport.sessionId] = transport

            val server = mcpHandler.createServer()
            server.onClose {
                mcpSessions.remove(transport.sessionId)
            }

            logger.info(
                "MCP SSE connected sessionId={} remote={}",
                transport.sessionId,
                call.request.local.remoteHost
            )
            try {
                server.createSession(transport)

                // Send SSE comment keepalives to prevent proxy idle timeouts
                val keepaliveJob = launch {
                    while (true) {
                        delay(30_000)
                        send(ServerSentEvent(comments = "keepalive"))
                    }
                }
                try {
                    awaitCancellation()
                } finally {
                    keepaliveJob.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("MCP SSE session failed sessionId={}", transport.sessionId, e)
                throw e
            } finally {
                mcpSessions.remove(transport.sessionId)
                logger.info("MCP SSE closed sessionId={}", transport.sessionId)
            }
        }
    }

    // POST to MCP endpoint for client messages (SSE transport)
    post(MCP_ENDPOINT) {
        if (!call.checkMcpAccessToken(mcpAccessTokens)) {
            call.respondMcpUnauthorized()
            return@post
        }

        val sessionId = call.request.queryParameters["sessionId"]
        if (sessionId == null) {
            logger.warn("MCP POST missing sessionId")
            call.respond(HttpStatusCode.BadRequest, "sessionId query parameter is required")
            return@post
        }

        val transport = mcpSessions[sessionId]
        if (transport == null) {
            logger.warn("MCP POST unknown sessionId={}", sessionId)
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return@post
        }

        transport.handlePostMessage(call)
    }
}

// Data classes
private data class McpOauthClient(
    val clientId: String,
    val redirectUris: Set<String>,
    val grantTypes: Set<String>,
    val responseTypes: Set<String>,
    val tokenEndpointAuthMethod: String
)

private data class McpAuthRequest(
    val redirectUri: String,
    val responseType: String,
    val clientId: String,
    val state: String?,
    val codeChallenge: String,
    val codeChallengeMethod: String,
    val scope: String?,
    val resource: String
)

private data class McpAuthValidation(
    val request: McpAuthRequest?,
    val error: String?
)

private data class McpOauthCode(
    val clientId: String,
    val redirectUri: String,
    val expiresAtMillis: Long,
    val resource: String,
    val scope: String?,
    val codeChallenge: String?,
    val codeChallengeMethod: String?
)

private data class McpAccessToken(
    val resource: String,
    val scope: String?,
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

// Constants
private const val MCP_OAUTH_CODE_TTL_MILLIS = 5 * 60 * 1000L
private const val MCP_OAUTH_ACCESS_TOKEN_TTL_SECONDS = 60L * 60

// Helper functions
private fun ApplicationCall.resolveBaseUrl(): String {
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

    return "$scheme://$hostWithPort"
}

private fun buildMcpProtectedResourceMetadata(call: ApplicationCall) = buildJsonObject {
    val baseUrl = call.resolveBaseUrl()
    val resource = "$baseUrl$MCP_ENDPOINT"
    put("resource", resource)
    putJsonArray("authorization_servers") {
        add(baseUrl)
    }
}

private fun buildMcpOauthMetadata(call: ApplicationCall) = buildJsonObject {
    val baseUrl = call.resolveBaseUrl()
    put("issuer", baseUrl)
    put("authorization_endpoint", "$baseUrl$MCP_ENDPOINT/authorize")
    put("token_endpoint", "$baseUrl$MCP_ENDPOINT/token")
    put("registration_endpoint", "$baseUrl$MCP_ENDPOINT/register")
    putJsonArray("response_types_supported") { add("code") }
    putJsonArray("grant_types_supported") { add("authorization_code") }
    putJsonArray("token_endpoint_auth_methods_supported") { add("none") }
    putJsonArray("code_challenge_methods_supported") { add("S256") }
    put("client_id_metadata_document_supported", true)
}

private suspend fun validateMcpAuthRequest(
    call: ApplicationCall,
    params: Parameters,
    clients: ConcurrentHashMap<String, McpOauthClient>
): McpAuthValidation {
    val responseType = params["response_type"]?.trim()?.ifBlank { null } ?: "code"
    if (responseType != "code") {
        return McpAuthValidation(null, "Unsupported response_type: $responseType")
    }

    val redirectUri = params["redirect_uri"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return McpAuthValidation(null, "redirect_uri is required")
    if (!isValidRedirectUri(redirectUri)) {
        return McpAuthValidation(null, "Invalid redirect_uri")
    }

    val clientId = params["client_id"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return McpAuthValidation(null, "client_id is required")
    val client = resolveMcpClient(clientId, clients)
        ?: return McpAuthValidation(null, "Unknown client_id")
    if (!client.redirectUris.contains(redirectUri)) {
        return McpAuthValidation(null, "redirect_uri does not match registered value")
    }

    val codeChallenge = params["code_challenge"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return McpAuthValidation(null, "code_challenge is required")
    val rawCodeChallengeMethod = params["code_challenge_method"]?.trim()?.takeIf { it.isNotBlank() }
        ?: return McpAuthValidation(null, "code_challenge_method is required")
    val codeChallengeMethod = rawCodeChallengeMethod.uppercase()
    if (codeChallengeMethod != "S256") {
        return McpAuthValidation(null, "Unsupported code_challenge_method: $rawCodeChallengeMethod")
    }

    // Resource defaults to the MCP endpoint
    val baseUrl = call.resolveBaseUrl()
    val resource = "$baseUrl$MCP_ENDPOINT"

    val scope = params["scope"]?.trim()?.takeIf { it.isNotBlank() }
    val state = params["state"]?.trim()?.takeIf { it.isNotBlank() }

    return McpAuthValidation(
        McpAuthRequest(
            redirectUri = redirectUri,
            responseType = responseType,
            clientId = clientId,
            state = state,
            codeChallenge = codeChallenge,
            codeChallengeMethod = codeChallengeMethod,
            scope = scope,
            resource = resource
        ),
        null
    )
}

private suspend fun resolveMcpClient(
    clientId: String,
    clients: ConcurrentHashMap<String, McpOauthClient>
): McpOauthClient? {
    clients[clientId]?.let { return it }

    // Known clients (hardcoded)
    resolveKnownClient(clientId)?.let { known ->
        clients[clientId] = known
        logger.info("MCP OAuth known client loaded clientId={}", clientId)
        return known
    }

    // Try to fetch client metadata document from URL
    val uri = runCatching { URI(clientId.trim()) }.getOrNull() ?: return null
    if (!uri.isAbsolute || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null

    val metadata = fetchClientMetadataDocument(clientId) ?: return null
    clients[clientId] = metadata
    logger.info("MCP OAuth client metadata fetched clientId={}", clientId)
    return metadata
}

private fun resolveKnownClient(clientId: String): McpOauthClient? {
    val normalized = clientId.trim().trimEnd('/')
    return when (normalized) {
        "https://claude.ai/oauth/mcp-oauth-client-metadata" -> McpOauthClient(
            clientId = clientId,
            redirectUris = setOf("https://claude.ai/api/mcp/auth_callback"),
            grantTypes = setOf("authorization_code"),
            responseTypes = setOf("code"),
            tokenEndpointAuthMethod = "none"
        )

        else -> null
    }
}

private suspend fun fetchClientMetadataDocument(clientIdUrl: String): McpOauthClient? {
    return withContext(Dispatchers.IO) {
        val connection = (URL(clientIdUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 5000
            readTimeout = 5000
        }
        val status = connection.responseCode
        if (status !in 200..299) return@withContext null

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val doc = runCatching { mcpJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext null

        val docClientId = doc["client_id"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        if (docClientId != clientIdUrl) return@withContext null

        val redirectUris = doc["redirect_uris"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
            ?: return@withContext null
        if (redirectUris.any { !isValidRedirectUri(it) }) return@withContext null

        val grantTypes = doc["grant_types"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
            ?: setOf("authorization_code")
        val responseTypes = doc["response_types"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
            ?: setOf("code")
        val tokenEndpointAuthMethod = doc["token_endpoint_auth_method"]?.jsonPrimitive?.contentOrNull ?: "none"

        McpOauthClient(
            clientId = docClientId,
            redirectUris = redirectUris.toSet(),
            grantTypes = grantTypes,
            responseTypes = responseTypes,
            tokenEndpointAuthMethod = tokenEndpointAuthMethod
        )
    }
}

private fun isValidRedirectUri(redirectUri: String): Boolean {
    val uri = runCatching { URI(redirectUri.trim()) }.getOrNull() ?: return false
    if (!uri.isAbsolute || uri.fragment != null) return false
    if (!uri.userInfo.isNullOrBlank()) return false
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (scheme == "https") return true
    if (scheme == "http") {
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }
    return false
}

private fun verifyPkce(codeVerifier: String, codeChallenge: String, method: String): Boolean {
    return when (method.lowercase()) {
        "plain" -> codeVerifier == codeChallenge
        "s256" -> sha256Base64Url(codeVerifier) == codeChallenge
        else -> false
    }
}

private fun sha256Base64Url(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun createMcpOauthCode(
    codes: ConcurrentHashMap<String, McpOauthCode>,
    clientId: String,
    redirectUri: String,
    resource: String,
    scope: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?
): String {
    val now = System.currentTimeMillis()
    codes.entries.removeIf { it.value.expiresAtMillis <= now }
    val code = UUID.randomUUID().toString()
    codes[code] = McpOauthCode(
        clientId = clientId,
        redirectUri = redirectUri,
        expiresAtMillis = now + MCP_OAUTH_CODE_TTL_MILLIS,
        resource = resource,
        scope = scope?.takeIf { it.isNotBlank() },
        codeChallenge = codeChallenge,
        codeChallengeMethod = codeChallengeMethod
    )
    return code
}

private fun issueMcpAccessToken(
    tokens: ConcurrentHashMap<String, McpAccessToken>,
    resource: String,
    scope: String?
): String {
    val now = System.currentTimeMillis()
    tokens.entries.removeIf { it.value.expiresAtMillis <= now }
    val bytes = ByteArray(32)
    mcpTokenRandom.nextBytes(bytes)
    val accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    tokens[accessToken] = McpAccessToken(
        resource = resource,
        scope = scope?.takeIf { it.isNotBlank() },
        expiresAtMillis = now + (MCP_OAUTH_ACCESS_TOKEN_TTL_SECONDS * 1000)
    )
    return accessToken
}

private fun ApplicationCall.checkMcpAccessToken(
    mcpAccessTokens: ConcurrentHashMap<String, McpAccessToken>
): Boolean {
    val token = extractBearerToken()
        ?: request.queryParameters["access_token"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.queryParameters["token"]?.trim()?.takeIf { it.isNotEmpty() }

    if (token == null) {
        logger.warn("MCP auth failed: missing token path={}", request.path())
        return false
    }

    val now = System.currentTimeMillis()
    val entry = mcpAccessTokens[token]
    if (entry == null) {
        logger.warn("MCP auth failed: invalid token path={}", request.path())
        return false
    }
    if (entry.expiresAtMillis <= now) {
        mcpAccessTokens.remove(token)
        logger.warn("MCP auth failed: expired token path={}", request.path())
        return false
    }
    return true
}

private suspend fun ApplicationCall.respondMcpUnauthorized() {
    val baseUrl = resolveBaseUrl()
    val resourceMetadataUrl = "$baseUrl/.well-known/oauth-protected-resource"
    response.header(
        HttpHeaders.WWWAuthenticate,
        """Bearer resource_metadata="$resourceMetadataUrl""""
    )
    respondBytes(ByteArray(0), ContentType.Text.Plain, status = HttpStatusCode.Unauthorized)
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

private fun renderMcpOauthPage(
    redirectUri: String,
    state: String?,
    responseType: String,
    clientId: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    scope: String?,
    resource: String?,
    errorMessage: String?
): String {
    val errorBlock = if (errorMessage != null) {
        """<div class="error">${errorMessage.escapeHtml()}</div>"""
    } else ""

    fun hiddenInput(name: String, value: String?) =
        if (!value.isNullOrBlank()) """<input type="hidden" name="$name" value="${value.escapeHtml()}">""" else ""

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <title>MCP Authorization - Hue Manager</title>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 500px; margin: 80px auto; padding: 20px; background: #f5f5f5; }
                .container { background: white; padding: 40px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                h1 { margin: 0 0 10px 0; font-size: 24px; color: #333; }
                p { color: #666; margin: 0 0 30px 0; }
                input[type="password"] { width: 100%; padding: 12px; border: 1px solid #ddd; border-radius: 4px; font-size: 16px; box-sizing: border-box; margin-bottom: 20px; }
                button { width: 100%; padding: 12px; background: #007AFF; color: white; border: none; border-radius: 4px; font-size: 16px; font-weight: 600; cursor: pointer; }
                button:hover { background: #0051D5; }
                .error { margin-top: 16px; padding: 12px; border-radius: 4px; background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>Authorize MCP Access</h1>
                <p>Enter your Hue Manager password to authorize this MCP client.</p>
                <form method="post" action="$MCP_ENDPOINT/authorize">
                    <input type="password" name="password" placeholder="Password" autocomplete="current-password" required>
                    ${hiddenInput("redirect_uri", redirectUri)}
                    ${hiddenInput("response_type", responseType)}
                    ${hiddenInput("state", state)}
                    ${hiddenInput("client_id", clientId)}
                    ${hiddenInput("code_challenge", codeChallenge)}
                    ${hiddenInput("code_challenge_method", codeChallengeMethod)}
                    ${hiddenInput("scope", scope)}
                    ${hiddenInput("resource", resource)}
                    <button type="submit">Authorize</button>
                </form>
                $errorBlock
            </div>
        </body>
        </html>
    """.trimIndent()
}

private fun String.escapeHtml(): String = buildString(length) {
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
