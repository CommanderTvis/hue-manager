package io.github.commandertvis.huemanager.hue

import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.ConfigLoader
import io.github.commandertvis.huemanager.network.configureJson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Client for Philips Hue Remote API (cloud-based control).
 */
class HueRemoteClient private constructor(
    private val clientId: String,
    private val clientSecret: String,
    private val appId: String,
    private var accessToken: String?,
    private var refreshToken: String?,
    private var username: String?,
    private val client: HttpClient = HttpClient(CIO) {
        configureJson()
    },
) : AutoCloseable by client {
    private val logger = LoggerFactory.getLogger(HueRemoteClient::class.java)

    /** Json instance for parsing token responses - ignores unknown keys like 'scope' */
    private val tokenJson = Json { ignoreUnknownKeys = true }

    private val lightRateLimiter = RateLimiter(maxTokens = 10)
    private val groupRateLimiter = RateLimiter(maxTokens = 1)

    /**
     * Flag indicating that the OAuth2 session is outdated and re-authorization is needed.
     * Set to true when token refresh fails (e.g., refresh token expired or revoked).
     */
    @Volatile
    var needsReauthorization: Boolean = false
        private set

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
            "$key=${value.encodeURLParameter()}"
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

            val response: HttpResponse = client.post("https://api.meethue.com/v2/oauth2/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                // Use Basic Authentication with base64(clientId:clientSecret)
                basicAuth(clientId, clientSecret)
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    // redirect_uri MUST match the one used in /authorize request
                    append("redirect_uri", redirectUri)
                }))
            }

            val responseBody = response.bodyAsText()
            logger.debug("Token exchange response status: {}", response.status)
            logger.debug("Token exchange response body: {}", responseBody.take(200))

            if (response.status.isSuccess()) {
                val tokenResponse: TokenResponse = tokenJson.decodeFromString(responseBody)
                accessToken = tokenResponse.accessToken
                refreshToken = tokenResponse.refreshToken

                // Save tokens to .env
                ConfigLoader.updateHueTokens(
                    tokenResponse.accessToken,
                    tokenResponse.refreshToken ?: ""
                )

                // Clear re-authorization flag on successful token exchange
                needsReauthorization = false

                logger.info("Successfully obtained OAuth2 tokens")
                logger.info("Access token expires in: ${tokenResponse.expiresIn} seconds")
                tokenResponse
            } else {
                logger.error("Failed to exchange code for tokens: ${response.status}")
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
            needsReauthorization = true
            return false
        }

        return try {
            val response: HttpResponse = client.post("https://api.meethue.com/v2/oauth2/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                basicAuth(clientId, clientSecret)
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refresh)
                }))
            }

            if (response.status.isSuccess()) {
                val tokenResponse: TokenResponse = response.body()
                accessToken = tokenResponse.accessToken
                if (tokenResponse.refreshToken != null) {
                    refreshToken = tokenResponse.refreshToken
                }

                ConfigLoader.updateHueTokens(
                    tokenResponse.accessToken,
                    tokenResponse.refreshToken ?: refresh
                )

                // Clear re-authorization flag on successful refresh
                needsReauthorization = false
                logger.info("Successfully refreshed access token")
                true
            } else {
                logger.error("Failed to refresh token: ${response.status}")
                // Mark as needing re-authorization when refresh fails
                needsReauthorization = true
                false
            }
        } catch (e: Exception) {
            logger.error("Error refreshing token: ${e.message}", e)
            needsReauthorization = true
            false
        }
    }

    /**
     * Clear the re-authorization flag (called after successful OAuth flow).
     */
    fun clearReauthorizationFlag() {
        needsReauthorization = false
    }

    /**
     * Link the remote API to a specific bridge (creates username).
     * User must press the bridge button before calling this.
     */
    suspend fun linkBridge(): LinkResult {
        val token = accessToken ?: return LinkResult.Error("Not authenticated")

        return try {
            // First, we need to activate the link button via Remote API
            val activateResponse: HttpResponse = client.put("https://api.meethue.com/route/api/0/config") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"linkbutton": true}""")
            }

            if (!activateResponse.status.isSuccess()) {
                return LinkResult.Error("Failed to activate link: ${activateResponse.status}")
            }

            // Now create the user
            val createResponse: HttpResponse = client.post("https://api.meethue.com/route/api") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"devicetype": "hue_manager#server"}""")
            }

            val body = createResponse.bodyAsText()
            logger.debug("Link response: $body")

            if (body.contains("\"success\"")) {
                val usernamePattern = "\"username\":\"([^\"]+)\"".toRegex()
                val match = usernamePattern.find(body)
                val newUsername = match?.groupValues?.get(1)
                    ?: return LinkResult.Error("Could not parse username from response")

                username = newUsername
                ConfigLoader.updateHueTokens(accessToken!!, refreshToken ?: "", newUsername)

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
     * Issue an authenticated request and retry once with a refreshed token on 401.
     * Returns null if there is no current token, or if a 401 retry's token refresh fails.
     * The same rate-limiter slot covers both attempts.
     */
    private suspend fun authedRequest(
        label: String,
        request: suspend (token: String) -> HttpResponse,
    ): HttpResponse? {
        val initialToken = accessToken ?: return null
        var response = request(initialToken)
        if (response.status == HttpStatusCode.Unauthorized) {
            logger.info("$label failed with 401. Refreshing token and retrying...")
            if (!refreshAccessToken()) return null
            val newToken = accessToken ?: return null
            response = request(newToken)
        }
        return response
    }

    /**
     * Get all lights via Remote API.
     */
    suspend fun getLights(): Map<String, HueLight> {
        val user = username ?: return emptyMap()
        return lightRateLimiter.execute {
            try {
                val response = authedRequest("getLights") { token ->
                    client.get("https://api.meethue.com/route/api/$user/lights") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                } ?: return@execute emptyMap()
                response.body<Map<String, HueLight>>()
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
                val response = authedRequest("getLight") { token ->
                    client.get("https://api.meethue.com/route/api/$user/lights/$id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                } ?: return@execute null
                response.body<HueLight>()
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
                val response = authedRequest("setLightState") { token ->
                    client.put("https://api.meethue.com/route/api/$user/lights/$id/state") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(state)
                    }
                } ?: return@execute false

                if (!response.status.isSuccess()) {
                    logger.warn("Set light state failed: ${response.status} Body: ${response.bodyAsText()}")
                }
                response.status.isSuccess()
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
                val response = authedRequest("getGroups") { token ->
                    client.get("https://api.meethue.com/route/api/$user/groups") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                } ?: return@execute emptyMap()
                response.body<Map<String, HueGroup>>()
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
                val response = authedRequest("getSensors") { token ->
                    client.get("https://api.meethue.com/route/api/$user/sensors") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                } ?: return@execute emptyMap()
                response.body<Map<String, HueSensor>>()
            } catch (e: Exception) {
                logger.error("Error getting sensors: ${e.message}", e)
                emptyMap()
            }
        }
    }

    companion object {
        fun fromConfig(config: Config): HueRemoteClient {
            val clientId = config.hueClientId
            val clientSecret = config.hueClientSecret
            val appId = config.hueAppId

            return HueRemoteClient(
                clientId = clientId,
                clientSecret = clientSecret,
                appId = appId,
                accessToken = config.hueAccessToken,
                refreshToken = config.hueRefreshToken,
                username = config.hueUsername
            )
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
