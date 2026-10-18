package com.d4viddf.hyperbridge.service

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log

/**
 * Invisible landing point for a tap on the next-alarm card of the Permanent Island.
 *
 * Opening the clock from the island leaves the pill in HyperOS's "app opened from island" state,
 * which hides it until the next re-post (up to a minute). Going through this activity makes the
 * tap an event we see, so the pill is re-posted right away.
 */
class NextAlarmTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.e("HyperBridge", "No app handles ACTION_SHOW_ALARMS", e)
        }

        sendBroadcast(Intent(ACTION_REPOST_PERMANENT_ISLAND).setPackage(packageName))

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_REPOST_PERMANENT_ISLAND = "com.d4viddf.hyperbridge.REPOST_PERMANENT_ISLAND"
    }
}
