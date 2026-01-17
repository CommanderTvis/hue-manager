package io.github.commandertvis.huemanager

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform