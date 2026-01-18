package io.github.commandertvis.huemanager.auth

import java.util.prefs.Preferences

private class JvmSessionStorageBackend : SessionStorageBackend {
    private val prefs = Preferences.userNodeForPackage(JvmSessionStorageBackend::class.java)

    override fun getToken(): String? = prefs.get(KEY_SESSION_TOKEN, null)

    override fun setToken(token: String) {
        prefs.put(KEY_SESSION_TOKEN, token)
        prefs.flush()
    }

    override fun clearToken() {
        prefs.remove(KEY_SESSION_TOKEN)
        prefs.flush()
    }

    private companion object {
        private const val KEY_SESSION_TOKEN = "session_token"
    }
}

actual fun createSessionStorageBackend(): SessionStorageBackend = JvmSessionStorageBackend()
