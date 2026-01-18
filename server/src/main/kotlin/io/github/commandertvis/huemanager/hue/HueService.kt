package io.github.commandertvis.huemanager.hue

import io.github.commandertvis.huemanager.config.Config
import org.slf4j.LoggerFactory

class HueService(private var config: Config) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(HueService::class.java)

    // Remote API client (cloud connection via OAuth2)
    private var remoteClient: HueRemoteClient? = HueRemoteClient.fromConfig(config)

    val isConnected: Boolean
        get() = remoteClient?.isConfigured == true

    val needsLinking: Boolean
        get() = remoteClient?.isConfigured != true
    
    fun getAuthorizationUrl(redirectUri: String, state: String, minimal: Boolean = false): String? {
        return remoteClient?.getAuthorizationUrl(redirectUri, state, minimal)
    }
    
    suspend fun handleOAuthCallback(code: String, redirectUri: String): Boolean {
        val tokens = remoteClient?.exchangeCodeForTokens(code, redirectUri)
        return tokens != null
    }
    
    suspend fun linkRemoteBridge(): LinkResult {
        return remoteClient?.linkBridge() ?: LinkResult.Error("Remote client not configured")
    }

    suspend fun initialize(): Boolean {
        // With remote API, we just check if we have valid tokens
        if (remoteClient?.isConfigured == true) {
            logger.info("Remote API client configured, ready to use")
            return true
        }
        
        logger.info("Remote API not configured, OAuth2 authorization required")
        return false
    }

    suspend fun getLights(): Map<String, HueLight> = remoteClient?.getLights() ?: emptyMap()

    suspend fun getLight(id: String): HueLight? = remoteClient?.getLight(id)

    suspend fun setLightState(id: String, state: HueLightStateUpdate): Boolean =
        remoteClient?.setLightState(id, state) ?: false

    suspend fun setAllLightsState(state: HueLightStateUpdate): Boolean {
        val lights = getLights()
        var success = true
        for (id in lights.keys) {
            if (!setLightState(id, state)) {
                success = false
            }
        }
        return success
    }

    suspend fun getGroups(): Map<String, HueGroup> = remoteClient?.getGroups() ?: emptyMap()

    suspend fun getEntertainmentGroups(): Map<String, HueGroup> = getGroups().filter { it.value.type == "Entertainment" }

    override fun close() {
        remoteClient?.close()
    }
}
