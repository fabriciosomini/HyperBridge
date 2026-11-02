package com.d4viddf.hyperbridge.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import com.d4viddf.hyperbridge.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Current weather for the Permanent Island pill, from Open-Meteo at the phone's approximate
 * location.
 *
 * Xiaomi Weather's own provider (`content://weather/weather`) would match the lock screen, but it
 * only answers an allow-list of Xiaomi packages (and the shell): HyperOS 4 logs "query rejected,
 * invalid calling package" for anyone else. Its condition icons are plain Flutter assets in its
 * APK, though, so the pill still uses those, with bundled vectors as a fallback.
 */
object WeatherRepository {
    const val XIAOMI_WEATHER_PACKAGE = "com.miui.weather2"

    private const val TAG = "HyperBridge"
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val RETRY_MS = 5 * 60 * 1000L
    private const val PREFS = "permanent_island_weather"
    private const val ICON_ASSET_DIR = "flutter_assets/assets/images"

    data class Snapshot(
        val reading: OpenMeteo.Reading,
        val city: String?,
        val fetchedAtMillis: Long,
    )

    @Volatile var current: Snapshot? = null
        private set
    private var lastAttemptMillis = 0L
    private var refreshing = false
    private var forceRefresh = false
    private val iconCache = mutableMapOf<String, Bitmap>()

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Fetches a new reading in the background when the last one is older than 30 minutes (5 after
     * a failure); [onUpdated] runs once it lands.
     */
    @Synchronized
    fun refreshIfStale(context: Context, scope: CoroutineScope, onUpdated: () -> Unit) {
        val now = System.currentTimeMillis()
        val fetchedAt = current?.fetchedAtMillis ?: 0L
        if (refreshing) return
        if (!forceRefresh && (now - fetchedAt < REFRESH_MS || now - lastAttemptMillis < RETRY_MS)) return
        forceRefresh = false
        refreshing = true
        lastAttemptMillis = now
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val snapshot = runCatching { fetch(appContext) }
                .onFailure { Log.w(TAG, "Weather refresh failed", it) }
                .getOrNull()
            synchronized(this@WeatherRepository) {
                refreshing = false
                if (snapshot != null) current = snapshot
            }
            if (snapshot != null) onUpdated()
        }
    }

    /**
     * Asks for a fresh fix while the app is in the foreground (the island runs in the background,
     * where Android only hands out the last known location), stores it for later refreshes and makes the next one fetch right away.
     */
    fun captureLocation(context: Context) {
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val provider = if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER
        else LocationManager.NETWORK_PROVIDER
        runCatching {
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { location ->
                if (location != null) {
                    saveLocation(context, location)
                    synchronized(this) { forceRefresh = true }
                }
            }
        }.onFailure { Log.w(TAG, "No location fix", it) }
    }

    private fun fetch(context: Context): Snapshot? {
        val (latitude, longitude) = location(context) ?: return null
        val fahrenheit = LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.US
        val connection = URL(OpenMeteo.url(latitude, longitude, fahrenheit)).openConnection() as HttpURLConnection
        val body = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val reading = OpenMeteo.parse(body) ?: return null
        return Snapshot(reading, cityName(context, latitude, longitude), System.currentTimeMillis())
    }

    /** The newest last-known fix if there is one, else the one stored by [captureLocation]. */
    private fun location(context: Context): Pair<Double, Double>? {
        if (hasLocationPermission(context)) {
            val lm = context.getSystemService(LocationManager::class.java)
            val lastKnown = lm?.getProviders(true)
                ?.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                ?.maxByOrNull { it.time }
            if (lastKnown != null) {
                saveLocation(context, lastKnown)
                return lastKnown.latitude to lastKnown.longitude
            }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("lat")) return null
        return prefs.getFloat("lat", 0f).toDouble() to prefs.getFloat("lon", 0f).toDouble()
    }

    private fun saveLocation(context: Context, location: Location) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat("lat", location.latitude.toFloat())
            putFloat("lon", location.longitude.toFloat())
        }
    }

    private fun cityName(context: Context, latitude: Double, longitude: Double): String? = runCatching {
        @Suppress("DEPRECATION") // blocking variant is fine here: we're already on Dispatchers.IO
        Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()

    /** Xiaomi Weather's own icon for [iconName] (see [WeatherPillPolicy.iconName]), else a bundled one. */
    @Synchronized
    fun icon(context: Context, iconName: String?): Bitmap {
        val key = iconName ?: "unknown"
        iconCache[key]?.let { return it }
        val bitmap = iconName?.let { loadXiaomiIcon(context, it) } ?: fallbackIcon(context, iconName)
        iconCache[key] = bitmap
        return bitmap
    }

    private fun loadXiaomiIcon(context: Context, iconName: String): Bitmap? = runCatching {
        context.createPackageContext(XIAOMI_WEATHER_PACKAGE, 0).assets.open("$ICON_ASSET_DIR/icon_$iconName.webp")
            .use { BitmapFactory.decodeStream(it) }
    }.onFailure { Log.w(TAG, "No Xiaomi Weather icon $iconName", it) }.getOrNull()

    private fun fallbackIcon(context: Context, iconName: String?): Bitmap {
        val res = when (iconName) {
            "sunny" -> R.drawable.ic_island_weather_sunny
            "sunny_night" -> R.drawable.ic_island_weather_clear_night
            "fog", "fog_night" -> R.drawable.ic_island_weather_fog
            "light_rain", "moderate_rain", "heavy_rain", "ice_rain" -> R.drawable.ic_island_weather_rain
            "light_snow", "moderate_snow", "heavy_snow" -> R.drawable.ic_island_weather_snow
            "t_storm" -> R.drawable.ic_island_weather_thunder
            else -> R.drawable.ic_island_weather_cloudy
        }
        return checkNotNull(ContextCompat.getDrawable(context, res)).toBitmap(96, 96)
    }
}
