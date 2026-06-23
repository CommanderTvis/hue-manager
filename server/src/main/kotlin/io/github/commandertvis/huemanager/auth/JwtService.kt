package io.github.commandertvis.huemanager.auth

import io.github.commandertvis.huemanager.config.AppConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import org.jose4j.jws.AlgorithmIdentifiers
import org.jose4j.jws.JsonWebSignature
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.consumer.InvalidJwtException
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import org.jose4j.jwa.AlgorithmConstraints
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType
import org.jose4j.keys.HmacKey

/**
 * Issues and verifies the SPA session JWT. The SPA logs in once with the password and
 * receives a signed token (HS256, HMAC over `hue.jwt-secret`); every subsequent request
 * carries that token as a Bearer credential instead of the raw password.
 *
 * This is deliberately separate from the Hydra/OIDC bearer auth on `/mcp`: those tokens
 * are validated by `quarkus-oidc`, ours are validated here explicitly so the two
 * mechanisms never collide.
 */
@ApplicationScoped
class JwtService @Inject constructor(config: AppConfig) {
    private val logger = Logger.getLogger(JwtService::class.java)

    private val secretBytes = config.jwtSecret().toByteArray(Charsets.UTF_8).also {
        require(it.size >= MIN_SECRET_BYTES) {
            "HUE_JWT_SECRET must be at least $MIN_SECRET_BYTES bytes for HS256 (got ${it.size})"
        }
    }
    private val key = HmacKey(secretBytes)
    private val ttlMinutes = config.jwtTtlDays() * 24 * 60

    /** Mints a signed token for the single admin subject. */
    fun issue(): String {
        val claims = JwtClaims().apply {
            issuer = ISSUER
            subject = SUBJECT
            setIssuedAtToNow()
            setExpirationTimeMinutesInTheFuture(ttlMinutes.toFloat())
            setGeneratedJwtId()
        }
        return JsonWebSignature().apply {
            payload = claims.toJson()
            this.key = this@JwtService.key
            algorithmHeaderValue = AlgorithmIdentifiers.HMAC_SHA256
        }.compactSerialization
    }

    /** Returns true if [token] is a valid, unexpired, correctly-signed session token. */
    fun isValid(token: String): Boolean = try {
        consumer.processToClaims(token)
        true
    } catch (e: InvalidJwtException) {
        logger.debug("Rejected session token: ${e.message}")
        false
    }

    private val consumer = JwtConsumerBuilder()
        .setRequireExpirationTime()
        .setRequireSubject()
        .setExpectedIssuer(ISSUER)
        .setVerificationKey(key)
        .setJwsAlgorithmConstraints(AlgorithmConstraints(ConstraintType.PERMIT, AlgorithmIdentifiers.HMAC_SHA256))
        .build()

    private companion object {
        const val ISSUER = "hue-manager"
        const val SUBJECT = "admin"
        const val MIN_SECRET_BYTES = 32
    }
}
