package io.github.commandertvis.huemanager.storage

import java.util.prefs.Preferences

private object JvmPlatformStorage : PlatformStorage {
    private val prefs = Preferences.userNodeForPackage(JvmPlatformStorage::class.java)

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_AUTH_PASSWORD = "auth_password"

    override fun getServerUrl(): String? = prefs.get(KEY_SERVER_URL, null)

    override fun setServerUrl(url: String) {
        prefs.put(KEY_SERVER_URL, url)
        prefs.flush()
    }

    override fun clearServerUrl() {
        prefs.remove(KEY_SERVER_URL)
        prefs.flush()
    }

    override fun getPassword(): String? = prefs.get(KEY_AUTH_PASSWORD, null)

    override fun setPassword(password: String) {
        prefs.put(KEY_AUTH_PASSWORD, password)
        prefs.flush()
    }

    override fun clearPassword() {
        prefs.remove(KEY_AUTH_PASSWORD)
        prefs.flush()
    }
}

actual val platformStorage: PlatformStorage = JvmPlatformStorage
