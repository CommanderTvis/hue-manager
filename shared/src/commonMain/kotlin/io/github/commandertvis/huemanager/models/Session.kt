package io.github.commandertvis.huemanager.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val token: String,
    val createdAt: Instant,
    val expiresAt: Instant
)

@Serializable
data class LoginRequest(
    val password: String
)

@Serializable
data class LoginResponse(
    val success: Boolean,
    val token: String?,
    val error: String?
) {
    companion object {
        fun success(token: String) = LoginResponse(
            success = true,
            token = token,
            error = null
        )

        fun failure(error: String) = LoginResponse(
            success = false,
            token = null,
            error = error
        )
    }
}
