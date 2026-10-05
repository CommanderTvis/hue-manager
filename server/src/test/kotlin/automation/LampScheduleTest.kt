package io.github.commandertvis.huemanager.automation

import io.github.commandertvis.huemanager.hue.HueLightState
import io.github.commandertvis.huemanager.models.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LampScheduleTest {
    private fun schedule(vararg intervals: LampScheduleInterval) = LampSchedule("plant", intervals = intervals.toList())
    private fun at(value: String) = LocalDateTime.parse(value)
    private fun daytimeInterval(
        days: Set<Int> = (1..7).toSet(),
        start: String = "08:00",
        end: String = "21:00",
        brightness: Int = 100,
        temperatureKelvin: Int? = 5000,
        hue: Int? = null,
        saturation: Int? = null,
    ) = LampScheduleInterval(days, start, end, brightness, temperatureKelvin, hue, saturation)

    @Test
    fun `plant is on at start and off at end with full brightness and white light`() {
        val plant = schedule(daytimeInterval())
        assertFalse(plant.desiredState(at("2026-10-05T07:59")).on!!)
        val state = plant.desiredState(at("2026-10-05T08:00"))
        assertTrue(state.on!!)
        assertEquals(254, state.bri)
        assertEquals(200, state.ct)
        assertNull(state.hue)
        assertNull(state.sat)
        assertFalse(plant.desiredState(at("2026-10-05T21:00")).on!!)
    }

    @Test
    fun `overnight interval belongs to starting day and wraps across week`() {
        val overnight = schedule(daytimeInterval(days = setOf(7), start = "22:00", end = "02:00"))
        assertFalse(overnight.desiredState(at("2026-10-04T01:00")).on!!)
        assertTrue(overnight.desiredState(at("2026-10-04T22:00")).on!!)
        assertTrue(overnight.desiredState(at("2026-10-05T01:59")).on!!)
        assertFalse(overnight.desiredState(at("2026-10-05T02:00")).on!!)
        assertFalse(overnight.desiredState(at("2026-10-05T22:00")).on!!)
    }

    @Test
    fun `multiple adjacent intervals change brightness and color and leave gaps off`() {
        val plant = schedule(
            daytimeInterval(start = "08:00", end = "12:00", brightness = 1),
            daytimeInterval(start = "12:00", end = "16:00", brightness = 50,
                temperatureKelvin = null, hue = 5000, saturation = 254),
        )
        assertNull(lampScheduleError(plant))
        assertEquals(3, plant.desiredState(at("2026-10-05T08:00")).bri)
        val afternoon = plant.desiredState(at("2026-10-05T12:00"))
        assertEquals(127, afternoon.bri)
        assertEquals(5000, afternoon.hue)
        assertNull(afternoon.ct)
        assertFalse(plant.desiredState(at("2026-10-05T17:00")).on!!)
    }

    @Test
    fun `reject overlaps including Sunday spillover but allow different days`() {
        val sunday = daytimeInterval(days = setOf(7), start = "23:00", end = "02:00")
        val monday = daytimeInterval(days = setOf(1), start = "01:00", end = "03:00")
        assertNotNull(lampScheduleError(schedule(sunday, monday)))
        assertNull(lampScheduleError(schedule(sunday, monday.copy(days = setOf(2)))))
        assertNotNull(lampScheduleError(schedule(daytimeInterval(), daytimeInterval())))
    }

    @Test
    fun `invalid times days brightness temperature and conflicting color are rejected`() {
        val base = daytimeInterval()
        val invalid = listOf(
            base.copy(start = "24:00"), base.copy(end = "21:60"), base.copy(start = "8:00"),
            base.copy(end = base.start), base.copy(days = emptySet()), base.copy(days = setOf(0)),
            base.copy(brightness = 0), base.copy(brightness = 101), base.copy(temperatureKelvin = 7000),
            base.copy(hue = 5000), base.copy(temperatureKelvin = null, hue = 5000),
        )
        invalid.forEach { assertNotNull(lampScheduleError(schedule(it)), it.toString()) }
    }

    @Test
    fun `local wall times stay the same through daylight saving changes`() {
        val zone = TimeZone.of("Europe/Berlin")
        val plant = schedule(daytimeInterval())
        for (instant in listOf("2026-10-24T06:00:00Z", "2026-10-25T07:00:00Z")) {
            assertTrue(plant.desiredState(Instant.parse(instant).toLocalDateTime(zone)).on!!)
        }
        assertFalse(plant.desiredState(Instant.parse("2026-10-25T06:59:00Z").toLocalDateTime(zone)).on!!)
    }

    @Test
    fun `schedule matching detects small brightness changes and wrong color mode`() {
        val desired = schedule(daytimeInterval()).desiredState(at("2026-10-05T08:00"))
        val actual = HueLightState(on = true, bri = 254, ct = 200, colormode = "ct")
        assertTrue(actual.matchesSchedule(desired))
        assertFalse(actual.copy(bri = 240).matchesSchedule(desired))
        assertFalse(actual.copy(colormode = "hs").matchesSchedule(desired))
    }

    @Test
    fun `schedule round trip retains days colors and disabled state`() {
        val original = schedule(daytimeInterval(days = setOf(1, 3, 5))).copy(enabled = false)
        assertEquals(original, Json.decodeFromString<LampSchedule>(Json.encodeToString(original)))
    }

    @Test
    fun `on off lamps receive only a power command`() {
        val state = schedule(daytimeInterval(temperatureKelvin = null))
            .desiredState(at("2026-10-05T08:00"), supportsBrightness = false)
        assertTrue(state.on!!)
        assertNull(state.bri)
        assertNull(state.ct)
        assertTrue(HueLightState(on = true).matchesSchedule(state))
    }

    @Test
    fun `validation identifies both time fields in their own interval`() {
        val errors = lampScheduleErrors(schedule(daytimeInterval(start = "", end = ""), daytimeInterval(temperatureKelvin = 7000)))
        assertEquals(listOf(0, 0, 1), errors.map { it.intervalIndex })
        assertEquals(listOf(LampScheduleField.START, LampScheduleField.END, LampScheduleField.TEMPERATURE), errors.map { it.field })
    }

    @Test
    fun `validation assigns overlap to the conflicting interval`() {
        val errors = lampScheduleErrors(schedule(daytimeInterval(), daytimeInterval()))
        assertEquals(1, errors.single().intervalIndex)
        assertEquals(LampScheduleField.INTERVAL, errors.single().field)
    }
}
