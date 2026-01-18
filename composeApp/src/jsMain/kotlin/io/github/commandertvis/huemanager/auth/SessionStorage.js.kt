package io.github.commandertvis.huemanager.auth

import kotlinx.browser.localStorage

private class JsSessionStorageBackend : SessionStorageBackend {
    override fun getToken(): String? {
        return localStorage.getItem(KEY_SESSION_TOKEN)
    }

    override fun setToken(token: String) {
        localStorage.setItem(KEY_SESSION_TOKEN, token)
    }

    override fun clearToken() {
        localStorage.removeItem(KEY_SESSION_TOKEN)
    }

    companion object {
        private const val KEY_SESSION_TOKEN = "session_token"
    }
}

actual fun createSessionStorageBackend(): SessionStorageBackend = JsSessionStorageBackend()
