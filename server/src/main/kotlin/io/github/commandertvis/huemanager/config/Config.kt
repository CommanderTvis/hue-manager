package io.github.commandertvis.huemanager.config

import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.Serializable
import org.jboss.logging.Logger
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double
)

/**
 * Holds the live Philips Hue OAuth2 credentials and the mutable tokens.
 *
 * The static credentials (client id / secret / app id) and the *initial* tokens are seeded from
 * [AppConfig] at startup. Tokens then mutate at runtime on refresh and bridge linking; the new
 * values are written back to the `.env` file so they survive a restart (Quarkus reads `.env`
 * natively on boot, repopulating [AppConfig]).
 *
 * This is the single, framework-friendly injection point the Hue REST client uses to read and
 * update tokens — replacing the old `Config` data class threaded through constructors.
 */
@ApplicationScoped
class TokenStore @Inject constructor(private val config: AppConfig) {
    private val logger = Logger.getLogger(TokenStore::class.java)
    private val envFile = if (Path("/.env").exists()) Path("/.env") else Path(".env")

    val clientId: String get() = config.clientId()
    val clientSecret: String get() = config.clientSecret()
    val appId: String get() = config.appId()

    @Volatile
    private var _accessToken: String? = null

    @Volatile
    private var _refreshToken: String? = null

    @Volatile
    private var _username: String? = null

    val accessToken: String? get() = _accessToken
    val refreshToken: String? get() = _refreshToken
    val username: String? get() = _username

    @PostConstruct
    fun seed() {
        _accessToken = config.accessToken().orElse(null)?.takeIf { it.isNotBlank() }
        _refreshToken = config.refreshToken().orElse(null)?.takeIf { it.isNotBlank() }
        _username = config.username().orElse(null)?.takeIf { it.isNotBlank() }
    }

    /** Update access/refresh tokens (and optionally username) and persist them to `.env`. */
    @Synchronized
    fun updateTokens(accessToken: String, refreshToken: String, username: String? = null) {
        _accessToken = accessToken
        _refreshToken = refreshToken.takeIf { it.isNotBlank() } ?: _refreshToken
        if (username != null) _username = username
        persist(accessToken, refreshToken, username)
    }

    private fun persist(accessToken: String, refreshToken: String, username: String?) {
        val current = if (envFile.exists()) envFile.readText() else ""
        val lines = current.lines().toMutableList()

        fun updateOrAdd(key: String, value: String) {
            val index = lines.indexOfFirst { it.startsWith("$key=") }
            if (index >= 0) lines[index] = "$key=$value" else lines.add("$key=$value")
        }

        updateOrAdd("HUE_ACCESS_TOKEN", accessToken)
        updateOrAdd("HUE_REFRESH_TOKEN", refreshToken)
        if (username != null) updateOrAdd("HUE_USERNAME", username)

        runCatching { envFile.writeText(lines.joinToString("\n")) }
            .onFailure { logger.warn("Failed to persist Hue tokens to .env: ${it.message}") }
    }
}
