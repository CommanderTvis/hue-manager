package io.github.commandertvis.huemanager.network

import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

private val JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Configure JSON content negotiation for an HTTP client.
 */
fun HttpClientConfig<*>.configureJson() {
    install(ContentNegotiation) {
        json(JSON)
    }
}
