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
    val type: LampType = LampType.UNKNOWN,
    val inEntertainment: Boolean = false
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

