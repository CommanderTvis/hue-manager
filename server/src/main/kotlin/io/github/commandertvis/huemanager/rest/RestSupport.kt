package io.github.commandertvis.huemanager.rest

import io.github.commandertvis.huemanager.api.ApiError
import io.github.commandertvis.huemanager.auth.JwtService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response

/**
 * Bearer-token session check. The client logs in once at `/api/auth` and sends the
 * returned JWT as a bearer token on every protected request; [JwtService] verifies the
 * signature and expiry. The raw password is never sent after login.
 *
 * On failure it throws a 401 `WebApplicationException` carrying an [ApiError] body,
 * which aborts the JAX-RS resource method exactly like the old early-return did.
 */
@ApplicationScoped
class AuthVerifier @Inject constructor(
    private val jwtService: JwtService,
) {
    fun requireAuth(headers: HttpHeaders) {
        val token = extractBearerToken(headers)
        if (token == null || !jwtService.isValid(token)) {
            throw WebApplicationException(
                Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiError("Invalid or expired session", 401))
                    .build()
            )
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

/** Throws a JAX-RS exception that maps to the given status + [ApiError] body. */
internal fun apiError(status: Response.Status, message: String): Nothing =
    throw WebApplicationException(
        Response.status(status).entity(ApiError(message, status.statusCode)).build()
    )
