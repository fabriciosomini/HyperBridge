package com.d4viddf.hyperbridge.service

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.integration.xiaomi.HyperIslandProtocolOptions
import com.d4viddf.hyperbridge.integration.xiaomi.KEYGUARD_ISLAND_PROPERTY
import com.d4viddf.hyperbridge.integration.xiaomi.buildJsonParam
import com.d4viddf.hyperbridge.models.HyperIslandData
import com.d4viddf.hyperbridge.util.ShizukuManager
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.TextInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds


class PermanentIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: AppPreferences
) {
    private val TAG = "HyperBridgeDebug"

    companion object {
        const val PERMANENT_BRIDGE_ID = 9999
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

    init {
        isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        context.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })

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

            // Only big paramislands with empty values for textonleft and picKey = null
            // Use width spaces to change width
            val emptyString = "\u00A0".repeat(currentWidth)
            builder.setBigIslandInfo(
                left = ImageTextInfoLeft(1, null, TextInfo(emptyString, emptyString)),
                right = null
            )
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

            notifBuilder.addExtras(data.resources)

            val notification = notifBuilder.build()
            notification.extras.putString("miui.focus.param", data.jsonParam)

            ShizukuManager.notify(context, PERMANENT_BRIDGE_ID, notification)
            isPosted = true
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching permanent island", e)
        }
    }

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
