package io.github.commandertvis.huemanager.models

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class LampSchedule(
    val lampId: String,
    val enabled: Boolean = true,
    val intervals: List<LampScheduleInterval> = emptyList(),
)

@Serializable
data class LampScheduleInterval(
    val days: Set<Int> = (1..7).toSet(),
    val start: String = "",
    val end: String = "",
    val brightness: Int = 100,
    val temperatureKelvin: Int? = null,
    val hue: Int? = null,
    val saturation: Int? = null,
) {
    fun isActive(time: LocalDateTime): Boolean {
        val startMinute = scheduleMinute(start) ?: return false
        val endMinute = scheduleMinute(end) ?: return false
        val minute = time.hour * 60 + time.minute
        val day = time.date.dayOfWeek.ordinal + 1
        return if (endMinute > startMinute) {
            day in days && minute in startMinute until endMinute
        } else {
            (day in days && minute >= startMinute) ||
                ((if (day == 1) 7 else day - 1) in days && minute < endMinute)
        }
    }
}

fun scheduleMinute(value: String): Int? {
    if (!Regex("\\d{2}:\\d{2}").matches(value)) return null
    val hour = value.substringBefore(':').toIntOrNull() ?: return null
    val minute = value.substringAfter(':').toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
}

enum class LampScheduleField { SCHEDULE, START, END, DAYS, BRIGHTNESS, TEMPERATURE, COLOR, INTERVAL }

data class LampScheduleValidationError(
    val intervalIndex: Int?,
    val field: LampScheduleField,
    val message: String,
)

fun lampScheduleError(schedule: LampSchedule): String? = lampScheduleErrors(schedule).firstOrNull()?.let {
    if (it.intervalIndex == null) it.message else "Interval ${it.intervalIndex + 1}: ${it.message}"
}

fun lampScheduleErrors(schedule: LampSchedule): List<LampScheduleValidationError> {
    val errors = mutableListOf<LampScheduleValidationError>()
    if (schedule.lampId.isBlank()) errors.add(LampScheduleValidationError(null, LampScheduleField.SCHEDULE, "Choose a lamp."))
    if (schedule.intervals.isEmpty()) errors.add(LampScheduleValidationError(null, LampScheduleField.SCHEDULE, "Add at least one interval."))
    val occupied = BooleanArray(7 * 24 * 60)
    for ((index, interval) in schedule.intervals.withIndex()) {
        fun error(field: LampScheduleField, message: String) {
            errors.add(LampScheduleValidationError(index, field, message))
        }
        val start = scheduleMinute(interval.start)
        val end = scheduleMinute(interval.end)
        if (start == null) error(LampScheduleField.START, "Enter a time as HH:MM.")
        if (end == null) error(LampScheduleField.END, "Enter a time as HH:MM.")
        if (start != null && end != null && start == end) error(LampScheduleField.END, "End must differ from start.")
        val validDays = interval.days.isNotEmpty() && interval.days.all { it in 1..7 }
        if (!validDays) error(LampScheduleField.DAYS, "Choose at least one day.")
        if (interval.brightness !in 1..100) error(LampScheduleField.BRIGHTNESS, "Brightness must be 1–100%.")
        if (interval.temperatureKelvin != null) {
            if (interval.temperatureKelvin !in 2000..6500) error(LampScheduleField.TEMPERATURE, "Temperature must be 2000–6500 K.")
            if (interval.hue != null || interval.saturation != null) error(LampScheduleField.COLOR, "Choose white or color.")
        } else if (interval.hue != null || interval.saturation != null) {
            if (interval.hue == null || interval.hue !in 0..65535 ||
                interval.saturation == null || interval.saturation !in 0..254
            ) error(LampScheduleField.COLOR, "Invalid color.")
        }
        if (start == null || end == null || start == end || !validDays) continue
        val duration = (end - start + 1440) % 1440
        var overlaps = false
        for (day in interval.days) {
            for (offset in 0 until duration) {
                val minute = ((day - 1) * 1440 + start + offset) % occupied.size
                if (occupied[minute]) overlaps = true
                occupied[minute] = true
            }
        }
        if (overlaps) error(LampScheduleField.INTERVAL, "Overlaps another interval. Change the times or days.")
    }
    return errors
}
