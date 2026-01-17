package io.github.commandertvis.huemanager.storage

/**
 * Platform-specific storage for server URL.
 */
interface ServerUrlStorage {
    /**
     * Get the stored server URL, or null if not set.
     */
    fun getServerUrl(): String?
    
    /**
     * Store the server URL.
     */
    fun setServerUrl(url: String)
    
    /**
     * Clear the stored server URL.
     */
    fun clearServerUrl()
}

/**
 * Create a platform-specific ServerUrlStorage instance.
 */
expect fun createServerUrlStorage(): ServerUrlStorage
