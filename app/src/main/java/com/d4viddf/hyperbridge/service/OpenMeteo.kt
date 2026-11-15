package com.d4viddf.hyperbridge.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/** The Open-Meteo forecast request and its parsing (free, no API key). */
object OpenMeteo {

    data class Reading(
        val temperature: Double,
        val weatherCode: Int?,
        val isDay: Boolean,
        val high: Double?,
        val low: Double?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Coordinates go out rounded to 0.01° (about 1 km): enough for the weather, no more. */
    fun url(latitude: Double, longitude: Double, fahrenheit: Boolean): String = String.format(
        Locale.ROOT,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto" +
            "&temperature_unit=%s",
        latitude, longitude, if (fahrenheit) "fahrenheit" else "celsius"
    )

    /** @return the current reading, or null when the response has no current temperature. */
    fun parse(body: String): Reading? {
        val response = runCatching { json.decodeFromString<Response>(body) }.getOrNull() ?: return null
        val current = response.current ?: return null
        val temperature = current.temperature ?: return null
        return Reading(
            temperature = temperature,
            weatherCode = current.weatherCode,
            isDay = current.isDay != 0,
            high = response.daily?.max?.firstOrNull(),
            low = response.daily?.min?.firstOrNull(),
        )
    }

    @Serializable
    private data class Response(val current: Current? = null, val daily: Daily? = null)

    @Serializable
    private data class Current(
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
        @SerialName("is_day") val isDay: Int? = null,
    )

    @Serializable
    private data class Daily(
        @SerialName("temperature_2m_max") val max: List<Double?> = emptyList(),
        @SerialName("temperature_2m_min") val min: List<Double?> = emptyList(),
    )
}
