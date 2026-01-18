package io.github.commandertvis.huemanager.hue

import io.github.commandertvis.huemanager.network.configureJson
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*

actual fun createHueBridgeHttpClient(): HttpClient = HttpClient(CIO) {
    configureJson()
    install(HttpTimeout) {
        requestTimeoutMillis = 5000
        connectTimeoutMillis = 5000
        socketTimeoutMillis = 5000
    }
}
