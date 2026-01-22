package io.github.commandertvis.huemanager.storage

/**
 * Platform-specific persistent storage for app settings.
 */
interface PlatformStorage {
    // Server URL
    fun getServerUrl(): String?
    fun setServerUrl(url: String)
    fun clearServerUrl()

    // Authentication
    fun getPassword(): String?
    fun setPassword(password: String)
    fun clearPassword()
}

/**
 * Get the platform-specific PlatformStorage instance.
 */
expect val platformStorage: PlatformStorage
