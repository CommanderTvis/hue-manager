package io.github.commandertvis.huemanager.auth

import io.github.commandertvis.huemanager.storage.PlatformStorage
import io.github.commandertvis.huemanager.storage.platformStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthStorage(
    private val storage: PlatformStorage = platformStorage
) {
    private val _token = MutableStateFlow(storage.getAuthToken())
    val token: StateFlow<String?> = _token.asStateFlow()

    val isLoggedIn: Boolean
        get() = _token.value != null

    fun setToken(token: String?) {
        _token.value = token
        if (token != null) {
            storage.setAuthToken(token)
        } else {
            storage.clearAuthToken()
        }
    }

    fun clearToken() {
        _token.value = null
        storage.clearAuthToken()
    }
}
