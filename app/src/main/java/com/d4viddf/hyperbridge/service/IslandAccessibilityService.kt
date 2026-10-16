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
 * Draws a ring around every collapsed island (ours, other apps' pills and the small circle shown
 * when two islands run at once), in the same colour the Permanent Island's native border uses
 * ([PermanentIslandManager.currentBorderColor]). HyperOS only draws its own border over dark
 * content and at 20% alpha; this ring is visible everywhere. The native border is left alone.
 *
 * Islands are found in the island window's node tree: `big_island_view` (the pill) and
 * `small_island_view` (the circle). Expanded cards are skipped by height. While any island moves
 * or resizes (expand, merge, width change) the rings hide and come back once the bounds hold still.
 *
 * Must stay in the app's default process: the colour comes from an in-process StateFlow.
 */
class IslandAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private val rings = mutableListOf<View>()
    private var ringBounds: List<Rect> = emptyList()
    private var pendingBounds: List<Rect> = emptyList()
    private var color = PermanentIslandBorderColor.DEFAULT

    private val refreshRunnable = Runnable { refresh() }
    private val showRunnable = Runnable { showRings(pendingBounds) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        scope.launch {
            PermanentIslandManager.currentBorderColor.collect {
                color = it
                if (rings.isNotEmpty()) showRings(ringBounds)
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
        val bounds = findPills()
        if (bounds.isEmpty()) {
            handler.removeCallbacks(showRunnable)
            pendingBounds = emptyList()
            removeRings()
            return
        }
        if (bounds == ringBounds && rings.isNotEmpty()) return
        if (bounds == pendingBounds) return
        // Bounds changed: hide now, show once they have held still (animations pass through
        // many intermediate frames).
        removeRings()
        pendingBounds = bounds
        handler.removeCallbacks(showRunnable)
        handler.postDelayed(showRunnable, SETTLE_MS)
    }

    private fun findPills(): List<Rect> {
        val found = mutableListOf<Rect>()
        val all = runCatching { windows }.getOrNull().orEmpty()
        for (w in all) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() != ISLAND_PACKAGE) continue
            collectPillNodes(root, 0, found)
        }
        return found.sortedBy { it.left }
    }

    private fun collectPillNodes(node: AccessibilityNodeInfo, depth: Int, out: MutableList<Rect>) {
        if (depth > MAX_NODE_DEPTH) return
        val id = node.viewIdResourceName
        if (id != null && PILL_VIEW_IDS.any { id.endsWith(":id/$it") }) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            val maxHeightPx = MAX_PILL_HEIGHT_DP * resources.displayMetrics.density
            if (node.isVisibleToUser && !r.isEmpty && r.height() <= maxHeightPx) out += r
            return
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectPillNodes(child, depth + 1, out)
        }
    }

    private fun showRings(bounds: List<Rect>) {
        removeRings()
        bounds.forEach { showRing(it) }
        ringBounds = if (rings.isEmpty()) emptyList() else bounds
    }

    private fun showRing(bounds: Rect) {
        val wm = windowManager ?: return
        val parsed = runCatching { Color.parseColor(color) }.getOrDefault(Color.WHITE)
        val strokePx = (STROKE_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val view = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = minOf(bounds.width(), bounds.height()) / 2f
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
            rings += view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add island ring", e)
        }
    }

    private fun removeRings() {
        rings.forEach { runCatching { windowManager?.removeView(it) } }
        rings.clear()
        ringBounds = emptyList()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        removeRings()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeRings()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IslandRing"
        private const val ISLAND_PACKAGE = "miui.systemui.plugin"
        // Exact ids: the fake_* twins only exist during animations.
        private val PILL_VIEW_IDS = listOf("big_island_view", "small_island_view")
        // A collapsed pill is ~33 dp tall; anything taller is an expanded card.
        private const val MAX_PILL_HEIGHT_DP = 48f
        private const val MAX_NODE_DEPTH = 12
        private const val EVENT_COALESCE_MS = 50L
        private const val SETTLE_MS = 250L
        private const val STROKE_DP = 1.5f
    }
}
