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
 * Uses OAuth2 for authentication and routes requests through api.meethue.com.
 */
class HueRemoteClient(
    private val clientId: String,
    private val clientSecret: String,
    private val appId: String,
    private var accessToken: String?,
    private var refreshToken: String?,
    private var username: String?
) {
    private val logger = LoggerFactory.getLogger(HueRemoteClient::class.java)
    
    private val client = HttpClient(CIO) {
        configureJson()
    }
    
    private val lightRateLimiter = RateLimiter(maxTokens = 10)
    private val groupRateLimiter = RateLimiter(maxTokens = 1)
    
    val isConfigured: Boolean
        get() = accessToken != null && username != null
    
    /**
     * Generate the OAuth2 authorization URL for user to visit.
     */
    fun getAuthorizationUrl(redirectUri: String, state: String): String {
        val url = "https://api.meethue.com/v2/oauth2/authorize?" +
            "client_id=$clientId&" +
            "appid=$appId&" +
            "deviceid=server&" +
            "devicename=HueManagerServer&" +
            "response_type=code&" +
            "state=$state&" +
            "redirect_uri=${redirectUri.encodeURLParameter()}"
        logger.info("Built auth URL with clientId: $clientId, appId: $appId, and redirectUri: $redirectUri")
        return url
    }
    
    /**
     * Exchange authorization code for access and refresh tokens.
     */
    suspend fun exchangeCodeForTokens(code: String, redirectUri: String): TokenResponse? {
        return try {
            val response: HttpResponse = client.post("https://api.meethue.com/v2/oauth2/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                basicAuth(clientId, clientSecret)
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                }))
            }
            
            if (response.status.isSuccess()) {
                val tokenResponse: TokenResponse = response.body()
                accessToken = tokenResponse.accessToken
                refreshToken = tokenResponse.refreshToken
                
                // Save tokens to .env
                ConfigLoader.updateHueTokens(
                    tokenResponse.accessToken,
                    tokenResponse.refreshToken ?: ""
                )
                
                logger.info("Successfully obtained OAuth2 tokens")
                tokenResponse
            } else {
                logger.error("Failed to exchange code for tokens: ${response.status}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error exchanging code for tokens: ${e.message}", e)
            null
        }
    }
    
    /**
     * Refresh the access token using the refresh token.
     */
    suspend fun refreshAccessToken(): Boolean {
        val refresh = refreshToken ?: return false
        
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
                
                logger.info("Successfully refreshed access token")
                true
            } else {
                logger.error("Failed to refresh token: ${response.status}")
                false
            }
        } catch (e: Exception) {
            logger.error("Error refreshing token: ${e.message}", e)
            false
        }
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
                
                logger.info("Successfully linked to bridge, username: $newUsername")
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
            logger.error("Error linking bridge: ${e.message}", e)
            LinkResult.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Get all lights via Remote API.
     */
    suspend fun getLights(): Map<String, HueLight> {
        val token = accessToken ?: return emptyMap()
        val user = username ?: return emptyMap()
        
        return lightRateLimiter.execute {
            try {
                val response: HttpResponse = client.get("https://api.meethue.com/route/api/$user/lights") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                
                if (response.status == HttpStatusCode.Unauthorized) {
                    refreshAccessToken()
                    return@execute emptyMap()
                }
                
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
        val token = accessToken ?: return null
        val user = username ?: return null
        
        return lightRateLimiter.execute {
            try {
                val response: HttpResponse = client.get("https://api.meethue.com/route/api/$user/lights/$id") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                
                if (response.status == HttpStatusCode.Unauthorized) {
                    refreshAccessToken()
                    return@execute null
                }
                
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
        val token = accessToken ?: return false
        val user = username ?: return false
        
        return lightRateLimiter.execute {
            try {
                val response: HttpResponse = client.put("https://api.meethue.com/route/api/$user/lights/$id/state") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(state)
                }
                
                if (response.status == HttpStatusCode.Unauthorized) {
                    refreshAccessToken()
                    return@execute false
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
        val token = accessToken ?: return emptyMap()
        val user = username ?: return emptyMap()
        
        return groupRateLimiter.execute {
            try {
                val response: HttpResponse = client.get("https://api.meethue.com/route/api/$user/groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                
                if (response.status == HttpStatusCode.Unauthorized) {
                    refreshAccessToken()
                    return@execute emptyMap()
                }
                
                response.body<Map<String, HueGroup>>()
            } catch (e: Exception) {
                logger.error("Error getting groups: ${e.message}", e)
                emptyMap()
            }
        }
    }
    
    fun close() {
        client.close()
    }
    
    companion object {
        fun fromConfig(config: Config): HueRemoteClient? {
            val clientId = config.hueClientId ?: return null
            val clientSecret = config.hueClientSecret ?: return null
            val appId = config.hueAppId ?: return null
            
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
