package io.github.commandertvis.huemanager

interface Platform {
    val name: String
    val isWeb: Boolean
}

expect fun getPlatform(): Platform