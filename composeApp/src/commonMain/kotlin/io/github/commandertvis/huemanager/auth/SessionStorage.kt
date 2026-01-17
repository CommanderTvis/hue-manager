package io.github.commandertvis.huemanager.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionStorage {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    val isLoggedIn: Boolean
        get() = _token.value != null

    fun setToken(token: String?) {
        _token.value = token
    }

    fun clearToken() {
        _token.value = null
    }
}
