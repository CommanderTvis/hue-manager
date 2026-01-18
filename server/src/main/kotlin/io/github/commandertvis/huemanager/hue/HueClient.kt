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
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration.Companion.seconds

class HueClient(
    private val bridgeIp: String,
    private val username: String
) {
    private val baseUrl = "https://$bridgeIp/api/$username"

    // Hue bridge rate limits (shared across all connected apps):
    // - Individual lights: ~10 commands per second
    // - Groups: 1 command per second
    private val lightRateLimiter = RateLimiter(maxTokens = 10, refillRate = 1.seconds)
    private val groupRateLimiter = RateLimiter(maxTokens = 1, refillRate = 1.seconds)

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

    suspend fun getLights(): Map<String, HueLight> = lightRateLimiter.execute {
        val response: HttpResponse = client.get("$baseUrl/lights")
        val json = response.body<JsonObject>()
        json.mapValues { (_, value) ->
            Json.decodeFromJsonElement<HueLight>(value)
        }
    }

    suspend fun getLight(id: String): HueLight? = lightRateLimiter.execute {
        try {
            val response: HttpResponse = client.get("$baseUrl/lights/$id")
            response.body<HueLight>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setLightState(id: String, state: HueLightStateUpdate): Boolean = lightRateLimiter.execute {
        try {
            val response: HttpResponse = client.put("$baseUrl/lights/$id/state") {
                contentType(ContentType.Application.Json)
                setBody(state)
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getGroups(): Map<String, HueGroup> = groupRateLimiter.execute {
        val response: HttpResponse = client.get("$baseUrl/groups")
        val json = response.body<JsonObject>()
        json.mapValues { (_, value) ->
            Json.decodeFromJsonElement<HueGroup>(value)
        }
    }

    suspend fun getGroup(id: String): HueGroup? = groupRateLimiter.execute {
        try {
            val response: HttpResponse = client.get("$baseUrl/groups/$id")
            response.body<HueGroup>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setGroupState(id: String, state: HueLightStateUpdate): Boolean = groupRateLimiter.execute {
        try {
            val response: HttpResponse = client.put("$baseUrl/groups/$id/action") {
                contentType(ContentType.Application.Json)
                setBody(state)
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        client.close()
    }
}

@Serializable
data class HueLight(
    val state: HueLightState,
    val type: String,
    val name: String,
    val modelid: String? = null,
    val manufacturername: String? = null,
    val productname: String? = null,
    val uniqueid: String? = null
)

@Serializable
data class HueLightState(
    val on: Boolean,
    val bri: Int? = null,
    val hue: Int? = null,
    val sat: Int? = null,
    val xy: List<Double>? = null,
    val ct: Int? = null,
    val alert: String? = null,
    val effect: String? = null,
    val colormode: String? = null,
    val reachable: Boolean? = null
)

@Serializable
data class HueLightStateUpdate(
    val on: Boolean? = null,
    val bri: Int? = null,
    val hue: Int? = null,
    val sat: Int? = null,
    val xy: List<Double>? = null,
    val ct: Int? = null,
    val transitiontime: Int? = null
)

@Serializable
data class HueGroup(
    val name: String,
    val lights: List<String>,
    val type: String,
    val state: HueGroupState? = null,
    val action: HueLightState? = null,
    val `class`: String? = null,
    val stream: HueStreamState? = null
)

@Serializable
data class HueGroupState(
    val all_on: Boolean,
    val any_on: Boolean
)

@Serializable
data class HueStreamState(
    val active: Boolean
)
