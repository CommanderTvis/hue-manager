package io.github.commandertvis.huemanager.config

import io.github.commandertvis.huemanager.persistence.SettingsStore
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import kotlinx.serialization.Serializable
import org.jboss.logging.Logger

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double
)

/**
 * Holds the live Philips Hue OAuth2 credentials and the mutable tokens.
 *
 * The static credentials (client id / secret / app id) come from [AppConfig]. The access/refresh
 * tokens are persisted durably in the SQLite [SettingsStore] (on the `hue-data` volume) so they
 * survive restarts and redeploys — essential because Philips rotates the refresh token on every
 * refresh, so an ephemeral copy goes stale and forces re-authorization. On first run the *initial*
 * tokens are seeded from [AppConfig] (the `.env` `HUE_*_TOKEN` values) and copied into the store.
 *
 * This is the single, framework-friendly injection point the Hue REST client uses to read and
 * update tokens — replacing the old `Config` data class threaded through constructors.
 */
@ApplicationScoped
class TokenStore @Inject constructor(
    private val config: AppConfig,
    private val settings: SettingsStore,
) {
    private val logger = Logger.getLogger(TokenStore::class.java)

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
        // Durable store wins; fall back to the .env-seeded config on first run, copying it into the
        // store so it persists thereafter.
        _accessToken = load(KEY_ACCESS) { config.accessToken().orElse(null) }
        _refreshToken = load(KEY_REFRESH) { config.refreshToken().orElse(null) }
        _username = load(KEY_USERNAME) { config.username().orElse(null) }
        logger.info("Hue tokens seeded (access=${_accessToken != null}, refresh=${_refreshToken != null})")
    }

    private fun load(key: String, fallback: () -> String?): String? {
        settings.get(key)?.takeIf { it.isNotBlank() }?.let { return it }
        val seeded = fallback()?.takeIf { it.isNotBlank() } ?: return null
        settings.put(key, seeded)
        return seeded
    }

    /** Update access/refresh tokens (and optionally username) and persist them durably. */
    @Synchronized
    fun updateTokens(accessToken: String, refreshToken: String, username: String? = null) {
        _accessToken = accessToken
        _refreshToken = refreshToken.takeIf { it.isNotBlank() } ?: _refreshToken
        if (username != null) _username = username

        settings.put(KEY_ACCESS, accessToken)
        _refreshToken?.let { settings.put(KEY_REFRESH, it) }
        _username?.let { settings.put(KEY_USERNAME, it) }
    }

    private companion object {
        const val KEY_ACCESS = "hue_access_token"
        const val KEY_REFRESH = "hue_refresh_token"
        const val KEY_USERNAME = "hue_username"
    }
}
