package com.d4viddf.hyperbridge.service

import com.d4viddf.hyperbridge.service.WeatherPillPolicy.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherPillPolicyTest {

    private val now = 1_790_000_040_000L // a minute boundary
    private val minute = WeatherPillPolicy.ROTATION_MS

    @Test
    fun temperatureIsRoundedWithADegreeSign() {
        assertEquals("23°", WeatherPillPolicy.label(22.6, now, now))
        assertEquals("-12°", WeatherPillPolicy.label(-12.4, now, now))
        assertEquals("0°", WeatherPillPolicy.label(-0.3, now, now))
    }

    @Test
    fun aStaleOrMissingReadingShowsNothing() {
        assertNull(WeatherPillPolicy.label(20.0, now - WeatherPillPolicy.STALE_MS - 1, now))
        assertNull(WeatherPillPolicy.label(20.0, null, now))
        assertNull(WeatherPillPolicy.label(null, now, now))
        assertNull(WeatherPillPolicy.label(Double.NaN, now, now))
        assertEquals("20°", WeatherPillPolicy.label(20.0, now - WeatherPillPolicy.STALE_MS, now))
    }

    @Test
    fun alarmAndWeatherTakeTurnsEveryMinute() {
        val first = WeatherPillPolicy.pick(hasAlarm = true, hasWeather = true, nowMillis = now)
        val second = WeatherPillPolicy.pick(hasAlarm = true, hasWeather = true, nowMillis = now + minute)
        assertEquals(setOf(Content.ALARM, Content.WEATHER), setOf(first, second))
        assertEquals(first, WeatherPillPolicy.pick(true, true, now + minute - 1))
        assertEquals(first, WeatherPillPolicy.pick(true, true, now + 2 * minute))
    }

    @Test
    fun nextSwapsTheTurns() {
        val shown = WeatherPillPolicy.pick(true, true, now)
        val flipped = WeatherPillPolicy.pick(true, true, now, flipped = true)
        assertEquals(setOf(Content.ALARM, Content.WEATHER), setOf(shown, flipped))
        assertEquals(shown, WeatherPillPolicy.pick(true, true, now + minute, flipped = true))
    }

    @Test
    fun withOneSourceThatOneStays() {
        assertEquals(Content.ALARM, WeatherPillPolicy.pick(hasAlarm = true, hasWeather = false, nowMillis = now))
        assertEquals(Content.WEATHER, WeatherPillPolicy.pick(hasAlarm = false, hasWeather = true, nowMillis = now))
        assertEquals(Content.WEATHER, WeatherPillPolicy.pick(false, true, now, flipped = true))
        assertNull(WeatherPillPolicy.pick(hasAlarm = false, hasWeather = false, nowMillis = now))
    }

    @Test
    fun rotationLandsOnTheMinute() {
        assertEquals(minute, WeatherPillPolicy.untilNextRotation(now))
        assertEquals(15_000L, WeatherPillPolicy.untilNextRotation(now + 45_000L))
    }

    @Test
    fun wmoCodesMapToXiaomiWeatherIcons() {
        assertEquals("sunny", WeatherPillPolicy.iconName(0, isDay = true))
        assertEquals("sunny_night", WeatherPillPolicy.iconName(1, isDay = false))
        assertEquals("cloudy_night", WeatherPillPolicy.iconName(2, isDay = false))
        assertEquals("overcast", WeatherPillPolicy.iconName(3, isDay = false))
        assertEquals("fog_night", WeatherPillPolicy.iconName(45, isDay = false))
        assertEquals("moderate_rain", WeatherPillPolicy.iconName(63, isDay = true))
        assertEquals("ice_rain", WeatherPillPolicy.iconName(66, isDay = true))
        assertEquals("heavy_snow", WeatherPillPolicy.iconName(75, isDay = true))
        assertEquals("t_storm", WeatherPillPolicy.iconName(95, isDay = false))
        assertNull(WeatherPillPolicy.iconName(42, isDay = true))
        assertNull(WeatherPillPolicy.iconName(null, isDay = true))
    }

    @Test
    fun everyIconCodeHasADescription() {
        listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
            .forEach { code ->
                assertNotNull("icon for $code", WeatherPillPolicy.iconName(code, isDay = true))
                assertNotNull("description for $code", WeatherPillPolicy.descriptionRes(code))
            }
    }
}
