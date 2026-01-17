package io.github.commandertvis.huemanager.storage

import java.util.prefs.Preferences

private class JvmServerUrlStorage : ServerUrlStorage {
    private val prefs = Preferences.userNodeForPackage(JvmServerUrlStorage::class.java)
    
    override fun getServerUrl(): String? {
        return prefs.get(KEY_SERVER_URL, null)
    }
    
    override fun setServerUrl(url: String) {
        prefs.put(KEY_SERVER_URL, url)
        prefs.flush()
    }
    
    override fun clearServerUrl() {
        prefs.remove(KEY_SERVER_URL)
        prefs.flush()
    }
    
    companion object {
        private const val KEY_SERVER_URL = "server_url"
    }
}

actual fun createServerUrlStorage(): ServerUrlStorage = JvmServerUrlStorage()
