package io.github.commandertvis.huemanager

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.commandertvis.huemanager.storage.platformStorage
import io.github.commandertvis.huemanager.ui.McpOAuthScreen
import kotlinx.browser.document
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement

/**
 * MCP OAuth authorization app entry point.
 * Handles the OAuth authorization flow for MCP clients.
 */
@Composable
fun McpOAuthApp(params: OAuthParams) {
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var storedPassword by remember { mutableStateOf<String?>(null) }
    var checkedStorage by remember { mutableStateOf(false) }

    // Check if user has stored password
    LaunchedEffect(Unit) {
        storedPassword = platformStorage.getPassword()
        checkedStorage = true
    }

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeContentPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (!checkedStorage) {
                // Still loading
                McpOAuthScreen(
                    redirectUri = params.redirectUri,
                    state = params.state,
                    responseType = params.responseType,
                    clientId = params.clientId,
                    codeChallenge = params.codeChallenge,
                    codeChallengeMethod = params.codeChallengeMethod,
                    onAuthorize = {},
                    isLoading = true,
                    error = null,
                    hasStoredPassword = false
                )
            } else {
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
                        submitViaForm(password, params)
                    },
                    onAuthorizeWithStoredPassword = {
                        storedPassword?.let { pwd ->
                            isLoading = true
                            error = null
                            submitViaForm(pwd, params)
                        }
                    },
                    isLoading = isLoading,
                    error = error,
                    hasStoredPassword = storedPassword != null
                )
            }
        }
    }
}

/**
 * Submit via hidden form to handle redirects properly.
 * This is the most reliable way to handle OAuth redirects across browsers.
 */
private fun submitViaForm(password: String, params: OAuthParams) {
    val form = (document.createElement("form") as HTMLFormElement).apply {
        method = "POST"
        action = "/mcp/authorize"
        style.display = "none"
    }

    fun addField(n: String, v: String) {
        form.appendChild((document.createElement("input") as HTMLInputElement).apply {
            type = "hidden"
            name = n
            value = v
        })
    }

    addField("password", password)
    addField("redirect_uri", params.redirectUri)
    addField("response_type", params.responseType)
    params.state?.let { addField("state", it) }
    params.clientId?.let { addField("client_id", it) }
    params.codeChallenge?.let { addField("code_challenge", it) }
    params.codeChallengeMethod?.let { addField("code_challenge_method", it) }
    params.scope?.let { addField("scope", it) }
    params.resource?.let { addField("resource", it) }

    document.body?.appendChild(form)
    form.submit()
}
