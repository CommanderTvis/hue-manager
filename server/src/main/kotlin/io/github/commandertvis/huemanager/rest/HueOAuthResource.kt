package io.github.commandertvis.huemanager.rest

import io.github.commandertvis.huemanager.api.ApiError
import io.github.commandertvis.huemanager.api.GenericResponse
import io.github.commandertvis.huemanager.config.AppConfig
import io.github.commandertvis.huemanager.hue.HueService
import io.github.commandertvis.huemanager.hue.LampStateCache
import io.github.commandertvis.huemanager.hue.LinkResult
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Philips Hue Remote API OAuth2 endpoints, ported from `apiRoutes`.
 * `authorize` returns JSON; `callback` returns HTML; `link` returns JSON.
 * The redirect URI is taken from `hue.redirect-uri` or reconstructed from the
 * `X-Forwarded-*` headers exactly as the Ktor `resolveHueRedirectUri` did.
 */
@Path("/api/hue")
class HueOAuthResource @Inject constructor(
    private val config: AppConfig,
    private val hueService: HueService,
    private val lampStateCache: LampStateCache,
) {
    private val logger: Logger = Logger.getLogger(HueOAuthResource::class.java)

    @GET
    @Path("/authorize")
    @Produces(MediaType.APPLICATION_JSON)
    fun authorize(@Context headers: HttpHeaders, @Context uriInfo: UriInfo): Response {
        val redirectUri = resolveRedirectUri(headers, uriInfo)
        val state = UUID.randomUUID().toString()

        logger.info("Generating authorization URL for redirectUri: $redirectUri")
        val authUrl = hueService.getAuthorizationUrl(redirectUri, state)
        return if (authUrl != null) {
            logger.info("Generated URL: $authUrl")
            Response.ok(mapOf("authorizationUrl" to authUrl, "state" to state)).build()
        } else {
            logger.warn("Failed to generate authorization URL - HueService returned null")
            Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(
                    ApiError(
                        "OAuth2 not configured. Set HUE_CLIENT_ID, HUE_CLIENT_SECRET, and HUE_APP_ID in .env",
                        503
                    )
                )
                .build()
        }
    }

    @GET
    @Path("/callback")
    @Produces(MediaType.TEXT_HTML)
    suspend fun callback(
        @QueryParam("code") code: String?,
        @QueryParam("state") state: String?,
        @QueryParam("pkce") pkce: String?,
        @QueryParam("error") error: String?,
        @QueryParam("error_description") errorDescription: String?,
        @Context headers: HttpHeaders,
        @Context uriInfo: UriInfo,
    ): Response {
        logger.info("Received OAuth2 callback")
        logger.debug("Callback parameters: code=${code?.take(10)}..., state=${state?.take(8)}..., pkce=$pkce")

        if (error != null) {
            logger.error("OAuth2 authorization failed: $error - $errorDescription")
            return html(
                Response.Status.BAD_REQUEST,
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Hue Authorization Failed</title></head>
                    <body>
                        <h1>Authorization Failed</h1>
                        <p>Error: $error</p>
                        ${errorDescription?.let { "<p>Description: $it</p>" } ?: ""}
                        <p><a href="/api/hue/authorize">Try Again</a></p>
                    </body>
                    </html>
                """.trimIndent()
            )
        }

        if (code == null) {
            logger.error("OAuth2 callback missing authorization code")
            return html(
                Response.Status.BAD_REQUEST,
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Hue Authorization Failed</title></head>
                    <body>
                        <h1>Authorization Failed</h1>
                        <p>Missing authorization code in callback</p>
                        <p><a href="/api/hue/authorize">Try Again</a></p>
                    </body>
                    </html>
                """.trimIndent()
            )
        }

        if (pkce != null) {
            logger.debug("PKCE extension used: $pkce")
        }

        val redirectUri = resolveRedirectUri(headers, uriInfo)
        logger.debug("Using redirect_uri for token exchange: $redirectUri")

        return if (hueService.handleOAuthCallback(code, redirectUri)) {
            logger.info("Successfully exchanged authorization code for tokens")
            html(
                Response.Status.OK,
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Hue Authorization</title></head>
                    <body>
                        <h1>Authorization Successful!</h1>
                        <p>Now press the link button on your Hue Bridge, then click the button below.</p>
                        <button onclick="linkBridge()">Complete Setup</button>
                        <p id="status"></p>
                        <script>
                            async function linkBridge() {
                                document.getElementById('status').textContent = 'Linking...';
                                const response = await fetch('/api/hue/link', { method: 'POST' });
                                const result = await response.json();
                                if (result.success) {
                                    document.getElementById('status').textContent = 'Success! You can close this window.';
                                } else {
                                    document.getElementById('status').textContent = 'Error: ' + result.message;
                                }
                            }
                        </script>
                    </body>
                    </html>
                """.trimIndent()
            )
        } else {
            logger.error("Failed to exchange authorization code for tokens")
            html(
                Response.Status.INTERNAL_SERVER_ERROR,
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Hue Authorization Failed</title></head>
                    <body>
                        <h1>Token Exchange Failed</h1>
                        <p>Failed to exchange authorization code for access tokens.</p>
                        <p>Check server logs for details.</p>
                        <p><a href="/api/hue/authorize">Try Again</a></p>
                    </body>
                    </html>
                """.trimIndent()
            )
        }
    }

    @POST
    @Path("/link")
    @Produces(MediaType.APPLICATION_JSON)
    suspend fun link(): GenericResponse = when (val result = hueService.linkRemoteBridge()) {
        is LinkResult.Success -> {
            lampStateCache.forceRefresh()
            val lamps = lampStateCache.getLights()
            lampStateCache.startRefreshing()
            GenericResponse(success = true, message = "Linked! Found ${lamps.size} lamps")
        }

        is LinkResult.Error -> GenericResponse(success = false, message = result.message)

        LinkResult.LinkButtonNotPressed ->
            GenericResponse(success = false, message = "Press the link button on your Hue Bridge first")
    }

    private fun html(status: Response.Status, body: String): Response =
        Response.status(status).type(MediaType.TEXT_HTML).entity(body).build()

    /**
     * Ported verbatim from Ktor `ApplicationCall.resolveHueRedirectUri`: prefer the
     * configured URI, otherwise reconstruct from `X-Forwarded-*` headers / request URI.
     */
    private fun resolveRedirectUri(headers: HttpHeaders, uriInfo: UriInfo): String {
        config.redirectUri().orElse(null)?.takeIf { it.isNotBlank() }?.let { return it }

        val requestUri = uriInfo.requestUri
        val forwardedProto = headers.getHeaderString("X-Forwarded-Proto")
        val forwardedHost = headers.getHeaderString("X-Forwarded-Host")
        val forwardedPort = headers.getHeaderString("X-Forwarded-Port")

        val requestPort = if (requestUri.port == -1) {
            if (requestUri.scheme == "https") 443 else 80
        } else requestUri.port

        val scheme = forwardedProto ?: if (requestPort == 443) "https" else "http"
        val host = forwardedHost ?: requestUri.host
        val port = forwardedPort ?: when {
            forwardedProto != null && scheme == "https" -> "443"
            forwardedProto != null && scheme == "http" -> "80"
            else -> requestPort.toString()
        }

        val hostWithPort = if (":" in host) {
            host
        } else {
            val isDefaultPort = (scheme == "http" && port == "80") || (scheme == "https" && port == "443")
            if (isDefaultPort) host else "$host:$port"
        }

        return "$scheme://$hostWithPort/api/hue/callback"
    }
}
