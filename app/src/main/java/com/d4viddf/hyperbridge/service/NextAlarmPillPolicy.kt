package com.d4viddf.hyperbridge.service

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the Permanent Island pill says about the next alarm, if anything. */
object NextAlarmPillPolicy {
    /** Further out than this, the alarm isn't "coming up" and the pill stays plain. */
    const val WINDOW_MS = 12 * 60 * 60 * 1000L

    // Always 4 digits, no separator or AM/PM: the pill only stays at its minimum width up to 4
    // characters, and within the 12 h window a 12-hour time is unambiguous.
    private val format24 = DateTimeFormatter.ofPattern("HHmm")
    private val format12 = DateTimeFormatter.ofPattern("hhmm")

    /** Settings.Global key where Xiaomi's clock app keeps its own next alarm, in epoch millis. */
    const val XIAOMI_NEXT_ALARM_KEY = "next_alarm_clock_long"

    /**
     * Picks the next real alarm. AlarmManager.getNextAlarmClock() is the earliest alarm-clock entry
     * from any app, and calendar providers register event reminders that way, so a 07:44 reminder
     * hid a 09:00 alarm. Xiaomi's clock writes its own next alarm to [XIAOMI_NEXT_ALARM_KEY]; where
     * that's missing, the system entry is used unless a calendar app created it.
     *
     * @param xiaomiClockMillis the [XIAOMI_NEXT_ALARM_KEY] value, null or 0 when unset
     * @param systemMillis AlarmManager's next alarm-clock trigger time
     * @param systemCreatorPackage the package that created that alarm's show intent
     */
    fun alarmTime(xiaomiClockMillis: Long?, systemMillis: Long?, systemCreatorPackage: String?): Long? {
        if (xiaomiClockMillis != null && xiaomiClockMillis > 0) return xiaomiClockMillis
        if (systemCreatorPackage?.contains("calendar", ignoreCase = true) == true) return null
        return systemMillis
    }

    /** @return hours and minutes until [triggerAtMillis], minutes rounded up so it never reads "0 min". */
    fun timeLeft(triggerAtMillis: Long, nowMillis: Long): Pair<Int, Int> {
        val minutes = ((triggerAtMillis - nowMillis).coerceAtLeast(0L) + 59_999L) / 60_000L
        return (minutes / 60).toInt() to (minutes % 60).toInt()
    }

    /** @return the alarm time in the system's 12/24-hour style, or null when there's nothing to show. */
    fun label(triggerAtMillis: Long?, nowMillis: Long, is24Hour: Boolean, zone: ZoneId): String? {
        if (triggerAtMillis == null) return null
        val untilAlarm = triggerAtMillis - nowMillis
        if (untilAlarm < 0 || untilAlarm > WINDOW_MS) return null
        val time = Instant.ofEpochMilli(triggerAtMillis).atZone(zone)
        return (if (is24Hour) format24 else format12).format(time)
    }
}
