package io.github.commandertvis.huemanager.hue

import io.github.commandertvis.huemanager.network.configureJson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds

object HueBridge {
    private val logger = LoggerFactory.getLogger(HueBridge::class.java)

    private const val DISCOVERY_URL = "https://discovery.meethue.com"
    private const val APP_NAME = "hue-manager"
    private const val DEVICE_NAME = "server"
    
    // Discovery should be called infrequently (5 second minimum delay)
    private val discoveryRateLimiter = MinimumDelayRateLimiter(minimumDelay = 5.seconds)
    // Linking attempts should have 1 second delay between retries
    private val linkingRateLimiter = MinimumDelayRateLimiter(minimumDelay = 1.seconds)

    private val client = HttpClient(CIO) {
        configureJson()

        engine {
            https {
                trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            }
        }
    }

    suspend fun discoverBridges(): List<DiscoveredBridge> = discoveryRateLimiter.execute {
        try {
            val response: HttpResponse = client.get(DISCOVERY_URL)
            response.body<List<DiscoveredBridge>>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createUser(bridgeIp: String): LinkResult = linkingRateLimiter.execute {
        val url = "https://$bridgeIp/api"
        val body = Json.encodeToString(
            LinkRequest.serializer(),
            LinkRequest(devicetype = "$APP_NAME#$DEVICE_NAME")
        )

        logger.debug("Attempting to create user on bridge at $bridgeIp")

        try {
            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val responseText = response.bodyAsText()
            logger.debug("Bridge response: $responseText")

            val jsonArray = Json.parseToJsonElement(responseText).jsonArray

            if (jsonArray.isEmpty()) {
                logger.warn("Empty response from bridge")
                return@execute LinkResult.Error("Empty response from bridge")
            }

            val firstElement = jsonArray[0].jsonObject

            if (firstElement.containsKey("error")) {
                val error = firstElement["error"]!!.jsonObject
                val errorType = error["type"]?.jsonPrimitive?.intOrNull
                val description = error["description"]?.jsonPrimitive?.content ?: "Unknown error"

                return@execute when (errorType) {
                    101 -> {
                        logger.debug("Link button not pressed yet")
                        LinkResult.LinkButtonNotPressed
                    }
                    else -> {
                        logger.warn("Bridge error: type=$errorType, description=$description")
                        LinkResult.Error(description)
                    }
                }
            }

            if (firstElement.containsKey("success")) {
                val success = firstElement["success"]!!.jsonObject
                val username = success["username"]?.jsonPrimitive?.content
                    ?: return@execute LinkResult.Error("No username in response")
                logger.info("Successfully created user: $username")
                return@execute LinkResult.Success(username)
            }

            logger.warn("Unexpected response format: $responseText")
            LinkResult.Error("Unexpected response format")
        } catch (e: Exception) {
            logger.error("Error connecting to bridge: ${e.message}", e)
            LinkResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun validateConnection(bridgeIp: String, username: String): Boolean = linkingRateLimiter.execute {
        try {
            val response: HttpResponse = client.get("https://$bridgeIp/api/$username/config")
            val text = response.bodyAsText()
            !text.contains("\"error\"")
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        client.close()
    }
}

@Serializable
data class DiscoveredBridge(
    val id: String,
    val internalipaddress: String,
    val port: Int? = null
)

@Serializable
data class LinkRequest(
    val devicetype: String
)

sealed class LinkResult {
    data class Success(val username: String) : LinkResult()
    data object LinkButtonNotPressed : LinkResult()
    data class Error(val message: String) : LinkResult()
}
