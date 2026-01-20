package io.github.commandertvis.huemanager

interface Platform {
    val name: String
    val isWeb: Boolean
    fun openUrl(url: String)
    fun copyToClipboard(text: String)
}

expect fun getPlatform(): Platform