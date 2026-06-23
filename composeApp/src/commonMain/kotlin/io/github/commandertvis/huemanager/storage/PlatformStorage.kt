package io.github.commandertvis.huemanager.storage

/**
 * Platform-specific persistent storage for app settings.
 */
interface PlatformStorage {
    // Server URL
    fun getServerUrl(): String?
    fun setServerUrl(url: String)
    fun clearServerUrl()

    // Authentication — persisted session JWT (not the password)
    fun getAuthToken(): String?
    fun setAuthToken(token: String)
    fun clearAuthToken()
}

/**
 * Get the platform-specific PlatformStorage instance.
 */
expect val platformStorage: PlatformStorage
