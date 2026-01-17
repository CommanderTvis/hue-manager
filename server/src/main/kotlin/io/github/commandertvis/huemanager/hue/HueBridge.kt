package io.github.commandertvis.huemanager.hue

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

object HueBridge {
    private const val DISCOVERY_URL = "https://discovery.meethue.com"
    private const val APP_NAME = "hue-manager"
    private const val DEVICE_NAME = "server"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
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

    suspend fun discoverBridges(): List<DiscoveredBridge> {
        return try {
            val response: HttpResponse = client.get(DISCOVERY_URL)
            response.body<List<DiscoveredBridge>>()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createUser(bridgeIp: String): LinkResult {
        val url = "https://$bridgeIp/api"
        val body = Json.encodeToString(
            LinkRequest.serializer(),
            LinkRequest(devicetype = "$APP_NAME#$DEVICE_NAME")
        )

        return try {
            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val responseText = response.bodyAsText()
            val jsonArray = Json.parseToJsonElement(responseText).jsonArray

            if (jsonArray.isEmpty()) {
                return LinkResult.Error("Empty response from bridge")
            }

            val firstElement = jsonArray[0].jsonObject

            if (firstElement.containsKey("error")) {
                val error = firstElement["error"]!!.jsonObject
                val errorType = error["type"]?.jsonPrimitive?.intOrNull
                val description = error["description"]?.jsonPrimitive?.content ?: "Unknown error"

                return when (errorType) {
                    101 -> LinkResult.LinkButtonNotPressed
                    else -> LinkResult.Error(description)
                }
            }

            if (firstElement.containsKey("success")) {
                val success = firstElement["success"]!!.jsonObject
                val username = success["username"]?.jsonPrimitive?.content
                    ?: return LinkResult.Error("No username in response")
                return LinkResult.Success(username)
            }

            LinkResult.Error("Unexpected response format")
        } catch (e: Exception) {
            LinkResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun validateConnection(bridgeIp: String, username: String): Boolean {
        return try {
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
