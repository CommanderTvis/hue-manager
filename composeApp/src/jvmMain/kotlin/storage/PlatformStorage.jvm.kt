package io.github.commandertvis.huemanager.storage

import java.util.prefs.Preferences

private object JvmPlatformStorage : PlatformStorage {
    private val prefs = Preferences.userNodeForPackage(JvmPlatformStorage::class.java)

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_AUTH_TOKEN = "auth_token"

    override fun getServerUrl(): String? = prefs.get(KEY_SERVER_URL, null)

    override fun setServerUrl(url: String) {
        prefs.put(KEY_SERVER_URL, url)
        prefs.flush()
    }

    override fun clearServerUrl() {
        prefs.remove(KEY_SERVER_URL)
        prefs.flush()
    }

    override fun getAuthToken(): String? = prefs.get(KEY_AUTH_TOKEN, null)

    override fun setAuthToken(token: String) {
        prefs.put(KEY_AUTH_TOKEN, token)
        prefs.flush()
    }

    override fun clearAuthToken() {
        prefs.remove(KEY_AUTH_TOKEN)
        prefs.flush()
    }
}

actual val platformStorage: PlatformStorage = JvmPlatformStorage
