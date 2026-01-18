package io.github.commandertvis.huemanager.hue

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Create a platform-specific HTTP client for Hue bridge communication.
 * On some platforms, this may disable SSL verification for self-signed certificates.
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
