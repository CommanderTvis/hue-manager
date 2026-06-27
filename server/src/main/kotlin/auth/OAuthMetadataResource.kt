package io.github.commandertvis.huemanager.auth

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * RFC 8414 OAuth 2.0 Authorization Server Metadata for MCP clients (claude.ai).
 *
 * claude.ai requires the authorization server to live on the SAME origin as the `/mcp` endpoint, so
 * Caddy path-routes the actual OAuth endpoints (the `/oauth2/` paths and `/.well-known/jwks.json`) to
 * Ory Hydra under this app's public domain. Hydra advertises neither a `registration_endpoint` nor an
 * `/.well-known/oauth-authorization-server` document, so the app publishes the metadata here, with
 * every endpoint anchored at the public origin ([publicUrl]).
 *
 * `quarkus-oidc` (resource-metadata) already serves the RFC 9728 `/.well-known/oauth-protected-resource`
 * document and the `WWW-Authenticate: Bearer resource_metadata="…"` challenge on `/mcp`; that document
 * points here. Both the OAuth (`oauth-authorization-server`) and OIDC (`openid-configuration`) paths
 * return the same document so a client probing either discovers the registration endpoint.
 */
@Path("/.well-known")
class OAuthMetadataResource(
    @ConfigProperty(name = "app.public-url") private val publicUrl: String,
) {
    private val base = publicUrl.trimEnd('/')

    @GET
    @Path("/oauth-authorization-server")
    @Produces(MediaType.APPLICATION_JSON)
    fun authorizationServerMetadata(): OAuthServerMetadata = metadata

    @GET
    @Path("/openid-configuration")
    @Produces(MediaType.APPLICATION_JSON)
    fun openidConfiguration(): OAuthServerMetadata = metadata

    private val metadata = OAuthServerMetadata(
        issuer = base,
        authorizationEndpoint = "$base/oauth2/auth",
        tokenEndpoint = "$base/oauth2/token",
        registrationEndpoint = "$base/oauth2/register",
        revocationEndpoint = "$base/oauth2/revoke",
        jwksUri = "$base/.well-known/jwks.json",
        responseTypesSupported = listOf("code"),
        grantTypesSupported = listOf("authorization_code", "refresh_token"),
        codeChallengeMethodsSupported = listOf("S256"),
        tokenEndpointAuthMethodsSupported = listOf("none", "client_secret_basic", "client_secret_post"),
        scopesSupported = listOf("openid", "offline", "offline_access"),
    )
}

@Serializable
data class OAuthServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String,
    @SerialName("revocation_endpoint") val revocationEndpoint: String,
    @SerialName("jwks_uri") val jwksUri: String,
    @SerialName("response_types_supported") val responseTypesSupported: List<String>,
    @SerialName("grant_types_supported") val grantTypesSupported: List<String>,
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>,
    @SerialName("token_endpoint_auth_methods_supported") val tokenEndpointAuthMethodsSupported: List<String>,
    @SerialName("scopes_supported") val scopesSupported: List<String>,
)
