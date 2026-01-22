package io.github.commandertvis.huemanager.storage

import kotlinx.browser.localStorage

private object WasmJsPlatformStorage : PlatformStorage {
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_AUTH_PASSWORD = "auth_password"

    override fun getServerUrl(): String? = localStorage.getItem(KEY_SERVER_URL)?.takeIf { it.isNotEmpty() }
    override fun setServerUrl(url: String) = localStorage.setItem(KEY_SERVER_URL, url)
    override fun clearServerUrl() = localStorage.removeItem(KEY_SERVER_URL)

    override fun getPassword(): String? = localStorage.getItem(KEY_AUTH_PASSWORD)
    override fun setPassword(password: String) = localStorage.setItem(KEY_AUTH_PASSWORD, password)
    override fun clearPassword() = localStorage.removeItem(KEY_AUTH_PASSWORD)
}

actual val platformStorage: PlatformStorage = WasmJsPlatformStorage
