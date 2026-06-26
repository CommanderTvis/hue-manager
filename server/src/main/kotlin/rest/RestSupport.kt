package io.github.commandertvis.huemanager.rest

import io.github.commandertvis.huemanager.api.ApiError
import io.github.commandertvis.huemanager.auth.JwtService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.server.ServerExceptionMapper

/**
 * Encode a `@Serializable` [value] to a JSON [RestResponse] using its compile-time [serializer].
 *
 * Required for values returned from `suspend` resource methods: the Kotlin compiler erases such a
 * method's signature to `(…, Continuation) -> Object`, so Quarkus' build-time scan of resource
 * signatures never sees the real return type and does not register its kotlinx serializer. The
 * native image then fails the reflective serializer lookup at runtime (HTTP 500). Encoding
 * explicitly with the generated serializer avoids reflection entirely — same rationale as
 * [ApiExceptionMapper].
 */
internal fun <T> jsonResponse(serializer: SerializationStrategy<T>, value: T): RestResponse<String> =
    RestResponse.ResponseBuilder
        .create<String>(RestResponse.Status.OK)
        .entity(Json.encodeToString(serializer, value))
        .type(MediaType.APPLICATION_JSON)
        .build()

/**
 * Application error carrying an HTTP status + message, mapped to a JSON [ApiError] body by
 * [ApiExceptionMapper].
 */
class ApiException(val statusCode: Int, message: String) : RuntimeException(message)

class ApiExceptionMapper {
    // Serialize with the compile-time ApiError.serializer() and return a String body. Quarkus does
    // NOT register serializers for types returned only from exception mappers (only resource-method
    // signatures), so a RestResponse<ApiError> here would fail the reflective lookup in native.
    // Encoding explicitly with the generated serializer avoids reflection entirely.
    @ServerExceptionMapper
    fun toResponse(e: ApiException): RestResponse<String> =
        RestResponse.ResponseBuilder
            .create<String>(e.statusCode)
            .entity(Json.encodeToString(ApiError.serializer(), ApiError(e.message ?: "Error", e.statusCode)))
            .type(MediaType.APPLICATION_JSON)
            .build()
}

/**
 * Bearer-token session check. The client logs in once at `/api/auth` and sends the
 * returned JWT as a bearer token on every protected request; [JwtService] verifies the
 * signature and expiry. The raw password is never sent after login.
 */
@ApplicationScoped
class AuthVerifier @Inject constructor(
    private val jwtService: JwtService,
) {
    fun requireAuth(headers: HttpHeaders) {
        val token = extractBearerToken(headers)
        if (token == null || !jwtService.isValid(token)) {
            throw ApiException(Response.Status.UNAUTHORIZED.statusCode, "Invalid or expired session")
        }
    }

    private fun extractBearerToken(headers: HttpHeaders): String? {
        val header = headers.getHeaderString(HttpHeaders.AUTHORIZATION)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val parts = header.split(" ", limit = 2)
        if (parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)) {
            return parts[1].trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
}

/** Aborts the current resource method with the given status + [ApiError] JSON body. */
internal fun apiError(status: Response.Status, message: String): Nothing =
    throw ApiException(status.statusCode, message)
