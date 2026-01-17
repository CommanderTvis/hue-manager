package io.github.commandertvis.huemanager.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Config(
    val password: String,
    val region: GeoLocation,
    val pseudoSunset: String,
    val timezone: String,
    val keystorePassword: String?,
    val hueBridgeIp: String?,
    val hueUsername: String?
)

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double
)

object ConfigLoader {
    private val envFile = File(".env")
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

        val hueBridgeIp = env["HUE_BRIDGE_IP"]?.takeIf { it.isNotBlank() }
        val hueUsername = env["HUE_USERNAME"]?.takeIf { it.isNotBlank() }

        return Config(
            password = password,
            region = region,
            pseudoSunset = pseudoSunset,
            timezone = timezone,
            keystorePassword = keystorePassword,
            hueBridgeIp = hueBridgeIp,
            hueUsername = hueUsername
        )
    }

    fun updateHueCredentials(bridgeIp: String, username: String) {
        val currentContent = if (envFile.exists()) {
            envFile.readText()
        } else {
            File(".env.example").readText()
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

        updateOrAdd("HUE_BRIDGE_IP", bridgeIp)
        updateOrAdd("HUE_USERNAME", username)

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
