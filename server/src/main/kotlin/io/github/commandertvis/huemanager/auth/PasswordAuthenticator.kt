package io.github.commandertvis.huemanager.auth

import io.github.commandertvis.huemanager.config.AppConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.security.MessageDigest

/**
 * Verifies the SPA/MCP password against the configured hash. Reuses the existing SHA-256 scheme
 * (`PASSWORD_HASH` in `.env`) so previously stored hashes keep working. This is the single shared
 * password check used by both the SPA auth (Phase 2) and the Hydra login provider (Phase 4).
 */
@ApplicationScoped
class PasswordAuthenticator @Inject constructor(
    private val config: AppConfig
) {
    fun verify(password: String): Boolean =
        constantTimeEquals(hash(password), config.passwordHash())

    private fun hash(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(password.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }
}
