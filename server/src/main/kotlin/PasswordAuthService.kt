package io.github.commandertvis.huemanager

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.security.MessageDigest

/**
 * Plain-password verification for the SPA/session auth path. This is intentionally separate from the
 * Hydra/OIDC bearer auth used for `/mcp`: the SPA logs in with the configured password, never OAuth.
 *
 * The password hash is the SHA-256 of the configured password, stored as `hue.password-hash`
 * (env `HUE_PASSWORD_HASH`). Hashing/verification are self-contained here so callers don't depend
 * on config internals.
 */
@ApplicationScoped
class PasswordAuthService(
    @ConfigProperty(name = "hue.password-hash") private val passwordHash: String
) {
    fun verifyPassword(password: String): Boolean = hashPassword(password) == passwordHash

    /**
     * Extracts a Bearer token from an `Authorization` header value, or null if absent/malformed.
     */
    fun extractBearerToken(authorizationHeader: String?): String? {
        val header = authorizationHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = header.split(" ", limit = 2)
        if (parts.size == 2 && parts[0].equals("Bearer", ignoreCase = true)) {
            return parts[1].trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    companion object {
        fun hashPassword(password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
