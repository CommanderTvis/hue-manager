package io.github.commandertvis.huemanager.hue

import io.github.commandertvis.huemanager.config.TokenStore
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Client for Philips Hue Remote API (cloud-based control).
 *
 * The authenticated lights/groups/sensors calls go through the injected Quarkus REST Client [HueApi]
 * (`@RegisterRestClient(configKey = "hue-api")`). The OAuth2 token endpoints
 * (`/v2/oauth2/token`, Basic auth + form encoding) and the bridge-linking calls (`/route/api`,
 * raw JSON bodies + ad-hoc parsing) use a JDK [HttpClient] — they don't fit the typed REST Client
 * model cleanly.
 *
 * Credentials and tokens are read from / written through [TokenStore], the single mutable-token
 * injection point (which persists token changes back to `.env`).
 */
@ApplicationScoped
class HueRemoteClient @Inject constructor(
    private val tokens: TokenStore,
    @RestClient private val api: HueApi,
) {
    private val logger = Logger.getLogger(HueRemoteClient::class.java)

    private val clientId: String get() = tokens.clientId
    private val clientSecret: String get() = tokens.clientSecret
    private val appId: String get() = tokens.appId
    private val accessToken: String? get() = tokens.accessToken
    private val refreshToken: String? get() = tokens.refreshToken
    private val username: String? get() = tokens.username

    /** Lenient Json for parsing/serializing OAuth + link payloads (ignores unknown keys like 'scope'). */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** JDK client for OAuth2 token exchange/refresh and bridge linking (not rate-limited: one-time setup). */
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private val lightRateLimiter = RateLimiter(maxTokens = 10)
    private val groupRateLimiter = RateLimiter(maxTokens = 1)

    /**
     * Flag indicating that the OAuth2 session is outdated and re-authorization is needed.
     * Set to true when token refresh fails (e.g., refresh token expired or revoked).
     */
    @Volatile
    private var _needsReauthorization: Boolean = false

    val needsReauthorization: Boolean get() = _needsReauthorization

    val isConfigured: Boolean
        get() = accessToken != null && username != null

    /**
     * Generate the OAuth2 authorization URL for user to visit.
     * Per Hue OAuth2 spec: https://developers.meethue.com/develop/hue-api/remote-authentication-oauth/
     *
     * Required parameters: client_id, response_type
     * Recommended: state
     * Optional: redirect_uri (must match developer portal if included), deviceid, devicename, appid
     */
    fun getAuthorizationUrl(redirectUri: String, state: String): String {
        val baseUrl = "https://api.meethue.com/v2/oauth2/authorize"

        val params = buildMap {
            // REQUIRED parameters
            put("client_id", clientId)
            put("response_type", "code")

            // RECOMMENDED parameter
            put("state", state)

            // OPTIONAL: redirect_uri - must EXACTLY match what's in developer portal
            // According to docs: "can be omitted since Hue currently only supports one redirect uri per application"
            // However, if included it must be exact and also sent in token request
            put("redirect_uri", redirectUri)

            // OPTIONAL: deviceid and devicename
            put("deviceid", "hue_manager_server")
            put("devicename", "Hue Manager Server")

            // OPTIONAL: appid (marked as "might be removed in the future" in docs)
            put("appid", appId)
        }

        val queryString = params.entries.joinToString("&") { (key, value) ->
            "$key=${value.urlEncode()}"
        }

        val finalUrl = "$baseUrl?$queryString"

        logger.info("=== Hue OAuth2 Authorization URL ===")
        logger.info("Client ID: $clientId")
        logger.info("App ID: $appId")
        logger.info("Redirect URI: $redirectUri")
        logger.info("State: ${state.take(8)}...")
        logger.info("Parameters: ${params.keys.joinToString(", ")}")
        logger.info("Full URL: $finalUrl")
        logger.info("=".repeat(50))

        return finalUrl
    }

    /**
     * Exchange authorization code for access and refresh tokens.
     * Uses Basic Authentication as recommended in Hue OAuth2 spec.
     *
     * Per docs: redirect_uri must be included if it was in the /authorize request.
     */
    suspend fun exchangeCodeForTokens(code: String, redirectUri: String): TokenResponse? {
        return try {
            logger.info("Exchanging authorization code for tokens...")
            logger.debug("Using redirect_uri: $redirectUri")

            val response = postTokenForm(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    // redirect_uri MUST match the one used in /authorize request
                    "redirect_uri" to redirectUri,
                )
            )

            val responseBody = response.body()
            logger.debug("Token exchange response status: ${response.statusCode()}")
            logger.debug("Token exchange response body: ${responseBody.take(200)}")

            if (response.isSuccess()) {
                val tokenResponse: TokenResponse = json.decodeFromString(responseBody)

                // Save tokens (persisted to .env by TokenStore)
                tokens.updateTokens(
                    tokenResponse.accessToken,
                    tokenResponse.refreshToken ?: ""
                )

                // Clear re-authorization flag on successful token exchange
                _needsReauthorization = false

                logger.info("Successfully obtained OAuth2 tokens")
                logger.info("Access token expires in: ${tokenResponse.expiresIn} seconds")
                tokenResponse
            } else {
                logger.error("Failed to exchange code for tokens: ${response.statusCode()}")
                logger.error("Response body: $responseBody")
                null
            }
        } catch (e: Exception) {
            logger.error("Error exchanging code for tokens: ${e.message}", e)
            null
        }
    }

    /**
     * Refresh the access token using the refresh token.
     * Sets [needsReauthorization] to true if refresh fails.
     */
    suspend fun refreshAccessToken(): Boolean {
        val refresh = refreshToken ?: run {
            logger.warn("No refresh token available, re-authorization required")
            _needsReauthorization = true
            return false
        }

        return try {
            val response = postTokenForm(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                )
            )

            if (response.isSuccess()) {
                val tokenResponse: TokenResponse = json.decodeFromString(response.body())

                tokens.updateTokens(
                    tokenResponse.accessToken,
                    tokenResponse.refreshToken ?: refresh
                )

                // Clear re-authorization flag on successful refresh
                _needsReauthorization = false
                logger.info("Successfully refreshed access token")
                true
            } else {
                logger.error("Failed to refresh token: ${response.statusCode()}")
                // Mark as needing re-authorization when refresh fails
                _needsReauthorization = true
                false
            }
        } catch (e: Exception) {
            logger.error("Error refreshing token: ${e.message}", e)
            _needsReauthorization = true
            false
        }
    }

    /**
     * Clear the re-authorization flag (called after successful OAuth flow).
     */
    fun clearReauthorizationFlag() {
        _needsReauthorization = false
    }

    /**
     * Link the remote API to a specific bridge (creates username).
     * User must press the bridge button before calling this.
     */
    suspend fun linkBridge(): LinkResult {
        val token = accessToken ?: return LinkResult.Error("Not authenticated")

        return try {
            // First, we need to activate the link button via Remote API
            val activateResponse = withContext(Dispatchers.IO) {
                httpClient.send(
                    HttpRequest.newBuilder(URI.create("https://api.meethue.com/route/api/0/config"))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("""{"linkbutton": true}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }

            if (!activateResponse.isSuccess()) {
                return LinkResult.Error("Failed to activate link: ${activateResponse.statusCode()}")
            }

            // Now create the user
            val createResponse = withContext(Dispatchers.IO) {
                httpClient.send(
                    HttpRequest.newBuilder(URI.create("https://api.meethue.com/route/api"))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"devicetype": "hue_manager#server"}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }

            val body = createResponse.body()
            logger.debug("Link response: $body")

            if (body.contains("\"success\"")) {
                val usernamePattern = "\"username\":\"([^\"]+)\"".toRegex()
                val match = usernamePattern.find(body)
                val newUsername = match?.groupValues?.get(1)
                    ?: return LinkResult.Error("Could not parse username from response")

                tokens.updateTokens(accessToken!!, refreshToken ?: "", newUsername)

                logger.info("Successfully linked to Hue Bridge, username: $newUsername")
                LinkResult.Success(newUsername)
            } else if (body.contains("link button not pressed")) {
                LinkResult.LinkButtonNotPressed
            } else {
                val errorPattern = "\"description\":\"([^\"]+)\"".toRegex()
                val errorMatch = errorPattern.find(body)
                val errorMessage = errorMatch?.groupValues?.get(1) ?: "Unknown error"
                LinkResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            logger.error("Error linking Hue Bridge: ${e.message}", e)
            LinkResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Run a REST Client call, refreshing the token once and retrying on a 401.
     * Returns null if there is no current token, or if a 401 retry's token refresh fails.
     * The caller is responsible for holding the appropriate rate-limiter slot (which covers both
     * attempts, matching the previous single-slot behavior).
     */
    private suspend fun <T> authedRequest(
        label: String,
        request: suspend (token: String) -> T,
    ): T? {
        val initialToken = accessToken ?: return null
        return try {
            request("Bearer $initialToken")
        } catch (e: WebApplicationException) {
            if (e.response?.status == Response.Status.UNAUTHORIZED.statusCode) {
                logger.info("$label failed with 401. Refreshing token and retrying...")
                if (!refreshAccessToken()) return null
                val newToken = accessToken ?: return null
                request("Bearer $newToken")
            } else {
                throw e
            }
        }
    }

    /**
     * Get all lights via Remote API.
     */
    suspend fun getLights(): Map<String, HueLight> {
        val user = username ?: return emptyMap()
        return lightRateLimiter.execute {
            try {
                authedRequest("getLights") { auth ->
                    withContext(Dispatchers.IO) { api.getLights(user, auth) }
                } ?: emptyMap()
            } catch (e: Exception) {
                logger.error("Error getting lights: ${e.message}", e)
                emptyMap()
            }
        }
    }

    /**
     * Get a single light.
     */
    suspend fun getLight(id: String): HueLight? {
        val user = username ?: return null
        return lightRateLimiter.execute {
            try {
                authedRequest("getLight") { auth ->
                    withContext(Dispatchers.IO) { api.getLight(user, id, auth) }
                }
            } catch (e: Exception) {
                logger.error("Error getting light $id: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Set light state.
     */
    suspend fun setLightState(id: String, state: HueLightStateUpdate): Boolean {
        val user = username ?: return false
        return lightRateLimiter.execute {
            try {
                val response = authedRequestResponse("setLightState") { auth ->
                    withContext(Dispatchers.IO) { api.setLightState(user, id, auth, state) }
                } ?: return@execute false

                if (!response.isSuccess()) {
                    logger.warn("Set light state failed: ${response.status} Body: ${response.readEntityString()}")
                }
                response.isSuccess()
            } catch (e: Exception) {
                logger.error("Error setting light state: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Get all groups.
     */
    suspend fun getGroups(): Map<String, HueGroup> {
        val user = username ?: return emptyMap()
        return groupRateLimiter.execute {
            try {
                authedRequest("getGroups") { auth ->
                    withContext(Dispatchers.IO) { api.getGroups(user, auth) }
                } ?: emptyMap()
            } catch (e: Exception) {
                logger.error("Error getting groups: ${e.message}", e)
                emptyMap()
            }
        }
    }

    /**
     * Get all sensors (motion sensors, switches, smart buttons, daylight, etc.) via Remote API.
     */
    suspend fun getSensors(): Map<String, HueSensor> {
        val user = username ?: return emptyMap()
        return lightRateLimiter.execute {
            try {
                authedRequest("getSensors") { auth ->
                    withContext(Dispatchers.IO) { api.getSensors(user, auth) }
                } ?: emptyMap()
            } catch (e: Exception) {
                logger.error("Error getting sensors: ${e.message}", e)
                emptyMap()
            }
        }
    }

    /**
     * Variant of [authedRequest] for calls that return a raw JAX-RS [Response] (e.g. setLightState),
     * where a 401 is observable on the status rather than thrown as an exception.
     */
    private suspend fun authedRequestResponse(
        label: String,
        request: suspend (token: String) -> Response,
    ): Response? {
        val initialToken = accessToken ?: return null
        var response = request("Bearer $initialToken")
        if (response.status == Response.Status.UNAUTHORIZED.statusCode) {
            logger.info("$label failed with 401. Refreshing token and retrying...")
            response.close()
            if (!refreshAccessToken()) return null
            val newToken = accessToken ?: return null
            response = request("Bearer $newToken")
        }
        return response
    }

    /** POST a form-url-encoded body to the OAuth2 token endpoint with Basic auth. */
    private suspend fun postTokenForm(form: Map<String, String>): HttpResponse<String> {
        val body = form.entries.joinToString("&") { (k, v) -> "${k.urlEncode()}=${v.urlEncode()}" }
        val basic = Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))
        val request = HttpRequest.newBuilder(URI.create("https://api.meethue.com/v2/oauth2/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic $basic")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
    }

    companion object {
        private fun String.urlEncode(): String =
            URLEncoder.encode(this, StandardCharsets.UTF_8)

        private fun HttpResponse<String>.isSuccess(): Boolean = statusCode() in 200..299

        private fun Response.isSuccess(): Boolean = status in 200..299

        private fun Response.readEntityString(): String =
            try {
                readEntity(String::class.java)
            } catch (_: Exception) {
                ""
            }
    }
}

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null
)

sealed class LinkResult {
    data class Success(val username: String) : LinkResult()
    data object LinkButtonNotPressed : LinkResult()
    data class Error(val message: String) : LinkResult()
}
