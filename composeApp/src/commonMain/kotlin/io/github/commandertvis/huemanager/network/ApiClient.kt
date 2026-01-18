package io.github.commandertvis.huemanager.network

import io.github.commandertvis.huemanager.SERVER_PORT
import io.github.commandertvis.huemanager.api.*
import io.github.commandertvis.huemanager.models.Lamp
import io.github.commandertvis.huemanager.models.LoginRequest
import io.github.commandertvis.huemanager.models.LoginResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.time.Duration.Companion.seconds

expect fun createHttpClient(): HttpClient

class ApiClient(
    private val baseUrl: String = "http://localhost:$SERVER_PORT"
) {
    private val client = createHttpClient()
    private var authToken: String? = null

    // Rate limiter for discovery.meethue.com (5 second minimum between calls)
    private val discoveryRateLimiter = MinimumDelayRateLimiter(minimumDelay = 5.seconds)

    fun setAuthToken(token: String?) {
        authToken = token
    }

    fun getAuthToken(): String? = authToken

    suspend fun login(password: String): Result<LoginResponse> {
        return try {
            val response: HttpResponse = client.post("$baseUrl/api/session") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(password))
            }

            if (response.status.isSuccess()) {
                val loginResponse = response.body<LoginResponse>()
                if (loginResponse.success && loginResponse.token != null) {
                    authToken = loginResponse.token
                }
                Result.success(loginResponse)
            } else {
                Result.failure(ApiException("Login failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStatus(): Result<StatusResponse> {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api/status")
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLamps(): Result<LampsResponse> {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api/lamps")
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLamp(id: String): Result<Lamp> {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api/lamps/$id")
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateLamp(id: String, update: LampUpdateRequest): Result<ApiSuccess> {
        return try {
            val response: HttpResponse = client.put("$baseUrl/api/lamps/$id") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $authToken")
                setBody(update)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(ApiException("Update failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateAllLamps(update: AllLampsUpdateRequest): Result<ApiSuccess> {
        return try {
            val response: HttpResponse = client.put("$baseUrl/api/lamps/all") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $authToken")
                setBody(update)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(ApiException("Update failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroups(): Result<GroupsResponse> {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api/groups")
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun wakeUp(): Result<WakeUpResponse> {
        return try {
            val response: HttpResponse = client.post("$baseUrl/api/wakeup") {
                header(HttpHeaders.Authorization, "Bearer $authToken")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(ApiException("Wakeup failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sleep(): Result<SleepResponse> {
        return try {
            val response: HttpResponse = client.post("$baseUrl/api/sleep") {
                header(HttpHeaders.Authorization, "Bearer $authToken")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(ApiException("Sleep failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAutomationStatus(): Result<AutomationStatusResponse> {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api/automation")
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSettings(): Result<SettingsResponse> {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api/settings")
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSettings(update: SettingsUpdateRequest): Result<ApiSuccess> {
        return try {
            val response: HttpResponse = client.put("$baseUrl/api/settings") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $authToken")
                setBody(update)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(ApiException("Update failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearLampOverride(id: String): Result<ApiSuccess> {
        return try {
            val response: HttpResponse = client.delete("$baseUrl/api/lamps/$id/override") {
                header(HttpHeaders.Authorization, "Bearer $authToken")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(ApiException("Clear override failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun discoverBridges(): Result<List<DiscoveredBridge>> {
        // Check rate limit before making request
        val waitTime = discoveryRateLimiter.getRemainingWaitTime()
        if (waitTime > kotlin.time.Duration.ZERO) {
            return Result.failure(
                ApiException("Please wait ${waitTime.inWholeSeconds} seconds before discovering again.")
            )
        }

        return try {
            discoveryRateLimiter.recordCall()
            val response: HttpResponse = client.get("https://discovery.meethue.com")
            when {
                response.status.isSuccess() -> Result.success(response.body())
                response.status.value == 429 -> Result.failure(
                    ApiException("Too many discovery requests. Please wait a few seconds and try again.")
                )
                else -> Result.failure(
                    ApiException("Discovery failed: ${response.status}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun configureBridge(request: BridgeConfigRequest): Result<BridgeConfigResponse> {
        return try {
            val response: HttpResponse = client.post("$baseUrl/api/bridge/configure") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(ApiException("Bridge configuration failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun linkBridge(request: BridgeConfigRequest): Result<BridgeConfigResponse> {
        return try {
            val response: HttpResponse = client.post("$baseUrl/api/bridge/link") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $authToken")
                setBody(request)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(ApiException("Bridge linking failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAuthorizationUrl(): Result<String> {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api/hue/authorize")
            if (response.status.isSuccess()) {
                val body: Map<String, String> = response.body()
                Result.success(body["authorizationUrl"] ?: "")
            } else {
                Result.failure(ApiException("Failed to get authorization URL: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        client.close()
    }
}

class ApiException(message: String) : Exception(message)
