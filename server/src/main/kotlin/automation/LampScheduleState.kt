package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.hue.HueLightStateUpdate
import io.github.commandertvis.huemanager.hue.HueLightState
import io.github.commandertvis.huemanager.models.LampSchedule
import kotlinx.datetime.LocalDateTime
import kotlin.math.roundToInt

fun LampSchedule.desiredState(time: LocalDateTime, supportsBrightness: Boolean = true): HueLightStateUpdate {
    val interval = intervals.firstOrNull { it.isActive(time) }
        ?: return HueLightStateUpdate(on = false)
    return HueLightStateUpdate(
        on = true,
        bri = if (supportsBrightness) (interval.brightness * 254.0 / 100).roundToInt().coerceIn(1, 254) else null,
        ct = interval.temperatureKelvin?.let { (1_000_000.0 / it).roundToInt() },
        hue = interval.hue,
        sat = interval.saturation,
    )
}

fun HueLightState.matchesSchedule(target: HueLightStateUpdate): Boolean {
    if (on != target.on) return false
    if (!on) return true
    return (target.bri == null || bri == target.bri) &&
        (target.ct == null || (ct == target.ct && colormode == "ct")) &&
        (target.hue == null || (hue == target.hue && sat == target.sat && colormode == "hs"))
}
