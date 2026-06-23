@file:OptIn(ExperimentalComposeUiApi::class)

package io.github.commandertvis.huemanager

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.browser.document
import kotlinx.browser.window

fun main() {
    val body = document.body ?: return

    // Check if we're on the OAuth authorization page
    val pathname = window.location.pathname
    val searchParams = window.location.search
    val oauthParams = if (pathname == "/mcp/authorize") {
        parseOAuthParams(searchParams)
    } else {
        null
    }

    ComposeViewport(body) {
        if (oauthParams != null) {
            McpOAuthApp(oauthParams)
        } else {
            App(initialServerUrl = window.location.origin)
        }
    }
}

/**
 * Parse OAuth query parameters from the URL search string.
 */
private fun parseOAuthParams(search: String): OAuthParams? {
    if (search.isEmpty()) return null

    val params = search.removePrefix("?")
        .split("&")
        .associate { param ->
            val parts = param.split("=", limit = 2)
            val key = decodeURIComponent(parts[0])
            val value = if (parts.size > 1) decodeURIComponent(parts[1]) else ""
            key to value
        }

    val redirectUri = params["redirect_uri"] ?: return null

    return OAuthParams(
        redirectUri = redirectUri,
        responseType = params["response_type"] ?: "code",
        state = params["state"],
        clientId = params["client_id"],
        codeChallenge = params["code_challenge"],
        codeChallengeMethod = params["code_challenge_method"],
        scope = params["scope"],
        resource = params["resource"]
    )
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(encoded) => decodeURIComponent(encoded)")
private external fun decodeURIComponent(encoded: String): String

/**
 * OAuth parameters parsed from URL.
 */
data class OAuthParams(
    val redirectUri: String,
    val responseType: String,
    val state: String?,
    val clientId: String?,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
    val scope: String?,
    val resource: String?
)
