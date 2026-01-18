package io.github.commandertvis.huemanager.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic session storage interface.
 * Provides persistent storage for session tokens across app restarts.
 */
interface SessionStorageBackend {
    fun getToken(): String?
    fun setToken(token: String)
    fun clearToken()
}

/**
 * Create a platform-specific SessionStorageBackend instance.
 */
expect fun createSessionStorageBackend(): SessionStorageBackend

class SessionStorage(
    private val backend: SessionStorageBackend = createSessionStorageBackend()
) {
    private val _token = MutableStateFlow<String?>(backend.getToken())
    val token: StateFlow<String?> = _token.asStateFlow()

    val isLoggedIn: Boolean
        get() = _token.value != null

    fun setToken(token: String?) {
        _token.value = token
        if (token != null) {
            backend.setToken(token)
        } else {
            backend.clearToken()
        }
    }

    fun clearToken() {
        _token.value = null
        backend.clearToken()
    }
}
