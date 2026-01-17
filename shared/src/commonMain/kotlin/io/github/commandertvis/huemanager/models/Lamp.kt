package io.github.commandertvis.huemanager.models

import kotlinx.serialization.Serializable

@Serializable
data class Lamp(
    val id: String,
    val name: String,
    val on: Boolean,
    val brightness: Int?,
    val hue: Int?,
    val saturation: Int?,
    val colorTemperature: Int?,
    val colorMode: ColorMode?,
    val reachable: Boolean,
    val type: LampType = LampType.UNKNOWN
)

@Serializable
enum class ColorMode {
    HS,   // Hue and Saturation
    XY,   // CIE color space
    CT;   // Color Temperature

    companion object {
        fun fromString(value: String?): ColorMode? = when (value) {
            "hs" -> HS
            "xy" -> XY
            "ct" -> CT
            else -> null
        }
    }
}

@Serializable
enum class LampType {
    COLOR,           // Full color lamp
    COLOR_TEMPERATURE, // White ambiance (adjustable temperature)
    DIMMABLE,        // Dimmable white only
    ON_OFF,          // Simple on/off
    UNKNOWN;

    companion object {
        fun fromHueType(type: String): LampType = when {
            type.contains("color", ignoreCase = true) -> COLOR
            type.contains("ambiance", ignoreCase = true) -> COLOR_TEMPERATURE
            type.contains("white", ignoreCase = true) -> DIMMABLE
            else -> UNKNOWN
        }
    }
}

@Serializable
data class LampState(
    val on: Boolean? = null,
    val brightness: Int? = null,
    val hue: Int? = null,
    val saturation: Int? = null,
    val colorTemperature: Int? = null,
    val transitionTime: Int? = null  // In deciseconds (1/10 second)
) {
    companion object {
        fun turnOn() = LampState(on = true)
        fun turnOff() = LampState(on = false)

        fun white(brightness: Int = 254) = LampState(
            on = true,
            brightness = brightness,
            colorTemperature = 153  // Coolest white (6500K)
        )

        fun warm(brightness: Int = 254) = LampState(
            on = true,
            brightness = brightness,
            colorTemperature = 500  // Warmest white (2000K)
        )

        fun color(hue: Int, saturation: Int = 254, brightness: Int = 254) = LampState(
            on = true,
            brightness = brightness,
            hue = hue,
            saturation = saturation
        )

        // Orange/amber for evening (approximates #FF5500)
        fun evening(brightness: Int = 254) = LampState(
            on = true,
            brightness = brightness,
            hue = 5000,      // Orange hue
            saturation = 254
        )
    }
}

// Color constants for Hue API (0-65535 range)
object HueColors {
    const val RED = 0
    const val ORANGE = 5000
    const val YELLOW = 10000
    const val GREEN = 25500
    const val CYAN = 35000
    const val BLUE = 46920
    const val PURPLE = 50000
    const val PINK = 56100
}

// Color temperature constants (in Mirek, 153-500)
object ColorTemperatures {
    const val DAYLIGHT = 153   // 6500K - Cool daylight
    const val COOL = 200       // 5000K - Cool white
    const val NEUTRAL = 300    // 3500K - Neutral
    const val WARM = 400       // 2700K - Warm white
    const val CANDLE = 500     // 2000K - Candlelight
}
