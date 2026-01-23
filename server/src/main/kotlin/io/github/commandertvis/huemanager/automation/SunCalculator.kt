package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.config.GeoLocation
import kotlin.math.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetIn
import kotlinx.datetime.toLocalDateTime

/**
 * Calculates sunrise, sunset, and solar noon times based on geographic location.
 * Uses the NOAA Solar Calculator algorithm.
 */
object SunCalculator {

    data class SunTimes(
        val sunrise: LocalTime,
        val sunset: LocalTime,
        val solarNoon: LocalTime
    )

    /**
     * Calculate sun times for a given date and location.
     * Returns null if the sun doesn't rise or set (polar day/night).
     */
    fun calculateSunTimes(date: LocalDate, location: GeoLocation): SunTimes? {
        val latitude = location.latitude
        val longitude = location.longitude

        // Day of year (1-365/366)
        val dayOfYear = date.dayOfYear

        // Fractional year in radians
        val gamma = 2 * PI / 365 * (dayOfYear - 1)

        // Equation of time (in minutes)
        val eqTime = 229.18 * (
                0.000075 +
                        0.001868 * cos(gamma) -
                        0.032077 * sin(gamma) -
                        0.014615 * cos(2 * gamma) -
                        0.040849 * sin(2 * gamma)
                )

        // Solar declination angle (in radians)
        val decl = 0.006918 -
                0.399912 * cos(gamma) +
                0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) +
                0.000907 * sin(2 * gamma) -
                0.002697 * cos(3 * gamma) +
                0.00148 * sin(3 * gamma)

        // Hour angle for sunrise/sunset
        val latRad = latitude * PI / 180
        val zenith = 90.833 * PI / 180 // Official zenith for sunrise/sunset

        val cosHa = (cos(zenith) / (cos(latRad) * cos(decl))) - (tan(latRad) * tan(decl))

        // Check if sun rises/sets at this location on this day
        if (cosHa < -1 || cosHa > 1) {
            return null // Polar day or polar night
        }

        val ha = acos(cosHa) * 180 / PI // Hour angle in degrees

        // Solar noon in minutes from midnight UTC
        val solarNoonMinutes = 720 - 4 * longitude - eqTime

        // Sunrise and sunset in minutes from midnight UTC
        val sunriseMinutes = solarNoonMinutes - ha * 4
        val sunsetMinutes = solarNoonMinutes + ha * 4

        return SunTimes(
            sunrise = minutesToLocalTime(sunriseMinutes),
            sunset = minutesToLocalTime(sunsetMinutes),
            solarNoon = minutesToLocalTime(solarNoonMinutes)
        )
    }

    /**
     * Calculate sun times for a given instant and timezone.
     */
    fun calculateSunTimes(instant: Instant, location: GeoLocation, timeZone: TimeZone): SunTimes? {
        val localDate = instant.toLocalDateTime(timeZone).date

        // Get UTC sun times
        val utcTimes = calculateSunTimes(localDate, location) ?: return null

        // Convert from UTC to local timezone
        // The calculation gives us UTC times, we need to adjust for timezone offset
        val offsetSeconds = instant.offsetIn(timeZone).totalSeconds
        val offsetMinutes = offsetSeconds / 60

        return SunTimes(
            sunrise = adjustTimeByMinutes(utcTimes.sunrise, offsetMinutes),
            sunset = adjustTimeByMinutes(utcTimes.sunset, offsetMinutes),
            solarNoon = adjustTimeByMinutes(utcTimes.solarNoon, offsetMinutes)
        )
    }

    private fun minutesToLocalTime(minutes: Double): LocalTime {
        val totalMinutes = minutes.roundToInt().mod(1440) // Wrap around midnight
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return LocalTime(hours, mins)
    }

    private fun adjustTimeByMinutes(time: LocalTime, offsetMinutes: Int): LocalTime {
        val totalMinutes = (time.hour * 60 + time.minute + offsetMinutes).mod(1440)
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return LocalTime(hours, mins)
    }

    /**
     * Calculate the sun's position as a fraction of daylight (0 = sunrise, 0.5 = solar noon, 1 = sunset).
     * Returns null if before sunrise or after sunset.
     * Returns a value > 1 if after sunset, < 0 if before sunrise.
     */
    fun getDaylightFraction(currentTime: LocalTime, sunTimes: SunTimes): Double {
        val currentMinutes = currentTime.hour * 60 + currentTime.minute
        val sunriseMinutes = sunTimes.sunrise.hour * 60 + sunTimes.sunrise.minute
        val sunsetMinutes = sunTimes.sunset.hour * 60 + sunTimes.sunset.minute

        val daylightDuration = sunsetMinutes - sunriseMinutes
        if (daylightDuration <= 0) return 0.5 // Edge case

        return (currentMinutes - sunriseMinutes).toDouble() / daylightDuration
    }

    /**
     * Check if the current time is during daylight hours (between sunrise and sunset).
     */
    fun isDaylight(currentTime: LocalTime, sunTimes: SunTimes): Boolean {
        val currentMinutes = currentTime.hour * 60 + currentTime.minute
        val sunriseMinutes = sunTimes.sunrise.hour * 60 + sunTimes.sunrise.minute
        val sunsetMinutes = sunTimes.sunset.hour * 60 + sunTimes.sunset.minute

        return currentMinutes in sunriseMinutes..sunsetMinutes
    }
}
