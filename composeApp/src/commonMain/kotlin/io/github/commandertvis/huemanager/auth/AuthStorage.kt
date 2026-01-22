package io.github.commandertvis.huemanager.auth

import io.github.commandertvis.huemanager.storage.PlatformStorage
import io.github.commandertvis.huemanager.storage.platformStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthStorage(
    private val storage: PlatformStorage = platformStorage
) {
    private val _password = MutableStateFlow(storage.getPassword())
    val password: StateFlow<String?> = _password.asStateFlow()

    val isLoggedIn: Boolean
        get() = _password.value != null

    fun setPassword(password: String?) {
        _password.value = password
        if (password != null) {
            storage.setPassword(password)
        } else {
            storage.clearPassword()
        }
    }

    fun clearPassword() {
        _password.value = null
        storage.clearPassword()
    }
}
