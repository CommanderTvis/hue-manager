package io.github.commandertvis.huemanager.auth

import io.github.commandertvis.huemanager.config.Config
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.security.SecureRandom
import java.util.*
import kotlin.time.Duration.Companion.days

data class UserSession(
    val token: String,
    val createdAt: Instant,
    val expiresAt: Instant
)

class SessionManager(private val config: Config) {
    private val sessions = mutableMapOf<String, UserSession>()
    private val sessionDuration = 7.days

    fun authenticate(password: String): UserSession? {
        if (password != config.password) {
            return null
        }

        val token = generateToken()
        val now = Clock.System.now()
        val session = UserSession(
            token = token,
            createdAt = now,
            expiresAt = now.plus(sessionDuration)
        )

        sessions[token] = session
        cleanExpiredSessions()

        return session
    }

    fun validateSession(token: String?): Boolean {
        if (token == null) return false

        val session = sessions[token] ?: return false
        val now = Clock.System.now()

        if (now > session.expiresAt) {
            sessions.remove(token)
            return false
        }

        return true
    }

    fun invalidateSession(token: String) {
        sessions.remove(token)
    }

    private fun generateToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun cleanExpiredSessions() {
        val now = Clock.System.now()
        sessions.entries.removeIf { it.value.expiresAt < now }
    }
}
