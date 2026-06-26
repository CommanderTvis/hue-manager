package io.github.commandertvis.huemanager.network

import io.github.commandertvis.huemanager.api.*
import io.github.commandertvis.huemanager.models.Lamp
import io.github.commandertvis.huemanager.models.AuthRequest
import io.github.commandertvis.huemanager.models.AuthResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

expect fun createHttpClient(): HttpClient

class ApiClient(private val baseUrl: String, private val client: HttpClient = createHttpClient()) :
    AutoCloseable by client {

    init {
        check(baseUrl.isNotEmpty()) { "Base URL cannot be empty" }
    }

    private var authToken: String? = null

    // Rate limiter for discovery.meethue.com (5s between calls)
    private val discoveryRateLimiter = MinimumDelayRateLimiter(minimumDelay = 5.seconds)

    fun setAuthToken(token: String?) {
        authToken = token
    }

    fun getAuthToken(): String? = authToken

    fun getBaseUrl(): String = baseUrl

    /** Invoked when an authenticated request returns 401 (stored session JWT invalid/expired). */
    var onSessionExpired: (() -> Unit)? = null

    // Maps an authenticated-request failure to an error; on 401 it also signals session expiry so
    // the app routes back to the login screen instead of showing a dead-end "<action> failed: 401".
    private fun authFailure(response: HttpResponse, action: String): ApiException =
        if (response.status == HttpStatusCode.Unauthorized) {
            onSessionExpired?.invoke()
            ApiException("Session expired — please log in again")
        } else {
            ApiException("$action failed: ${response.status}")
        }

    suspend fun login(password: String): Result<AuthResponse> = try {
        val response = client.post("$baseUrl/api/auth") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(password))
        }

        if (response.status.isSuccess()) {
            val authResponse = response.body<AuthResponse>()
            if (authResponse.success) {
                authToken = authResponse.token
            }
            Result.success(authResponse)
        } else {
            val errorMessage = when (response.status.value) {
                401 -> "Incorrect password"
                else -> "Login failed: ${response.status}"
            }
            Result.failure(ApiException(errorMessage))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getStatus(): Result<StatusResponse> = try {
        val response = client.get("$baseUrl/api/status")
        Result.success(response.body())
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getLamps(): Result<LampsResponse> = try {
        val response = client.get("$baseUrl/api/lamps")
        Result.success(response.body())
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getLamp(id: String): Result<Lamp> = try {
        val response = client.get("$baseUrl/api/lamps/$id")
        Result.success(response.body())
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateLamp(id: String, update: LampUpdateRequest): Result<ApiSuccess> = try {
        val response = client.put("$baseUrl/api/lamps/$id") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $authToken")
            setBody(update)
        }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            Result.failure(authFailure(response, "Update"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateAllLamps(update: AllLampsUpdateRequest): Result<ApiSuccess> = try {
        val response = client.put("$baseUrl/api/lamps/all") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $authToken")
            setBody(update)
        }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            Result.failure(authFailure(response, "Update"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getSensors(): Result<SensorsResponse> = try {
        val response = client.get("$baseUrl/api/sensors")
        Result.success(response.body())
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getGroups(): Result<GroupsResponse> = try {
        val response = client.get("$baseUrl/api/groups")
        Result.success(response.body())
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun wakeUp(): Result<WakeUpResponse> = try {
        val response = client.post("$baseUrl/api/wakeup") {
            header(HttpHeaders.Authorization, "Bearer $authToken")
        }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            Result.failure(authFailure(response, "Wake"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun sleep(): Result<SleepResponse> = try {
        val response = client.post("$baseUrl/api/sleep") {
            header(HttpHeaders.Authorization, "Bearer $authToken")
        }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            Result.failure(authFailure(response, "Sleep"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getAutomationStatus(): Result<AutomationStatusResponse> = try {
        val response = client.get("$baseUrl/api/automation")
        Result.success(response.body())
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getSync(): Result<SyncResponse> = try {
        val response = client.get("$baseUrl/api/sync")
        Result.success(response.body())
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun addPendingOperations(lampIds: List<String>): Result<ApiSuccess> = try {
        val response = client.post("$baseUrl/api/sync/pending") {
            contentType(ContentType.Application.Json)
            setBody(PendingOperationRequest(lampIds))
        }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            Result.failure(ApiException("Failed to add pending: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun clearPendingOperations(lampIds: List<String>): Result<ApiSuccess> = try {
        val response = client.delete("$baseUrl/api/sync/pending") {
            contentType(ContentType.Application.Json)
            setBody(PendingOperationRequest(lampIds))
        }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            Result.failure(ApiException("Failed to clear pending: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getSettings(): Result<SettingsResponse> = try {
        val response = client.get("$baseUrl/api/settings")
        Result.success(response.body())
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateSettings(update: SettingsUpdateRequest): Result<ApiSuccess> = try {
        val response = client.put("$baseUrl/api/settings") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $authToken")
            setBody(update)
        }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            Result.failure(authFailure(response, "Update"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun clearLampOverride(id: String): Result<ApiSuccess> = try {
        val response = client.delete("$baseUrl/api/lamps/$id/override") {
            header(HttpHeaders.Authorization, "Bearer $authToken")
        }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            Result.failure(authFailure(response, "Clear override"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun discoverBridges(): Result<List<DiscoveredBridge>> {
        // Check rate limit before making request
        val waitTime = discoveryRateLimiter.getRemainingWaitTime()
        if (waitTime > Duration.ZERO) {
            return Result.failure(
                ApiException("Please wait ${waitTime.inWholeSeconds} seconds before discovering again.")
            )
        }

        return try {
            discoveryRateLimiter.recordCall()
            val response = client.get("https://discovery.meethue.com")
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
            val response = client.post("$baseUrl/api/bridge/configure") {
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

    suspend fun linkBridge(request: BridgeConfigRequest): Result<BridgeConfigResponse> = try {
        val response = client.post("$baseUrl/api/bridge/link") {
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

    suspend fun getAuthorizationUrl(): Result<String> = try {
        val response = client.get("$baseUrl/api/hue/authorize")
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

class ApiException(message: String) : RuntimeException(message)
