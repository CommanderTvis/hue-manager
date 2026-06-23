package io.github.commandertvis.huemanager.models

import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(
    val password: String
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val error: String?,
    /** Signed JWT issued on successful login; the client sends it as a Bearer token. */
    val token: String? = null
) {
    companion object {
        fun success(token: String) = AuthResponse(
            success = true,
            error = null,
            token = token
        )

        fun failure(error: String) = AuthResponse(
            success = false,
            error = error
        )
    }
}
