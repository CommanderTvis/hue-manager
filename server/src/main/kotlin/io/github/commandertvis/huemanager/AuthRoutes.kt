package io.github.commandertvis.huemanager

import io.github.commandertvis.huemanager.api.ApiError
import io.github.commandertvis.huemanager.config.Config
import io.github.commandertvis.huemanager.config.ConfigLoader
import io.github.commandertvis.huemanager.models.AuthRequest
import io.github.commandertvis.huemanager.models.AuthResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(config: Config) {
    post("/api/auth") {
        val request = call.receive<AuthRequest>()
        if (ConfigLoader.verifyPassword(request.password, config.passwordHash)) {
            call.respond(AuthResponse.success())
        } else {
            call.respond(HttpStatusCode.Unauthorized, AuthResponse.failure("Invalid password"))
        }
    }
}

internal fun ApplicationCall.extractBearerToken(): String? {
    val header = request.header(HttpHeaders.Authorization)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = header.split(" ", limit = 2)
    if (parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)) {
        return parts[1].trim().takeIf { it.isNotEmpty() }
    }
    return null
}

internal suspend fun ApplicationCall.requirePassword(config: Config): Boolean {
    val token = extractBearerToken()

    if (token == null || !ConfigLoader.verifyPassword(token, config.passwordHash)) {
        respond(HttpStatusCode.Unauthorized, ApiError("Invalid password", 401))
        return false
    }

    return true
}
