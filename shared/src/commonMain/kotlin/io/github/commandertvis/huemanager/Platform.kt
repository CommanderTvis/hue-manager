package io.github.commandertvis.huemanager

interface Platform {
    val name: String
    val isWeb: Boolean
    fun openUrl(url: String)
}

expect fun getPlatform(): Platform