package io.github.commandertvis.huemanager.hue

import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.ConfigLoader
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

class HueService(private var config: Config) {
    private val logger = LoggerFactory.getLogger(HueService::class.java)

    private var client: HueClient? = null
    private var bridgeIp: String? = config.hueBridgeIp
    private var username: String? = config.hueUsername

    val isConnected: Boolean
        get() = client != null

    val needsLinking: Boolean
        get() = bridgeIp == null || username == null

    suspend fun initialize(): Boolean {
        if (bridgeIp == null) {
            logger.info("No bridge IP configured, attempting discovery...")
            val bridges = HueBridge.discoverBridges()
            if (bridges.isEmpty()) {
                logger.warn("No Hue bridges discovered")
                return false
            }
            bridgeIp = bridges.first().internalipaddress
            logger.info("Discovered bridge at $bridgeIp")
        }

        if (username == null) {
            logger.info("No username configured, bridge linking required")
            return false
        }

        return connect()
    }

    private suspend fun connect(): Boolean {
        val ip = bridgeIp ?: return false
        val user = username ?: return false

        val valid = HueBridge.validateConnection(ip, user)
        if (!valid) {
            logger.error("Failed to validate connection to bridge at $ip")
            return false
        }

        client = HueClient(ip, user)
        logger.info("Connected to Hue bridge at $ip")
        return true
    }

    suspend fun startLinking(maxAttempts: Int = 30, delayMs: Long = 2000): LinkResult {
        val ip = bridgeIp
        if (ip == null) {
            val bridges = HueBridge.discoverBridges()
            if (bridges.isEmpty()) {
                return LinkResult.Error("No Hue bridges found")
            }
            bridgeIp = bridges.first().internalipaddress
        }

        val targetIp = bridgeIp!!
        logger.info("Starting link process with bridge at $targetIp")
        logger.info("Please press the link button on your Hue bridge...")

        repeat(maxAttempts) { attempt ->
            val result = HueBridge.createUser(targetIp)
            when (result) {
                is LinkResult.Success -> {
                    username = result.username
                    ConfigLoader.updateHueCredentials(targetIp, result.username)
                    logger.info("Successfully linked! Username: ${result.username}")

                    if (connect()) {
                        return result
                    }
                    return LinkResult.Error("Linked but failed to connect")
                }
                is LinkResult.LinkButtonNotPressed -> {
                    if (attempt < maxAttempts - 1) {
                        delay(delayMs)
                    }
                }
                is LinkResult.Error -> {
                    return result
                }
            }
        }

        return LinkResult.Error("Linking timed out - button was not pressed")
    }

    suspend fun getLights(): Map<String, HueLight> {
        return client?.getLights() ?: emptyMap()
    }

    suspend fun getLight(id: String): HueLight? {
        return client?.getLight(id)
    }

    suspend fun setLightState(id: String, state: HueLightStateUpdate): Boolean {
        return client?.setLightState(id, state) ?: false
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

    suspend fun getGroups(): Map<String, HueGroup> {
        return client?.getGroups() ?: emptyMap()
    }

    suspend fun getEntertainmentGroups(): Map<String, HueGroup> {
        return getGroups().filter { it.value.type == "Entertainment" }
    }

    fun getConfig(): Config = config

    fun getBridgeIp(): String? = bridgeIp

    /**
     * Configure bridge from external source (e.g., client app that discovered it locally)
     */
    suspend fun configureBridge(ip: String, user: String): Boolean {
        logger.info("Configuring bridge from external source: $ip")

        val valid = HueBridge.validateConnection(ip, user)
        if (!valid) {
            logger.error("Failed to validate external bridge config at $ip")
            return false
        }

        bridgeIp = ip
        username = user
        ConfigLoader.updateHueCredentials(ip, user)

        return connect()
    }

    /**
     * Start linking from external bridge IP (client discovered it locally)
     */
    suspend fun linkExternalBridge(ip: String, maxAttempts: Int = 30, delayMs: Long = 2000): LinkResult {
        bridgeIp = ip
        logger.info("Starting link process with externally-provided bridge at $ip")
        logger.info("Please press the link button on your Hue bridge...")

        repeat(maxAttempts) { attempt ->
            val result = HueBridge.createUser(ip)
            when (result) {
                is LinkResult.Success -> {
                    username = result.username
                    ConfigLoader.updateHueCredentials(ip, result.username)
                    logger.info("Successfully linked! Username: ${result.username}")

                    if (connect()) {
                        return result
                    }
                    return LinkResult.Error("Linked but failed to connect")
                }
                is LinkResult.LinkButtonNotPressed -> {
                    if (attempt < maxAttempts - 1) {
                        delay(delayMs)
                    }
                }
                is LinkResult.Error -> {
                    return result
                }
            }
        }

        return LinkResult.Error("Linking timed out - button was not pressed")
    }

    fun close() {
        client?.close()
        HueBridge.close()
    }
}
