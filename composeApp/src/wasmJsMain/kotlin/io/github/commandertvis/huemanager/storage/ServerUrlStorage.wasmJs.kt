package io.github.commandertvis.huemanager.storage

import kotlinx.browser.localStorage

private class WasmJsServerUrlStorage : ServerUrlStorage {
    override fun getServerUrl(): String? = localStorage.getItem(KEY_SERVER_URL)?.takeIf { it.isNotEmpty() }
    
    override fun setServerUrl(url: String) {
        localStorage.setItem(KEY_SERVER_URL, url)
    }
    
    override fun clearServerUrl() {
        localStorage.removeItem(KEY_SERVER_URL)
    }
    
    companion object {
        private const val KEY_SERVER_URL = "server_url"
    }
}

actual fun createServerUrlStorage(): ServerUrlStorage = WasmJsServerUrlStorage()
