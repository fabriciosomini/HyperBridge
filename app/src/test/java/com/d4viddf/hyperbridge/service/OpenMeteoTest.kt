package com.d4viddf.hyperbridge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoTest {

    @Test
    fun urlRoundsCoordinatesAndPicksTheUnit() {
        val url = OpenMeteo.url(-28.67312, -49.42587, fahrenheit = false)
        assertTrue(url, url.contains("latitude=-28.67&longitude=-49.43"))
        assertTrue(url.contains("temperature_unit=celsius"))
        assertTrue(OpenMeteo.url(40.0, -74.0, fahrenheit = true).contains("temperature_unit=fahrenheit"))
    }

    @Test
    fun parsesCurrentConditionsAndTodaysRange() {
        val body = """
            {"latitude":-28.68,"current_units":{"temperature_2m":"°C"},
             "current":{"time":"2026-10-18T22:45","interval":900,"temperature_2m":13.4,"weather_code":2,"is_day":0},
             "daily":{"time":["2026-10-18"],"temperature_2m_max":[23.1],"temperature_2m_min":[6.2]}}
        """.trimIndent()
        val reading = OpenMeteo.parse(body)!!
        assertEquals(13.4, reading.temperature, 0.0)
        assertEquals(2, reading.weatherCode)
        assertFalse(reading.isDay)
        assertEquals(23.1, reading.high!!, 0.0)
        assertEquals(6.2, reading.low!!, 0.0)
    }

    @Test
    fun noCurrentTemperatureMeansNoReading() {
        assertNull(OpenMeteo.parse("""{"current":{"weather_code":1}}"""))
        assertNull(OpenMeteo.parse("""{"error":true,"reason":"Latitude must be in range"}"""))
        assertNull(OpenMeteo.parse("not json"))
    }

    @Test
    fun missingDailyRangeIsFine() {
        val reading = OpenMeteo.parse("""{"current":{"temperature_2m":-3.0,"is_day":1}}""")!!
        assertTrue(reading.isDay)
        assertNull(reading.high)
        assertNull(reading.weatherCode)
    }
}
