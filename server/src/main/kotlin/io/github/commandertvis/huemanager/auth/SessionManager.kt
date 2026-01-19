package io.github.commandertvis.huemanager.auth

import io.github.commandertvis.huemanager.config.Config
import kotlin.time.Clock
import kotlin.time.Instant
import java.security.SecureRandom
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class UserSession(
    val token: String,
    val createdAt: Instant,
    val expiresAt: Instant
)

class SessionManager(private val config: Config) {
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)
    private val sessions = mutableMapOf<String, UserSession>()
    private val sessionDuration = 7.days
    private val sessionFile = File("sessions.json")

    init {
        loadSessions()
    }

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
        saveSessions()

        return session
    }

    fun validateSession(token: String?): Boolean {
        if (token == null) return false

        val session = sessions[token] ?: return false
        val now = Clock.System.now()

        if (now > session.expiresAt) {
            sessions.remove(token)
            saveSessions()
            return false
        }

        return true
    }

    fun invalidateSession(token: String) {
        sessions.remove(token)
        saveSessions()
    }

    private fun generateToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun cleanExpiredSessions() {
        val now = Clock.System.now()
        val removed = sessions.entries.removeIf { it.value.expiresAt < now }
        if (removed) {
            saveSessions()
        }
    }

    private fun saveSessions() {
        try {
            val json = Json.encodeToString(sessions.values.toList())
            sessionFile.writeText(json)
            logger.debug("Saved ${sessions.size} sessions to ${sessionFile.absolutePath}")
        } catch (e: Exception) {
            logger.error("Failed to save sessions: ${e.message}")
        }
    }

    private fun loadSessions() {
        if (!sessionFile.exists()) {
            logger.info("No existing sessions file found")
            return
        }

        try {
            val json = sessionFile.readText()
            val loadedSessions = Json.decodeFromString<List<UserSession>>(json)
            val now = Clock.System.now()

            // Only load non-expired sessions
            loadedSessions.forEach { session ->
                if (now <= session.expiresAt) {
                    sessions[session.token] = session
                }
            }

            logger.info("Loaded ${sessions.size} valid sessions from ${sessionFile.absolutePath}")

            // Clean up any expired sessions we loaded
            if (sessions.size < loadedSessions.size) {
                saveSessions()
            }
        } catch (e: Exception) {
            logger.error("Failed to load sessions: ${e.message}")
        }
    }
}
