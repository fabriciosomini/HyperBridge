package com.d4viddf.hyperbridge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NextAlarmPillPolicyTest {

    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = at(2026, 10, 18, 22, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun xiaomiClockAlarmWinsOverAnEarlierCalendarReminder() {
        val clock = at(2026, 10, 19, 9, 0)
        val reminder = at(2026, 10, 19, 7, 44)
        assertEquals(clock, NextAlarmPillPolicy.alarmTime(clock, reminder, "com.android.providers.calendar"))
    }

    @Test
    fun withoutTheXiaomiSettingTheSystemAlarmIsUsed() {
        val clock = at(2026, 10, 19, 9, 0)
        assertEquals(clock, NextAlarmPillPolicy.alarmTime(null, clock, "com.google.android.deskclock"))
        assertEquals(clock, NextAlarmPillPolicy.alarmTime(0L, clock, "com.google.android.deskclock"))
    }

    @Test
    fun aCalendarReminderIsNotAnAlarm() {
        val reminder = at(2026, 10, 19, 7, 44)
        assertNull(NextAlarmPillPolicy.alarmTime(null, reminder, "com.android.providers.calendar"))
        assertNull(NextAlarmPillPolicy.alarmTime(null, reminder, "com.google.android.calendar"))
    }

    @Test
    fun timeLeftRoundsUpToTheNextMinute() {
        assertEquals(6 to 20, NextAlarmPillPolicy.timeLeft(at(2026, 10, 19, 4, 20), now))
        assertEquals(6 to 20, NextAlarmPillPolicy.timeLeft(at(2026, 10, 19, 4, 20), now + 1_000L)) // 6 h 19 min 59 s
        assertEquals(0 to 1, NextAlarmPillPolicy.timeLeft(now + 1_000L, now))
        assertEquals(12 to 0, NextAlarmPillPolicy.timeLeft(now + 12 * 3_600_000L, now))
    }

    @Test
    fun noAlarmAnywhereIsNull() {
        assertNull(NextAlarmPillPolicy.alarmTime(null, null, null))
    }

    @Test
    fun noAlarmShowsNothing() {
        assertNull(NextAlarmPillPolicy.label(null, now, is24Hour = true, zone = zone))
    }

    @Test
    fun alarmWithinTwelveHoursShowsIn24HourFormatAsFourDigits() {
        assertEquals("0730", NextAlarmPillPolicy.label(at(2026, 10, 19, 7, 30), now, is24Hour = true, zone = zone))
    }

    @Test
    fun alarmWithinTwelveHoursShowsIn12HourFormatAsFourDigits() {
        assertEquals("0730", NextAlarmPillPolicy.label(at(2026, 10, 19, 7, 30), now, is24Hour = false, zone = zone))
        assertEquals("1105", NextAlarmPillPolicy.label(at(2026, 10, 18, 23, 5), now, is24Hour = false, zone = zone))
    }

    @Test
    fun alarmExactlyTwelveHoursAwayShows() {
        assertEquals("1000", NextAlarmPillPolicy.label(at(2026, 10, 19, 10, 0), now, is24Hour = true, zone = zone))
    }

    @Test
    fun alarmMoreThanTwelveHoursAwayShowsNothing() {
        assertNull(NextAlarmPillPolicy.label(at(2026, 10, 19, 10, 1), now, is24Hour = true, zone = zone))
    }

    @Test
    fun alarmInThePastShowsNothing() {
        assertNull(NextAlarmPillPolicy.label(at(2026, 10, 18, 21, 59), now, is24Hour = true, zone = zone))
    }
}
