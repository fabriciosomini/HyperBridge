package com.d4viddf.hyperbridge.service

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.integration.xiaomi.HyperIslandProtocolOptions
import com.d4viddf.hyperbridge.integration.xiaomi.KEYGUARD_ISLAND_PROPERTY
import com.d4viddf.hyperbridge.integration.xiaomi.buildJsonParam
import com.d4viddf.hyperbridge.models.HyperIslandData
import com.d4viddf.hyperbridge.util.ShizukuManager
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds


class PermanentIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: AppPreferences
) {
    private val TAG = "HyperBridgeDebug"

    companion object {
        const val PERMANENT_BRIDGE_ID = 9999
        private const val NEXT_ALARM_PIC = "next_alarm"
        // The dismiss path posts 9999 right after cancelling the previous focus
        // island. Delaying the post lets HyperOS finish tearing that island down,
        // otherwise it can swallow the re-post and leave 9999 posted but hidden.
        private const val DISPATCH_DELAY_MS = 700L
    }

    private var isPermanentIslandEnabled = false
    private var isIslandActive = false
    private var currentRealNotifications = 0

    fun isIslandActive(): Boolean = isIslandActive
    private var hasNativeIsland = false
    private var currentWidth = 0
    private var isHideInLandscapeEnabled = false
    private var showOnLockscreen = false
    private var showNextAlarm = false
    private var isKeyguardLocked = false
    private var pendingDispatchJob: Job? = null
    // Whether 9999 is posted right now, so a dispatch can tell a fresh add from an in-place update.
    private var isPosted = false
    // A fresh add that came up while the screen was off, held until SCREEN_ON.
    private var addOnScreenOn = false

    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    // KeyguardLockedStateListener needs the privileged SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE, so lock
    // state is re-queried on SCREEN_ON / USER_PRESENT instead. SCREEN_OFF is deliberately ignored:
    // an island added while the screen is off keeps a hidden view after wake (neither an in-place
    // update nor leaving it alone brings it back), so the keyguard pill is posted on SCREEN_ON.
    // The same holds for every other fresh add (e.g. the re-post after a WhatsApp island ends with
    // the screen off), so dispatchPermanentIsland holds those and they are posted here too.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val locked = keyguardManager?.isKeyguardLocked == true
            synchronized(this@PermanentIslandManager) {
                if (addOnScreenOn && intent.action == Intent.ACTION_SCREEN_ON) {
                    isKeyguardLocked = locked
                    addOnScreenOn = false
                    if (isIslandActive && desiredActive()) {
                        // Nothing was cancelled just before, so post without the anti-swallow delay.
                        val jobToCancel = pendingDispatchJob
                        pendingDispatchJob = null
                        jobToCancel?.cancel()
                        dispatchPermanentIsland()
                    } else {
                        updateStateLocked()
                    }
                    return
                }
                if (isKeyguardLocked == locked) return
                isKeyguardLocked = locked
                if (locked && !isIslandActive && desiredActive()) {
                    // Nothing was cancelled just before, so skip the anti-swallow delay: the pill
                    // should be there as the keyguard fades in, not 0.7 s later.
                    val jobToCancel = pendingDispatchJob
                    pendingDispatchJob = null
                    jobToCancel?.cancel()
                    isIslandActive = true
                    dispatchPermanentIsland()
                } else {
                    updateStateLocked()
                }
            }
        }
    }

    // An alarm set, changed or dismissed re-renders the pill at once; the 1-minute sync tick
    // re-dispatches too, which covers an alarm moving into the 12 h window.
    private val nextAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onNextAlarmChanged()
    }
    // Xiaomi's clock app rewrites its next-alarm setting whenever its alarms change.
    private val nextAlarmObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = onNextAlarmChanged()
    }

    // Sent by NextAlarmTapActivity: opening the clock from the card hides the pill ("app opened
    // from island"), and only a fresh add clears that, so cancel and post again.
    private val repostReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(this@PermanentIslandManager) {
                if (isIslandActive) {
                    removePermanentIsland()
                    scheduleDispatchLocked()
                }
            }
        }
    }

    private fun onNextAlarmChanged() {
        synchronized(this) {
            if (isIslandActive && showNextAlarm) dispatchPermanentIsland()
        }
    }

    init {
        isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        context.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        context.registerReceiver(nextAlarmReceiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED))
        ContextCompat.registerReceiver(
            context,
            repostReceiver,
            IntentFilter(NextAlarmTapActivity.ACTION_REPOST_PERMANENT_ISLAND),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(NextAlarmPillPolicy.XIAOMI_NEXT_ALARM_KEY), false, nextAlarmObserver
        )

        scope.launch {
            preferences.isPermanentIslandEnabledFlow.collectLatest { enabled ->
                synchronized(this@PermanentIslandManager) {
                    if (isPermanentIslandEnabled != enabled) {
                        isPermanentIslandEnabled = enabled
                        updateStateLocked()
                    }
                }
            }
        }
        scope.launch {
            preferences.hidePermanentIslandLandscapeFlow.collectLatest { hide ->
                synchronized(this@PermanentIslandManager) {
                    if (isHideInLandscapeEnabled != hide) {
                        isHideInLandscapeEnabled = hide
                        updateStateLocked()
                    }
                }
            }
        }
        scope.launch {
            preferences.permanentIslandWidthFlow.collectLatest { width ->
                synchronized(this@PermanentIslandManager) {
                    if (currentWidth != width) {
                        currentWidth = width
                        if (isIslandActive) {
                            dispatchPermanentIsland()
                        }
                    }
                }
            }
        }
        scope.launch {
            preferences.permanentIslandNextAlarmFlow.collectLatest { show ->
                synchronized(this@PermanentIslandManager) {
                    if (showNextAlarm != show) {
                        showNextAlarm = show
                        if (isIslandActive) {
                            dispatchPermanentIsland()
                        }
                    }
                }
            }
        }
        scope.launch {
            preferences.permanentIslandOnLockscreenFlow.collectLatest { show ->
                synchronized(this@PermanentIslandManager) {
                    if (showOnLockscreen != show) {
                        showOnLockscreen = show
                        // HyperOS only gives property-0 islands the keyguard-surviving "show once"
                        // slot when they are added; an in-place update keeps the old slot, so
                        // re-post from scratch.
                        if (isIslandActive) {
                            removePermanentIsland()
                            scheduleDispatchLocked()
                        }
                        updateStateLocked()
                    }
                }
            }
        }
    }

    @Synchronized
    fun onActiveNotificationsChanged(count: Int, hasNative: Boolean = false) {
        currentRealNotifications = count
        hasNativeIsland = hasNative
        updateStateLocked()
    }

    @Synchronized
    fun onOrientationChanged() {
        updateStateLocked()
    }

    // isIslandPresent reflects whether PERMANENT_BRIDGE_ID is actually posted right now.
    // Presence only proves the notification exists, NOT that its island is visible:
    // HyperOS can keep 9999 posted while hiding its island (e.g. a bridged focus island
    // superseded it, a re-post landed too soon after a cancel, or the user swiped the pill
    // left, which drops the island with no listener callback). So callers pass refresh=true to
    // re-assert the island even when present: on screen on / unlock / (re)connect, and on the
    // 1-minute tick so a swiped pill comes back. Re-asserting a visible pill is an in-place
    // update HyperOS draws identically (checked frame by frame on HyperOS 4).
    // The permanent island also steps aside for our own bridged islands (and widgets / the VPN
    // island). #243 kept 9999 posted underneath them so it was revealed the instant a bridged
    // island collapsed, which was fine while HyperOS drew one island at a time. HyperOS 3 draws
    // two: the newest big and the other as a mini island showing only the app icon, so a posted
    // pill turns into a HyperBridge bubble next to every WhatsApp island (#335). The price is a
    // ~1 s re-post after the last island goes, and, when the user swipes an island away early,
    // a gap until its lifecycle timeout (the same moment HyperOS would have hidden it anyway).
    private fun desiredActive(): Boolean {
        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return PermanentIslandVisibilityPolicy.desiredActive(
            enabled = isPermanentIslandEnabled,
            realNotificationCount = currentRealNotifications,
            hasNativeIsland = hasNativeIsland,
            hideInLandscape = isHideInLandscapeEnabled,
            isLandscape = isLandscape,
            keepOnKeyguard = showOnLockscreen && isKeyguardLocked
        )
    }

    fun stop() {
        runCatching { context.unregisterReceiver(screenReceiver) }
        runCatching { context.unregisterReceiver(nextAlarmReceiver) }
        runCatching { context.contentResolver.unregisterContentObserver(nextAlarmObserver) }
        runCatching { context.unregisterReceiver(repostReceiver) }
    }

    @Synchronized
    fun reconcile(count: Int, hasNative: Boolean, isIslandPresent: Boolean, refresh: Boolean) {
        currentRealNotifications = count
        hasNativeIsland = hasNative
        val shouldShow = desiredActive()
        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (shouldShow && isIslandPresent && refresh) {
            // Present but maybe not visible: re-assert in place (no remove first, so no
            // rapid cancel->post to swallow). Same id + content updates the residual island.
            val jobToCancel = pendingDispatchJob
            pendingDispatchJob = null
            jobToCancel?.cancel()
            dispatchPermanentIsland()
            isIslandActive = true
            return
        }
        isIslandActive = isIslandPresent
        isPosted = isIslandPresent
        Log.d(TAG, "updateState: shouldShow=$shouldShow, isLandscape=$isLandscape, isHideInLandscapeEnabled=$isHideInLandscapeEnabled")
        updateStateLocked()
    }

    private fun updateStateLocked() {
        if (desiredActive()) {
            if (!isIslandActive) {
                isIslandActive = true
                scheduleDispatchLocked()
            }
        } else {
            if (isIslandActive) {
                isIslandActive = false
                val jobToCancel = pendingDispatchJob
                pendingDispatchJob = null
                jobToCancel?.cancel()
                removePermanentIsland()
            }
        }
    }

    private fun scheduleDispatchLocked() {
        val jobToCancel = pendingDispatchJob
        pendingDispatchJob = null
        jobToCancel?.cancel()
        pendingDispatchJob = scope.launch {
            delay(DISPATCH_DELAY_MS.milliseconds)
            synchronized(this@PermanentIslandManager) {
                pendingDispatchJob = null
                // Re-check under the lock: the desired state may have flipped during the delay.
                if (desiredActive()) {
                    dispatchPermanentIsland()
                }
            }
        }
    }
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun dispatchPermanentIsland() {
        if (!isPosted && powerManager?.isInteractive == false) {
            Log.d(TAG, "Screen off: holding the permanent island until SCREEN_ON")
            addOnScreenOn = true
            return
        }
        try {
            Log.d(TAG, "Dispatching permanent island")
            
            val builder = HyperIslandNotification.Builder(context, "permanent_island", "Permanent Island")
            
            // Should not be dismissible and shouldn't show in shade
            builder.setEnableFloat(false)
            builder.setIslandConfig(timeout = 86400000, dismissible = false, highlightColor = "#FFFFFF", expandedTimeMs = 0)
            builder.setShowNotification(false)
            builder.setReopen(true)
            builder.setIslandFirstFloat(false)

            val alarmMillis = if (showNextAlarm) nextAlarmMillis() else null
            val alarmLabel = NextAlarmPillPolicy.label(
                triggerAtMillis = alarmMillis,
                nowMillis = System.currentTimeMillis(),
                is24Hour = DateFormat.is24HourFormat(context),
                zone = ZoneId.systemDefault()
            )
            if (alarmLabel != null && alarmMillis != null) {
                // Icon on the left, plain text on the right. bigIslandArea.textInfo becomes the
                // plugin's moduleText; an imageTextInfoRight of type 1 is skipped on the right.
                builder.addPicture(HyperPicture(NEXT_ALARM_PIC, alarmIconBitmap()))
                builder.setBigIslandInfo(
                    left = ImageTextInfoLeft(1, PicInfo(1, NEXT_ALARM_PIC), TextInfo("", "")),
                    centerText = TextInfo(alarmLabel)
                )
                // The card a tap expands the pill into; tapping it opens the alarm list.
                val (hoursLeft, minutesLeft) = NextAlarmPillPolicy.timeLeft(alarmMillis, System.currentTimeMillis())
                builder.setBaseInfo(
                    title = DateFormat.getTimeFormat(context).format(java.util.Date(alarmMillis)),
                    content = context.getString(R.string.permanent_island_next_alarm_in, hoursLeft, minutesLeft),
                    pictureKey = NEXT_ALARM_PIC
                )
            } else {
                // Only big paramislands with empty values for textonleft and picKey = null
                // Use width spaces to change width
                val emptyString = "\u00A0".repeat(currentWidth)
                builder.setBigIslandInfo(
                    left = ImageTextInfoLeft(1, null, TextInfo(emptyString, emptyString)),
                    right = null
                )
            }
            builder.setSmallIsland("")

            val resourceBundle = builder.buildResourceBundle()
            // Property 0 keeps HyperOS drawing the pill over the keyguard; 1 is the toolkit default.
            val islandProperty = if (showOnLockscreen) KEYGUARD_ISLAND_PROPERTY else 1
            val data = HyperIslandData(
                resourceBundle,
                builder.buildJsonParam(HyperIslandProtocolOptions(islandProperty = islandProperty))
            )

            val notifBuilder = NotificationCompat.Builder(context, "hyper_bridge_notification_channel")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Permanent Island")
                .setContentText("Empty Island")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
            // Opens the clock's alarm list via NextAlarmTapActivity, which also re-posts the pill.
            // HyperOS only sends this from the expanded island: a tap on the collapsed pill expands
            // it, and the direct-tap path is limited to an allow-list baked into MIUISystemUIPlugin
            // (click_direct_intent_packages).
            if (alarmLabel != null) notifBuilder.setContentIntent(showAlarmsIntent())

            notifBuilder.addExtras(data.resources)

            val notification = notifBuilder.build()
            notification.extras.putString("miui.focus.param", data.jsonParam)

            ShizukuManager.notify(context, PERMANENT_BRIDGE_ID, notification)
            isPosted = true
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching permanent island", e)
        }
    }

    private fun nextAlarmMillis(): Long? {
        val system = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        return NextAlarmPillPolicy.alarmTime(
            xiaomiClockMillis = runCatching {
                Settings.Global.getLong(context.contentResolver, NextAlarmPillPolicy.XIAOMI_NEXT_ALARM_KEY, 0L)
            }.getOrNull(),
            systemMillis = system?.triggerTime,
            systemCreatorPackage = system?.showIntent?.creatorPackage
        )
    }

    private fun showAlarmsIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, NextAlarmTapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun alarmIconBitmap(): Bitmap =
        checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_island_alarm)).toBitmap(96, 96)

    private fun removePermanentIsland() {
        isPosted = false
        addOnScreenOn = false
        try {
            Log.d(TAG, "Removing permanent island")
            ShizukuManager.cancel(context, PERMANENT_BRIDGE_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing permanent island", e)
        }
    }
}
