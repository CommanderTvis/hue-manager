package io.github.commandertvis.huemanager

import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * The [Json] instance Quarkus REST uses for kotlinx-serialization (both the server endpoints and
 * the Hue REST client). `ignoreUnknownKeys` lets the Hue Remote API client tolerate fields Philips
 * adds over time (e.g. a group's `sensors`, a sensor config's `configured`, a light state's `mode`);
 * without it, deserialization throws and the lamp/group/sensor cache refresh fails.
 */
@Singleton
class JsonProducer {
    @Produces
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
