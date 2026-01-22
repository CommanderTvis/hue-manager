package io.github.commandertvis.huemanager

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.commandertvis.huemanager.ui.McpOAuthScreen
import kotlinx.browser.document

/**
 * MCP OAuth authorization app entry point.
 * Handles the OAuth authorization flow for MCP clients.
 */
@Composable
fun McpOAuthApp(params: OAuthParams) {
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            McpOAuthScreen(
                redirectUri = params.redirectUri,
                state = params.state,
                responseType = params.responseType,
                clientId = params.clientId,
                codeChallenge = params.codeChallenge,
                codeChallengeMethod = params.codeChallengeMethod,
                onAuthorize = { password ->
                    isLoading = true
                    error = null

                    // Submit via form - simplest and most reliable for OAuth redirects
                    submitViaForm(password, params)
                },
                isLoading = isLoading,
                error = error
            )
        }
    }
}

/**
 * Submit via hidden form to handle redirects properly.
 * This is the most reliable way to handle OAuth redirects across browsers.
 */
private fun submitViaForm(password: String, params: OAuthParams) {
    val form = document.createElement("form") as org.w3c.dom.HTMLFormElement
    form.method = "POST"
    form.action = "/api/mcp/oauth"
    form.style.display = "none"

    fun addField(name: String, value: String) {
        val input = document.createElement("input") as org.w3c.dom.HTMLInputElement
        input.type = "hidden"
        input.name = name
        input.value = value
        form.appendChild(input)
    }

    addField("password", password)
    addField("redirect_uri", params.redirectUri)
    addField("response_type", params.responseType)
    params.state?.let { addField("state", it) }
    params.clientId?.let { addField("client_id", it) }
    params.codeChallenge?.let { addField("code_challenge", it) }
    params.codeChallengeMethod?.let { addField("code_challenge_method", it) }

    document.body?.appendChild(form)
    form.submit()
}
