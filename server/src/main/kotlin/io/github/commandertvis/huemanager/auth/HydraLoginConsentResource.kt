package io.github.commandertvis.huemanager.auth

import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.logging.Logger

/**
 * Login + consent provider that Ory Hydra redirects the browser to. This is the only OAuth code we
 * own: Hydra owns tokens / DCR / discovery, and delegates the user-facing login & consent to us so
 * we keep the existing "plain password asked by our frontend" UX.
 *
 * Flow:
 * 1. Hydra redirects to `GET /login?login_challenge=...` → we show a password form.
 * 2. `POST /login` verifies the password and accepts the login challenge → Hydra redirect.
 * 3. Hydra redirects to `GET /consent?consent_challenge=...` → single-user system, auto-accept the
 *    requested scopes → Hydra redirect.
 */
@Path("/")
class HydraLoginConsentResource @Inject constructor(
    @RestClient private val hydra: HydraAdminClient,
    private val passwords: PasswordAuthenticator
) {
    private val logger = Logger.getLogger(HydraLoginConsentResource::class.java)

    private companion object {
        // Single-user system: a fixed subject identifies the home owner across login/consent.
        const val SUBJECT = "hue-manager-owner"
    }

    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    fun loginForm(@QueryParam("login_challenge") challenge: String?): Response {
        if (challenge.isNullOrBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("login_challenge is required").build()
        }

        // If Hydra can skip (an authenticated session already exists), accept immediately.
        val request = runCatching { hydra.getLoginRequest(challenge) }.getOrElse {
            logger.warn("Hydra login challenge lookup failed", it)
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid login_challenge").build()
        }
        if (request.skip) {
            val redirect = hydra.acceptLoginRequest(challenge, HydraAcceptLogin(subject = request.subject))
            return seeOther(redirect.redirect_to)
        }

        return Response.ok(renderLoginPage(challenge, errorMessage = null)).build()
    }

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun loginSubmit(
        @FormParam("login_challenge") challenge: String?,
        @FormParam("password") password: String?
    ): Response {
        if (challenge.isNullOrBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("login_challenge is required").build()
        }

        if (password == null || !passwords.verify(password)) {
            logger.warn("Hydra login rejected: invalid password")
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity(renderLoginPage(challenge, errorMessage = "Invalid password"))
                .build()
        }

        val redirect = hydra.acceptLoginRequest(
            challenge,
            HydraAcceptLogin(subject = SUBJECT, remember = false, remember_for = 0)
        )
        logger.info("Hydra login accepted")
        return seeOther(redirect.redirect_to)
    }

    @GET
    @Path("/consent")
    fun consent(@QueryParam("consent_challenge") challenge: String?): Response {
        if (challenge.isNullOrBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("consent_challenge is required").build()
        }

        // Single-user system: the owner has already authenticated via the login step, so grant the
        // requested scopes/audiences without a separate consent UI.
        val request = runCatching { hydra.getConsentRequest(challenge) }.getOrElse {
            logger.warn("Hydra consent challenge lookup failed", it)
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid consent_challenge").build()
        }

        val redirect = hydra.acceptConsentRequest(
            challenge,
            HydraAcceptConsent(
                grant_scope = request.requested_scope,
                grant_access_token_audience = request.requested_access_token_audience,
                remember = true,
                remember_for = 3600
            )
        )
        logger.infof("Hydra consent accepted scopes=%s", request.requested_scope)
        return seeOther(redirect.redirect_to)
    }

    private fun seeOther(url: String): Response = Response.seeOther(URI.create(url)).build()

    private fun renderLoginPage(challenge: String, errorMessage: String?): String {
        val errorBlock = if (errorMessage != null) {
            """<div class="error">${errorMessage.escapeHtml()}</div>"""
        } else ""
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Sign in - Hue Manager</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 500px; margin: 80px auto; padding: 20px; background: #f5f5f5; }
                    .container { background: white; padding: 40px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                    h1 { margin: 0 0 10px 0; font-size: 24px; color: #333; }
                    p { color: #666; margin: 0 0 30px 0; }
                    input[type="password"] { width: 100%; padding: 12px; border: 1px solid #ddd; border-radius: 4px; font-size: 16px; box-sizing: border-box; margin-bottom: 20px; }
                    button { width: 100%; padding: 12px; background: #007AFF; color: white; border: none; border-radius: 4px; font-size: 16px; font-weight: 600; cursor: pointer; }
                    button:hover { background: #0051D5; }
                    .error { margin-top: 16px; padding: 12px; border-radius: 4px; background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>Sign in to Hue Manager</h1>
                    <p>Enter your password to authorize access.</p>
                    <form method="post" action="/login">
                        <input type="password" name="password" placeholder="Password" autocomplete="current-password" required>
                        <input type="hidden" name="login_challenge" value="${challenge.escapeHtml()}">
                        <button type="submit">Sign in</button>
                    </form>
                    $errorBlock
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}

private fun String.escapeHtml(): String = buildString(length) {
    for (char in this@escapeHtml) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(char)
        }
    }
}
