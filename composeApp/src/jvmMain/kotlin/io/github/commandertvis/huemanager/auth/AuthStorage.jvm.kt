package io.github.commandertvis.huemanager.auth

import java.util.prefs.Preferences

private class JvmAuthStorageBackend : AuthStorageBackend {
    private val prefs = Preferences.userNodeForPackage(JvmAuthStorageBackend::class.java)

    override fun getPassword(): String? = prefs.get(KEY_AUTH_PASSWORD, null)

    override fun setPassword(password: String) {
        prefs.put(KEY_AUTH_PASSWORD, password)
        prefs.flush()
    }

    override fun clearPassword() {
        prefs.remove(KEY_AUTH_PASSWORD)
        prefs.flush()
    }

    private companion object {
        private const val KEY_AUTH_PASSWORD = "auth_password"
    }
}

actual fun createAuthStorageBackend(): AuthStorageBackend = JvmAuthStorageBackend()
