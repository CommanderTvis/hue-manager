package io.github.commandertvis.huemanager.config

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import io.smallrye.config.WithName
import java.util.Optional

/**
 * Strongly-typed runtime configuration, mapped from `hue.*` properties / env vars.
 *
 * Quarkus maps env vars automatically (e.g. `HUE_CLIENT_ID` -> `hue.client-id`,
 * `HUE_PASSWORD_HASH` -> `hue.password-hash`). Bootstrap config and secrets live here;
 * runtime-mutable settings (schedule, excluded lamps, ...) live in the SQLite [SettingsStore].
 *
 * The OAuth tokens are mutable at runtime (refresh / link), so they are NOT read from this
 * mapping by the live client — they are persisted via [TokenStore]. The values here only seed
 * the initial state at boot.
 */
@ConfigMapping(prefix = "hue")
interface AppConfig {

    /** SHA-256 hex of the SPA/MCP password (env `HUE_PASSWORD_HASH`). */
    @WithName("password-hash")
    fun passwordHash(): String

    /** HMAC secret for signing/verifying SPA session JWTs (env `HUE_JWT_SECRET`, >= 32 bytes). */
    @WithName("jwt-secret")
    fun jwtSecret(): String

    /** Lifetime of an issued session JWT, in days (env `HUE_JWT_TTL_DAYS`, default 30). */
    @WithName("jwt-ttl-days")
    @WithDefault("30")
    fun jwtTtlDays(): Long

    /** Region as `latitude,longitude` (env `HUE_REGION`). */
    @WithName("region")
    fun region(): String

    /** Pseudo-sunset time `HH:MM` (env `HUE_PSEUDO_SUNSET`). */
    @WithName("pseudo-sunset")
    @WithDefault("21:05")
    fun pseudoSunset(): String

    /** IANA timezone id (env `HUE_TIMEZONE`). */
    @WithName("timezone")
    @WithDefault("Europe/Berlin")
    fun timezone(): String

    @WithName("client-id")
    fun clientId(): String

    @WithName("client-secret")
    fun clientSecret(): String

    @WithName("app-id")
    fun appId(): String

    @WithName("redirect-uri")
    fun redirectUri(): Optional<String>

    @WithName("access-token")
    fun accessToken(): Optional<String>

    @WithName("refresh-token")
    fun refreshToken(): Optional<String>

    @WithName("username")
    fun username(): Optional<String>

    /** Parsed [region] as a [GeoLocation]. */
    fun geoLocation(): GeoLocation {
        val parts = region().split(",")
        require(parts.size == 2) {
            "hue.region must be in format: latitude,longitude (e.g., 55.7558,37.6173)"
        }
        return GeoLocation(parts[0].trim().toDouble(), parts[1].trim().toDouble())
    }
}
