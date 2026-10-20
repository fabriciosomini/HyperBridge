package com.d4viddf.hyperbridge.service

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log

/**
 * Invisible landing point for a tap on the Permanent Island's next-alarm or weather card.
 *
 * Opening an app from the island leaves the pill in HyperOS's "app opened from island" state,
 * which hides it until the next re-post (up to a minute). Going through this activity makes the
 * tap an event we see, so the pill is re-posted right away.
 */
class PermanentIslandTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = when (intent.getStringExtra(EXTRA_TARGET)) {
            TARGET_WEATHER -> packageManager.getLaunchIntentForPackage(WeatherRepository.XIAOMI_WEATHER_PACKAGE)
            else -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        }
        try {
            if (target != null) startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.e("HyperBridge", "No app handles $target", e)
        }

        sendBroadcast(Intent(ACTION_REPOST_PERMANENT_ISLAND).setPackage(packageName))

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_REPOST_PERMANENT_ISLAND = "com.d4viddf.hyperbridge.REPOST_PERMANENT_ISLAND"
        const val EXTRA_TARGET = "target"
        const val TARGET_ALARMS = "alarms"
        const val TARGET_WEATHER = "weather"
    }
}
