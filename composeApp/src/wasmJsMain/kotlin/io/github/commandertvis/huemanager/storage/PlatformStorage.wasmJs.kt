package io.github.commandertvis.huemanager.storage

import kotlinx.browser.localStorage

private object WasmJsPlatformStorage : PlatformStorage {
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_AUTH_TOKEN = "auth_token"

    override fun getServerUrl(): String? = localStorage.getItem(KEY_SERVER_URL)?.takeIf { it.isNotEmpty() }
    override fun setServerUrl(url: String) = localStorage.setItem(KEY_SERVER_URL, url)
    override fun clearServerUrl() = localStorage.removeItem(KEY_SERVER_URL)

    override fun getAuthToken(): String? = localStorage.getItem(KEY_AUTH_TOKEN)
    override fun setAuthToken(token: String) = localStorage.setItem(KEY_AUTH_TOKEN, token)
    override fun clearAuthToken() = localStorage.removeItem(KEY_AUTH_TOKEN)
}

actual val platformStorage: PlatformStorage = WasmJsPlatformStorage
