package com.d4viddf.hyperbridge.service

import com.d4viddf.hyperbridge.R
import kotlin.math.roundToInt

/** What the Permanent Island pill says about the weather, and how it shares the pill with the alarm. */
object WeatherPillPolicy {
    /** Older than this, the reading no longer describes "now" and the pill drops it. */
    const val STALE_MS = 3 * 60 * 60 * 1000L

    /** With both the alarm and the weather to show, the pill switches between them this often. */
    const val ROTATION_MS = 60 * 1000L

    enum class Content { ALARM, WEATHER }

    /**
     * @return the temperature as the pill shows it ("23°", "-12°": at most 4 characters, so the
     *   pill stays at its minimum width), or null when there's no fresh reading.
     */
    fun label(temperature: Double?, fetchedAtMillis: Long?, nowMillis: Long): String? {
        if (temperature == null || temperature.isNaN()) return null
        if (fetchedAtMillis == null || nowMillis - fetchedAtMillis > STALE_MS) return null
        return "${temperature.roundToInt()}°"
    }

    /**
     * Picks what the pill shows. With both available they take turns every [ROTATION_MS], the
     * alarm on even slots; [flipped] (the card's "Next" button) swaps the turns. With one
     * available, that one; with neither, null (the plain pill).
     */
    fun pick(hasAlarm: Boolean, hasWeather: Boolean, nowMillis: Long, flipped: Boolean = false): Content? = when {
        hasAlarm && hasWeather ->
            if (((nowMillis / ROTATION_MS) % 2 == 0L) != flipped) Content.ALARM else Content.WEATHER
        hasAlarm -> Content.ALARM
        hasWeather -> Content.WEATHER
        else -> null
    }

    /** @return how long until the pill should switch, so the rotation lands on the minute. */
    fun untilNextRotation(nowMillis: Long): Long = ROTATION_MS - nowMillis % ROTATION_MS

    /**
     * Maps a WMO weather code (what Open-Meteo reports) to the base name of Xiaomi Weather's icon
     * assets (`icon_<name>.webp`), with its night variant where one exists.
     */
    fun iconName(wmoCode: Int?, isDay: Boolean): String? {
        val base = when (wmoCode) {
            0, 1 -> "sunny"
            2 -> "cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            51, 53, 55, 61, 80 -> "light_rain"
            63, 81 -> "moderate_rain"
            65, 82 -> "heavy_rain"
            56, 57, 66, 67 -> "ice_rain"
            71, 77, 85 -> "light_snow"
            73 -> "moderate_snow"
            75, 86 -> "heavy_snow"
            95, 96, 99 -> "t_storm"
            else -> return null
        }
        return if (!isDay && base in NIGHT_VARIANTS) "${base}_night" else base
    }

    /** @return the condition's name for the expanded card, or null for an unknown code. */
    fun descriptionRes(wmoCode: Int?): Int? = when (wmoCode) {
        0 -> R.string.weather_clear
        1 -> R.string.weather_mainly_clear
        2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_overcast
        45, 48 -> R.string.weather_fog
        51, 53, 55 -> R.string.weather_drizzle
        56, 57, 66, 67 -> R.string.weather_freezing_rain
        61, 63, 65 -> R.string.weather_rain
        71, 73, 75, 77 -> R.string.weather_snow
        80, 81, 82 -> R.string.weather_showers
        85, 86 -> R.string.weather_snow_showers
        95, 96, 99 -> R.string.weather_thunderstorm
        else -> null
    }

    private val NIGHT_VARIANTS = setOf("sunny", "cloudy", "fog")
}
