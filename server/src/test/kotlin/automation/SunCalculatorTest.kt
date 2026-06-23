package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.config.GeoLocation
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-logic unit tests for the NOAA solar calculator. No Quarkus runtime needed, so this runs as a
 * plain JUnit5 test (`SunCalculator` is a stateless object). Replaces the removed Ktor MCP tests,
 * which were coupled to the now-deleted embedded MCP SDK transport.
 */
class SunCalculatorTest {
    private val berlin = GeoLocation(latitude = 52.52, longitude = 13.405)

    @Test
    fun `summer solstice in Berlin yields an early sunrise and late sunset`() {
        val times = SunCalculator.calculateSunTimes(LocalDate(2026, 6, 21), berlin)
        assertNotNull(times)
        times!!
        // Times are returned in UTC. Berlin is UTC+2 in summer, so local ~05:00 sunrise / ~21:30 sunset.
        assertTrue(times.sunrise.hour in 2..5, "sunrise hour was ${times.sunrise}")
        assertTrue(times.sunset.hour in 18..21, "sunset hour was ${times.sunset}")
        assertTrue(times.sunrise < times.sunset, "sunrise should precede sunset")
    }

    @Test
    fun `polar night above the arctic circle returns null`() {
        val northPoleWinter = SunCalculator.calculateSunTimes(
            LocalDate(2026, 12, 21),
            GeoLocation(latitude = 80.0, longitude = 0.0),
        )
        assertTrue(northPoleWinter == null, "expected null (sun never rises) but got $northPoleWinter")
    }

    @Test
    fun `solar noon falls between sunrise and sunset`() {
        val times = SunCalculator.calculateSunTimes(LocalDate(2026, 3, 20), berlin)
        assertNotNull(times)
        times!!
        assertTrue(times.sunrise < times.solarNoon)
        assertTrue(times.solarNoon < times.sunset)
    }
}
