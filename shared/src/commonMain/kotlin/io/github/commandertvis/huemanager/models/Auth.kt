package io.github.commandertvis.huemanager.models

import kotlinx.serialization.Serializable

@Serializable
data class AuthRequest(
    val password: String
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val error: String?
) {
    companion object {
        fun success() = AuthResponse(
            success = true,
            error = null
        )

        fun failure(error: String) = AuthResponse(
            success = false,
            error = error
        )
    }
}
