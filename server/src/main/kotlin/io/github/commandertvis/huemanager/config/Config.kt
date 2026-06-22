package io.github.commandertvis.huemanager.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class Config(
    val passwordHash: String,
    val region: GeoLocation,
    val pseudoSunset: String,
    val timezone: String,
    val keystorePassword: String?,
    val hueUsername: String?,
    val hueClientId: String,
    val hueClientSecret: String,
    val hueAppId: String,
    val hueRedirectUri: String?,
    val hueAccessToken: String?,
    val hueRefreshToken: String?,
    val databasePath: String
)

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double
)

object ConfigLoader {
    private val envFile = if (Path("/.env").exists()) Path("/.env") else Path(".env")
    private var dotenv: Dotenv? = null

    fun load(): Config {
        dotenv = dotenv {
            ignoreIfMissing = true
        }

        val env = dotenv!!

        // Handle password hashing migration
        val passwordHash = getOrMigratePasswordHash(env)

        val regionStr = env["REGION"]
            ?: throw IllegalStateException("REGION is required in .env (format: latitude,longitude)")

        val region = parseRegion(regionStr)

        val pseudoSunset = env["PSEUDO_SUNSET"] ?: "21:05"
        val timezone = env["TIMEZONE"] ?: "Europe/Berlin"
        val keystorePassword = env["KEYSTORE_PASSWORD"]

        val hueUsername = env["HUE_USERNAME"]?.takeIf { it.isNotBlank() }

        // OAuth2 credentials - REQUIRED for app operation
        val hueClientId = env["HUE_CLIENT_ID"]?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("HUE_CLIENT_ID is required in .env for OAuth2 authentication")
        val hueClientSecret = env["HUE_CLIENT_SECRET"]?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("HUE_CLIENT_SECRET is required in .env for OAuth2 authentication")
        val hueAppId = env["HUE_APP_ID"]?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("HUE_APP_ID is required in .env for OAuth2 authentication")

        val hueRedirectUri = env["HUE_REDIRECT_URI"]?.trim()?.takeIf { it.isNotBlank() }
        val hueAccessToken = env["HUE_ACCESS_TOKEN"]?.trim()?.takeIf { it.isNotBlank() }
        val hueRefreshToken = env["HUE_REFRESH_TOKEN"]?.trim()?.takeIf { it.isNotBlank() }

        val databasePath = env["DATABASE_PATH"]?.trim()?.takeIf { it.isNotBlank() } ?: "data/hue.db"

        return Config(
            passwordHash = passwordHash,
            region = region,
            pseudoSunset = pseudoSunset,
            timezone = timezone,
            keystorePassword = keystorePassword,
            hueUsername = hueUsername,
            hueClientId = hueClientId,
            hueClientSecret = hueClientSecret,
            hueAppId = hueAppId,
            hueRedirectUri = hueRedirectUri,
            hueAccessToken = hueAccessToken,
            hueRefreshToken = hueRefreshToken,
            databasePath = databasePath
        )
    }

    fun updateHueTokens(accessToken: String, refreshToken: String, username: String? = null) {
        val currentContent = if (envFile.exists()) {
            envFile.readText()
        } else {
            // If .env doesn't exist, create it with empty content
            ""
        }

        val lines = currentContent.lines().toMutableList()

        fun updateOrAdd(key: String, value: String) {
            val index = lines.indexOfFirst { it.startsWith("$key=") }
            if (index >= 0) {
                lines[index] = "$key=$value"
            } else {
                lines.add("$key=$value")
            }
        }

        updateOrAdd("HUE_ACCESS_TOKEN", accessToken)
        updateOrAdd("HUE_REFRESH_TOKEN", refreshToken)
        if (username != null) {
            updateOrAdd("HUE_USERNAME", username)
        }

        envFile.writeText(lines.joinToString("\n"))
    }

    private fun parseRegion(regionStr: String): GeoLocation {
        val parts = regionStr.split(",")
        require(parts.size == 2) {
            "REGION must be in format: latitude,longitude (e.g., 55.7558,37.6173)"
        }
        return GeoLocation(
            latitude = parts[0].trim().toDouble(),
            longitude = parts[1].trim().toDouble()
        )
    }

    /**
     * Gets the password hash, migrating from plaintext PASSWORD if needed.
     * If PASSWORD is set (plaintext), it will be hashed, stored in PASSWORD_HASH,
     * and PASSWORD will be cleared in the .env file.
     */
    private fun getOrMigratePasswordHash(env: Dotenv): String {
        val existingHash = env["PASSWORD_HASH"]?.trim()?.takeIf { it.isNotBlank() }
        val plaintextPassword = env["PASSWORD"]?.trim()?.takeIf { it.isNotBlank() }

        return when {
            // Already have a hash - use it
            existingHash != null -> existingHash

            // Have plaintext password - migrate it
            plaintextPassword != null -> {
                val hash = hashPassword(plaintextPassword)
                migratePasswordToHash(hash)
                println("Password migrated to hash in .env file")
                hash
            }

            // No password at all
            else -> throw IllegalStateException(
                "PASSWORD or PASSWORD_HASH is required in .env"
            )
        }
    }

    /**
     * Hash a password using SHA-256.
     */
    fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify a password against a hash.
     */
    fun verifyPassword(password: String, hash: String): Boolean {
        return hashPassword(password) == hash
    }

    /**
     * Updates .env file to store the hash and clear plaintext password.
     */
    private fun migratePasswordToHash(hash: String) {
        val currentContent = if (envFile.exists()) {
            envFile.readText()
        } else {
            ""
        }

        val lines = currentContent.lines().toMutableList()

        fun updateOrAdd(key: String, value: String) {
            val index = lines.indexOfFirst { it.startsWith("$key=") }
            if (index >= 0) {
                lines[index] = "$key=$value"
            } else {
                lines.add("$key=$value")
            }
        }

        // Clear plaintext password and set hash
        updateOrAdd("PASSWORD", "")
        updateOrAdd("PASSWORD_HASH", hash)

        envFile.writeText(lines.joinToString("\n"))
    }
}
