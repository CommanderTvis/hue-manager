package io.github.commandertvis.huemanager.hue

import io.github.commandertvis.huemanager.api.DiscoveredBridge
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Create a platform-specific HTTP client for Hue bridge communication.
 * Platform implementations should use configureJson() from network.JsonConfig.
 */
expect fun createHueBridgeHttpClient(): HttpClient

/**
 * Direct HTTP client for communicating with Philips Hue Bridge on local network.
 * This client connects directly to the bridge IP address, bypassing the server.
 *
 * Used for:
 * - Initial bridge linking (button press authentication)
 * - Direct bridge communication when server is remote
 *
 * Note: Uses HTTP instead of HTTPS for simplicity and compatibility.
 * Hue bridges on local network don't need encryption (already on private network).
 */
class HueBridgeClient(
    private val bridgeIp: String,
    httpClient: HttpClient? = null
) {
    private val client = httpClient ?: createHueBridgeHttpClient()
    private val shouldCloseClient = httpClient == null

    /**
     * Attempt to create a new user on the bridge.
     * Returns the username if successful, null if button not pressed, or throws on error.
     *
     * The bridge button must be pressed within 30 seconds before calling this method.
     */
    suspend fun createUser(deviceType: String = "hue_manager#client"): LinkResult {
        return try {
            // Use HTTP for local network communication (simpler than dealing with self-signed HTTPS certs)
            val response: HttpResponse = client.post("http://$bridgeIp/api") {
                contentType(ContentType.Application.Json)
                setBody(CreateUserRequest(devicetype = deviceType))
            }

            val body = response.bodyAsText()

            // Hue API returns an array with either success or error
            if (body.contains("\"success\"")) {
                // Parse the username from response
                val usernamePattern = "\"username\":\"([^\"]+)\"".toRegex()
                val match = usernamePattern.find(body)
                val username = match?.groupValues?.get(1)
                    ?: return LinkResult.Error("Could not parse username from response")

                LinkResult.Success(username)
            } else if (body.contains("link button not pressed")) {
                LinkResult.LinkButtonNotPressed
            } else {
                // Extract error message if possible
                val errorPattern = "\"description\":\"([^\"]+)\"".toRegex()
                val errorMatch = errorPattern.find(body)
                val errorMessage = errorMatch?.groupValues?.get(1) ?: "Unknown error"
                LinkResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            LinkResult.Error("Failed to connect to bridge: ${e.message}")
        }
    }

    /**
     * Validate that the bridge is reachable and responding.
     */
    suspend fun validateConnection(): Boolean {
        return try {
            val response: HttpResponse = client.get("http://$bridgeIp/api/config")
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        if (shouldCloseClient) {
            client.close()
        }
    }

    companion object {
        /**
         * Discover Hue bridges on the local network by scanning common IP ranges.
         * This method scans the local network (192.168.x.x and 10.0.x.x ranges) for Hue bridges.
         * 
         * @param httpClient Optional HTTP client to use for discovery
         * @param ipRanges List of IP ranges to scan (default: common home network ranges)
         * @return List of discovered bridges
         */
        suspend fun discoverBridgesOnLocalNetwork(
            httpClient: HttpClient? = null,
            ipRanges: List<String> = listOf(
                "192.168.1", "192.168.0", "192.168.2", 
                "10.0.0", "10.0.1"
            )
        ): List<DiscoveredBridge> = coroutineScope {
            val client = httpClient ?: createHueBridgeHttpClient()
            val shouldClose = httpClient == null
            
            try {
                val bridges = mutableListOf<DiscoveredBridge>()
                
                // Scan each IP range
                for (range in ipRanges) {
                    val jobs = (1..254).map { lastOctet ->
                        async {
                            val ip = "$range.$lastOctet"
                            try {
                                // Try to get bridge config with a short timeout
                                val response: HttpResponse = client.get("http://$ip/api/config")
                                
                                if (response.status.isSuccess()) {
                                    val body = response.bodyAsText()
                                    val json = Json.parseToJsonElement(body).jsonObject
                                    
                                    // Check if this is a Hue bridge by looking for bridgeid
                                    val bridgeId = json["bridgeid"]?.jsonPrimitive?.content
                                    if (bridgeId != null) {
                                        DiscoveredBridge(
                                            id = bridgeId,
                                            internalipaddress = ip,
                                            port = 80
                                        )
                                    } else null
                                } else null
                            } catch (e: Exception) {
                                // Ignore connection failures (expected for non-bridge IPs)
                                null
                            }
                        }
                    }
                    
                    // Wait for all scans in this range to complete
                    val rangeResults = jobs.awaitAll().filterNotNull()
                    bridges.addAll(rangeResults)
                    
                    // If we found bridges, we can stop scanning other ranges
                    if (bridges.isNotEmpty()) {
                        break
                    }
                }
                
                bridges
            } finally {
                if (shouldClose) {
                    client.close()
                }
            }
        }
    }
}

@Serializable
private data class CreateUserRequest(
    val devicetype: String
)

sealed class LinkResult {
    data class Success(val username: String) : LinkResult()
    data class Error(val message: String) : LinkResult()
    object LinkButtonNotPressed : LinkResult()
}
