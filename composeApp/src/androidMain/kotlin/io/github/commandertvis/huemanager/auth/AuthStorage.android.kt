package io.github.commandertvis.huemanager.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.commandertvis.huemanager.getAndroidApplicationContext

private class AndroidAuthStorageBackend(context: Context) : AuthStorageBackend {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun getPassword(): String? = prefs.getString(KEY_AUTH_PASSWORD, null)

    override fun setPassword(password: String) {
        prefs.edit { putString(KEY_AUTH_PASSWORD, password) }
    }

    override fun clearPassword() {
        prefs.edit { remove(KEY_AUTH_PASSWORD) }
    }

    companion object {
        private const val PREFS_NAME = "hue_manager_prefs"
        private const val KEY_AUTH_PASSWORD = "auth_password"
    }
}

actual fun createAuthStorageBackend(): AuthStorageBackend =
    AndroidAuthStorageBackend(getAndroidApplicationContext())
