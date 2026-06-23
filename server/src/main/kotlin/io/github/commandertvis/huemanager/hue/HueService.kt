package io.github.commandertvis.huemanager.hue

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Service layer over the Philips Hue Remote API. Wraps [HueRemoteClient] and, on successful
 * writes, optimistically patches [LampStateCache] so subsequent reads reflect the change before
 * the next background refresh.
 *
 * [LampStateCache] and [HueService] reference each other; CDI injects client proxies, so the
 * cycle is fine as long as neither dereferences the other during construction. This replaces the
 * old manual `setCache()` wiring.
 */
@ApplicationScoped
class HueService @Inject constructor(
    private val remoteClient: HueRemoteClient,
    private val cache: LampStateCache,
) {
    private val logger = Logger.getLogger(HueService::class.java)

    val isConnected: Boolean
        get() = remoteClient.isConfigured

    val needsLinking: Boolean
        get() = !remoteClient.isConfigured

    /**
     * Returns true if the OAuth2 session is outdated and user needs to re-authorize.
     * This happens when the refresh token is expired or revoked.
     */
    val needsReauthorization: Boolean
        get() = remoteClient.needsReauthorization

    fun getAuthorizationUrl(redirectUri: String, state: String): String =
        remoteClient.getAuthorizationUrl(redirectUri, state)

    suspend fun handleOAuthCallback(code: String, redirectUri: String): Boolean {
        val tokens = remoteClient.exchangeCodeForTokens(code, redirectUri)
        return tokens != null
    }

    suspend fun linkRemoteBridge(): LinkResult = remoteClient.linkBridge()

    fun initialize(): Boolean {
        // With remote API, we just check if we have valid tokens
        if (remoteClient.isConfigured) {
            logger.info("Remote API client configured, ready to use")
            return true
        }

        logger.info("Remote API not configured, OAuth2 authorization required")
        return false
    }

    suspend fun getLights(): Map<String, HueLight> = remoteClient.getLights()

    suspend fun getLight(id: String): HueLight? = remoteClient.getLight(id)

    suspend fun setLightState(id: String, state: HueLightStateUpdate): Boolean {
        val success = remoteClient.setLightState(id, state)
        if (success) cache.updateLightState(id, state)
        return success
    }

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

    suspend fun getGroups(): Map<String, HueGroup> = remoteClient.getGroups()

    suspend fun getSensors(): Map<String, HueSensor> = remoteClient.getSensors()

    suspend fun getEntertainmentGroups(): Map<String, HueGroup> =
        getGroups().filter { it.value.type == "Entertainment" }
}
