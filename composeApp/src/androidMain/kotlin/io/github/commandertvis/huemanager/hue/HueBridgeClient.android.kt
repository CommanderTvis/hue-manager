package io.github.commandertvis.huemanager.hue

import io.github.commandertvis.huemanager.network.configureJson
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import java.util.concurrent.TimeUnit

actual fun createHueBridgeHttpClient(): HttpClient = HttpClient(OkHttp) {
    configureJson()
    install(HttpTimeout) {
        requestTimeoutMillis = 5000
        connectTimeoutMillis = 5000
        socketTimeoutMillis = 5000
    }
    engine {
        config {
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(5, TimeUnit.SECONDS)
            writeTimeout(5, TimeUnit.SECONDS)
        }
    }
}
