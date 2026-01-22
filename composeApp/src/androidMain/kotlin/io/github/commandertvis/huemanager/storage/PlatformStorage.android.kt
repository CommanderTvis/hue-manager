package io.github.commandertvis.huemanager.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.commandertvis.huemanager.getAndroidApplicationContext
import io.github.commandertvis.huemanager.initializeAndroidContext

private object AndroidPlatformStorage : PlatformStorage {
    private const val PREFS_NAME = "hue_manager_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_AUTH_PASSWORD = "auth_password"

    private val prefs: SharedPreferences by lazy {
        getAndroidApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun getServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)

    override fun setServerUrl(url: String) {
        prefs.edit { putString(KEY_SERVER_URL, url) }
    }

    override fun clearServerUrl() {
        prefs.edit { remove(KEY_SERVER_URL) }
    }

    override fun getPassword(): String? = prefs.getString(KEY_AUTH_PASSWORD, null)

    override fun setPassword(password: String) {
        prefs.edit { putString(KEY_AUTH_PASSWORD, password) }
    }

    override fun clearPassword() {
        prefs.edit { remove(KEY_AUTH_PASSWORD) }
    }
}

fun initializePlatformStorage(context: Context) {
    initializeAndroidContext(context)
}

actual val platformStorage: PlatformStorage = AndroidPlatformStorage
