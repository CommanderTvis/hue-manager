package io.github.commandertvis.huemanager.storage

import android.content.Context
import android.content.SharedPreferences

private class AndroidServerUrlStorage(context: Context) : ServerUrlStorage {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    
    override fun getServerUrl(): String? {
        return prefs.getString(KEY_SERVER_URL, null)
    }
    
    override fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }
    
    override fun clearServerUrl() {
        prefs.edit().remove(KEY_SERVER_URL).apply()
    }
    
    companion object {
        private const val PREFS_NAME = "hue_manager_prefs"
        private const val KEY_SERVER_URL = "server_url"
    }
}

private lateinit var applicationContext: Context

fun initializeServerUrlStorage(context: Context) {
    applicationContext = context.applicationContext
}

actual fun createServerUrlStorage(): ServerUrlStorage = AndroidServerUrlStorage(applicationContext)
