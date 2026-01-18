package io.github.commandertvis.huemanager.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.Serializable
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class Config(
    val password: String,
    val region: GeoLocation,
    val pseudoSunset: String,
    val timezone: String,
    val keystorePassword: String?,
    val hueUsername: String?,
    // Philips Hue Remote API (OAuth2)
    val hueClientId: String?,
    val hueClientSecret: String?,
    val hueAppId: String?,
    val hueRedirectUri: String?,
    val hueAccessToken: String?,
    val hueRefreshToken: String?
)

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double
)

object ConfigLoader {
    private val envFile = Path(".env")
    private var dotenv: Dotenv? = null

    fun load(): Config {
        dotenv = dotenv {
            ignoreIfMissing = true
        }

        val env = dotenv!!

        val password = env["PASSWORD"]
            ?: throw IllegalStateException("PASSWORD is required in .env")

        val regionStr = env["REGION"]
            ?: throw IllegalStateException("REGION is required in .env (format: latitude,longitude)")

        val region = parseRegion(regionStr)

        val pseudoSunset = env["PSEUDO_SUNSET"] ?: "21:05"
        val timezone = env["TIMEZONE"] ?: "Europe/Berlin"
        val keystorePassword = env["KEYSTORE_PASSWORD"]

        val hueUsername = env["HUE_USERNAME"]?.takeIf { it.isNotBlank() }

        val hueClientId = env["HUE_CLIENT_ID"]?.trim()?.takeIf { it.isNotBlank() }
        val hueClientSecret = env["HUE_CLIENT_SECRET"]?.trim()?.takeIf { it.isNotBlank() }
        val hueAppId = env["HUE_APP_ID"]?.trim()?.takeIf { it.isNotBlank() }
        val hueRedirectUri = env["HUE_REDIRECT_URI"]?.trim()?.takeIf { it.isNotBlank() }
        val hueAccessToken = env["HUE_ACCESS_TOKEN"]?.trim()?.takeIf { it.isNotBlank() }
        val hueRefreshToken = env["HUE_REFRESH_TOKEN"]?.trim()?.takeIf { it.isNotBlank() }

        return Config(
            password = password,
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
            hueRefreshToken = hueRefreshToken
        )
    }

    fun updateHueTokens(accessToken: String, refreshToken: String, username: String? = null) {
        val currentContent = if (envFile.exists()) {
            envFile.readText()
        } else {
            Path(".env.example").readText()
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
}
