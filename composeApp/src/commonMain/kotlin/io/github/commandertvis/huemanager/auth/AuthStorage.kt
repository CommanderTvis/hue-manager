package io.github.commandertvis.huemanager.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic authentication storage interface.
 * Provides persistent storage for the password across app restarts.
 */
interface AuthStorageBackend {
    fun getPassword(): String?
    fun setPassword(password: String)
    fun clearPassword()
}

/**
 * Create a platform-specific AuthStorageBackend instance.
 */
expect fun createAuthStorageBackend(): AuthStorageBackend

class AuthStorage(
    private val backend: AuthStorageBackend = createAuthStorageBackend()
) {
    private val _password = MutableStateFlow(backend.getPassword())
    val password: StateFlow<String?> = _password.asStateFlow()

    val isLoggedIn: Boolean
        get() = _password.value != null

    fun setPassword(password: String?) {
        _password.value = password
        if (password != null) {
            backend.setPassword(password)
        } else {
            backend.clearPassword()
        }
    }

    fun clearPassword() {
        _password.value = null
        backend.clearPassword()
    }
}
