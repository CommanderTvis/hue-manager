package io.github.commandertvis.huemanager.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.commandertvis.huemanager.storage.getApplicationContext

private class AndroidSessionStorageBackend(context: Context) : SessionStorageBackend {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun getToken(): String? = prefs.getString(KEY_SESSION_TOKEN, null)

    override fun setToken(token: String) {
        prefs.edit { putString(KEY_SESSION_TOKEN, token) }
    }

    override fun clearToken() {
        prefs.edit { remove(KEY_SESSION_TOKEN) }
    }

    companion object {
        private const val PREFS_NAME = "hue_manager_prefs"
        private const val KEY_SESSION_TOKEN = "session_token"
    }
}

actual fun createSessionStorageBackend(): SessionStorageBackend =
    AndroidSessionStorageBackend(getApplicationContext())
