package com.d4viddf.hyperbridge.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Draws a ring around the Permanent Island pill, in the same colour the island's native border
 * uses ([PermanentIslandManager.currentBorderColor]). HyperOS only draws its own border over dark
 * content and at 20% alpha; this ring is visible everywhere. The native border is left alone.
 *
 * The pill is found in the island window's node tree: `big_island_view` whose content description
 * is the owning app's label ("Hyper Bridge" for ours). While the pill moves or resizes (expand,
 * merge, width change) the ring hides and comes back once the bounds hold still.
 *
 * Must stay in the app's default process: the colour comes from an in-process StateFlow.
 */
class IslandAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var ring: View? = null
    private var ringBounds: Rect? = null
    private var pendingBounds: Rect? = null
    private var color = PermanentIslandBorderColor.DEFAULT
    private val ownLabel by lazy { applicationInfo.loadLabel(packageManager).toString() }

    private val refreshRunnable = Runnable { refresh() }
    private val showRunnable = Runnable { pendingBounds?.let { showRing(it) } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        scope.launch {
            PermanentIslandManager.currentBorderColor.collect {
                color = it
                ringBounds?.let { b -> removeRing(); showRing(b) }
            }
        }
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.packageName?.toString() != ISLAND_PACKAGE
        ) return
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, EVENT_COALESCE_MS)
    }

    private fun refresh() {
        val bounds = findOwnPill()
        if (bounds == null) {
            handler.removeCallbacks(showRunnable)
            pendingBounds = null
            removeRing()
            return
        }
        if (bounds == ringBounds && ring != null) return
        if (bounds == pendingBounds) return
        // Bounds changed: hide now, show once they have held still (animations pass through
        // many intermediate frames).
        removeRing()
        pendingBounds = bounds
        handler.removeCallbacks(showRunnable)
        handler.postDelayed(showRunnable, SETTLE_MS)
    }

    private fun findOwnPill(): Rect? {
        val all = runCatching { windows }.getOrNull().orEmpty()
        for (w in all) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() != ISLAND_PACKAGE) continue
            findPillNode(root, 0)?.let { return it }
        }
        return null
    }

    private fun findPillNode(node: AccessibilityNodeInfo, depth: Int): Rect? {
        if (depth > MAX_NODE_DEPTH) return null
        val id = node.viewIdResourceName
        if (id != null && id.endsWith(":id/$PILL_VIEW_ID") && node.isVisibleToUser &&
            node.contentDescription?.toString() == ownLabel
        ) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (!r.isEmpty) return r
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            findPillNode(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun showRing(bounds: Rect) {
        val wm = windowManager ?: return
        removeRing()
        val parsed = runCatching { Color.parseColor(color) }.getOrDefault(Color.WHITE)
        val strokePx = (STROKE_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = bounds.height() / 2f
                setColor(Color.TRANSPARENT)
                setStroke(strokePx, parsed)
            }
        }
        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm.addView(view, params)
            ring = view
            ringBounds = bounds
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add island ring", e)
        }
    }

    private fun removeRing() {
        val view = ring ?: return
        ring = null
        ringBounds = null
        runCatching { windowManager?.removeView(view) }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        removeRing()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeRing()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IslandRing"
        private const val ISLAND_PACKAGE = "miui.systemui.plugin"
        private const val PILL_VIEW_ID = "big_island_view"
        private const val MAX_NODE_DEPTH = 12
        private const val EVENT_COALESCE_MS = 50L
        private const val SETTLE_MS = 250L
        private const val STROKE_DP = 1.5f
    }
}
