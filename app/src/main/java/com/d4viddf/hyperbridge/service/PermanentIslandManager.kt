package com.d4viddf.hyperbridge.service

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.theme.ThemeRepository
import com.d4viddf.hyperbridge.data.widget.CustomWidgetRepository
import com.d4viddf.hyperbridge.data.widget.DeviceVariables
import com.d4viddf.hyperbridge.data.widget.SourceRepository
import com.d4viddf.hyperbridge.data.widget.VariableContext
import com.d4viddf.hyperbridge.data.widget.WidgetVariableEngine
import com.d4viddf.hyperbridge.integration.xiaomi.HyperIslandProtocolOptions
import com.d4viddf.hyperbridge.integration.xiaomi.KEYGUARD_PERSISTENT_ISLAND_PROPERTY
import com.d4viddf.hyperbridge.integration.xiaomi.buildJsonParam
import com.d4viddf.hyperbridge.models.HyperIslandData
import com.d4viddf.hyperbridge.models.theme.ColorMode
import com.d4viddf.hyperbridge.models.theme.HyperTheme
import com.d4viddf.hyperbridge.models.widget.CustomWidgetDocument
import com.d4viddf.hyperbridge.service.widget.CustomWidgetRenderer
import com.d4viddf.hyperbridge.util.ShizukuManager
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.TextInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds


class PermanentIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: AppPreferences,
    private val themeRepository: ThemeRepository
) {
    private val TAG = "HyperBridgeDebug"

    companion object {
        const val PERMANENT_BRIDGE_ID = 9999
        // The dismiss path posts 9999 right after cancelling the previous focus
        // island. Delaying the post lets HyperOS finish tearing that island down,
        // otherwise it can swallow the re-post and leave 9999 posted but hidden.
        private const val DISPATCH_DELAY_MS = 700L

        /** The resolved border colour, shared in-process with [IslandAccessibilityService]'s ring. */
        val currentBorderColor = MutableStateFlow(PermanentIslandBorderColor.DEFAULT)
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
    private var borderColor = resolveBorderColor()

    // --- Custom micro-widget on the permanent island (#273 "island content sources") ---
    private val customWidgetRepository = CustomWidgetRepository(context)
    private val sourceRepository = SourceRepository(context)
    private val widgetRenderer = CustomWidgetRenderer(context, WidgetVariableEngine(), customWidgetRepository)
    private var currentCustomWidgetId: String? = null
    private var currentCustomWidgetDoc: CustomWidgetDocument? = null

    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)

    // KeyguardLockedStateListener needs the privileged SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE, so lock
    // state is re-queried on SCREEN_ON / USER_PRESENT instead. SCREEN_OFF is deliberately ignored:
    // an island added while the screen is off keeps a hidden view after wake (neither an in-place
    // update nor leaving it alone brings it back), so the keyguard pill is posted on SCREEN_ON.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val locked = keyguardManager?.isKeyguardLocked == true
            synchronized(this@PermanentIslandManager) {
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
        currentBorderColor.value = borderColor
        isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        context.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        scope.launch {
            preferences.permanentIslandWidgetIdFlow.collectLatest { widgetId ->
                currentCustomWidgetId = widgetId
                currentCustomWidgetDoc = widgetId?.let { customWidgetRepository.getWidget(it) }
                if (isIslandActive) {
                    dispatchPermanentIsland()
                }
            }
        }
        scope.launch {
            sourceRepository.updates.collectLatest {
                // A bound widget may reference any source id in its templates; re-dispatch on any
                // source update rather than parsing which ids a widget actually references.
                if (isIslandActive && currentCustomWidgetDoc != null) {
                    dispatchPermanentIsland()
                }
            }
        }
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
        scope.launch {
            themeRepository.activeTheme.collectLatest { refreshBorderColor() }
        }
    }

    @Synchronized
    fun onActiveNotificationsChanged(count: Int, hasNative: Boolean = false) {
        currentRealNotifications = count
        hasNativeIsland = hasNative
        updateStateLocked()
    }

    @Synchronized
    fun onConfigurationChanged() {
        // A new wallpaper palette changes the Material You accent.
        refreshBorderColor()
        updateStateLocked()
    }

    // Re-post in place so the island's border picks up the new colour.
    @Synchronized
    private fun refreshBorderColor() {
        val color = resolveBorderColor()
        if (color != borderColor) {
            borderColor = color
            currentBorderColor.value = color
            if (isIslandActive) dispatchPermanentIsland()
        }
    }

    private fun resolveBorderColor(): String = PermanentIslandBorderColor.resolve(
        theme = themeRepository.activeTheme.value,
        materialYouHex = String.format("#%06X", context.getColor(android.R.color.system_accent1_200) and 0xFFFFFF),
        isValidColor = { runCatching { Color.parseColor(it) }.isSuccess }
    )

    // isIslandPresent reflects whether PERMANENT_BRIDGE_ID is actually posted right now.
    // Presence only proves the notification exists, NOT that its island is visible:
    // HyperOS can keep 9999 posted while hiding its island (e.g. a bridged focus island
    // superseded it, or a re-post landed too soon after a cancel). So on a discrete
    // transition (screen on / unlock / (re)connect) callers pass refresh=true to re-assert
    // the island even when present; the periodic tick passes false, trusting presence.
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
        try {
            Log.d(TAG, "Dispatching permanent island")
            
            val builder = HyperIslandNotification.Builder(context, "permanent_island", "Permanent Island")
            
            // Should not be dismissible and shouldn't show in shade
            builder.setEnableFloat(false)
            builder.setIslandConfig(timeout = 86400000, dismissible = false, highlightColor = borderColor, expandedTimeMs = 0)
            builder.setShowNotification(false)
            builder.setReopen(true)
            builder.setIslandFirstFloat(false)

            val widgetDoc = currentCustomWidgetDoc
            if (widgetDoc != null) {
                // #273 "island content sources": render the bound micro-widget instead of the
                // blank spacer, so e.g. a weather app driving {source.weather.text} shows on the
                // always-on permanent island.
                val resolvedColorScheme = com.d4viddf.hyperbridge.models.colorscheme.DynamicColorSchemeResolver.resolve(
                    context = context,
                    config = widgetDoc.globals.colorSchemeConfig,
                    embeddedYaml = widgetDoc.globals.embeddedColorSchemeYaml,
                    isDark = true
                )
                val ctx = VariableContext(
                    deviceBatteryPercent = DeviceVariables.batteryPercent(context),
                    timeNowFormatted = DeviceVariables.timeNow(),
                    sourceLookup = { id, field -> runBlocking { sourceRepository.lookup(id, field) } },
                    colorScheme = resolvedColorScheme
                )
                val widgetView = widgetRenderer.render(widgetDoc, ctx, bridgeId = PERMANENT_BRIDGE_ID)
                builder.setCustomRemoteView(widgetView)
                // The expanded island reads its content from the island-expand slot; the custom
                // notification view alone leaves a pill that never expands (verified on device).
                builder.setCustomIslandExpandRemoteView(widgetView)
                builder.setSmallIsland("")
            } else {
                // Only big paramislands with empty values for textonleft and picKey = null
                // Use width spaces to change width
                val emptyString = "\u00A0".repeat(currentWidth)
                builder.setBigIslandInfo(
                    left = ImageTextInfoLeft(1, null, TextInfo(emptyString, emptyString)),
                    right = null
                )
                builder.setSmallIsland("")
            }

            val resourceBundle = if (widgetDoc != null) builder.buildCustomExtras() else builder.buildResourceBundle()
            // Property 0 keeps HyperOS drawing the pill over the keyguard; 1 is the toolkit default.
            val islandProperty = if (showOnLockscreen) KEYGUARD_PERSISTENT_ISLAND_PROPERTY else 1
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
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching permanent island", e)
        }
    }

    private fun removePermanentIsland() {
        try {
            Log.d(TAG, "Removing permanent island")
            ShizukuManager.cancel(context, PERMANENT_BRIDGE_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing permanent island", e)
        }
    }
}

/**
 * The Permanent Island's `highlightColor`, which HyperOS uses only to tint the island's border: it
 * draws the stroke at 20% of the colour's alpha, and only while the area behind the cutout is dark
 * (never over light content, in either theme). Follows the active theme's global island colour like
 * bridged islands do; Material You takes the light accent tone, because a dark one would vanish
 * against the dark background the border is drawn on. App-icon mode has no app to sample here, so
 * it uses the stored colour.
 */
internal object PermanentIslandBorderColor {
    const val DEFAULT = "#FFFFFF"

    fun resolve(theme: HyperTheme?, materialYouHex: String, isValidColor: (String) -> Boolean): String {
        val global = theme?.global ?: return DEFAULT
        if (global.activeColorMode == ColorMode.MATERIAL_YOU) return materialYouHex
        return global.highlightColor?.takeIf(isValidColor) ?: DEFAULT
    }
}
