package io.github.commandertvis.huemanager.auth

import kotlinx.browser.localStorage

private class WasmJsAuthStorageBackend : AuthStorageBackend {
    override fun getPassword(): String? {
        return localStorage.getItem(KEY_AUTH_PASSWORD)
    }

    override fun setPassword(password: String) {
        localStorage.setItem(KEY_AUTH_PASSWORD, password)
    }

    override fun clearPassword() {
        localStorage.removeItem(KEY_AUTH_PASSWORD)
    }

    companion object {
        private const val KEY_AUTH_PASSWORD = "auth_password"
    }
}

actual fun createAuthStorageBackend(): AuthStorageBackend = WasmJsAuthStorageBackend()
