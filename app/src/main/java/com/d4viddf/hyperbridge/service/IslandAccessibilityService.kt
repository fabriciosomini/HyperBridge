package com.d4viddf.hyperbridge.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.d4viddf.hyperbridge.data.AppPreferences
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
 * `small_island_view` (the circle). Expanded cards are skipped by height. The rings fade out as
 * soon as anything changes (bounds, or HyperOS's `fake_*` animation twins appearing) and fade back
 * in once the bounds hold still. After every island event the tree is polled briefly, since the
 * animation itself runs without further events.
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
    private var enabled = true

    private var pollUntil = 0L
    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            if (SystemClock.uptimeMillis() < pollUntil) handler.postDelayed(this, POLL_MS)
        }
    }
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
        scope.launch {
            AppPreferences(applicationContext).islandBorderRingFlow.collect {
                enabled = it
                refresh()
            }
        }
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.packageName?.toString() != ISLAND_PACKAGE
        ) return
        // Check right away, then keep polling for a moment: the animation an event kicks off
        // reports no further events of its own.
        pollUntil = SystemClock.uptimeMillis() + POLL_WINDOW_MS
        handler.removeCallbacks(pollRunnable)
        pollRunnable.run()
    }

    private fun refresh() {
        val scan = scanIslands()
        if (scan.animating) {
            // Mid-animation: hide at the slightest change and wait for things to settle.
            handler.removeCallbacks(showRunnable)
            pendingBounds = emptyList()
            removeRings()
            return
        }
        val bounds = scan.pills
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

    private class Scan(val pills: MutableList<Rect> = mutableListOf(), var animating: Boolean = false)

    private fun scanIslands(): Scan {
        val scan = Scan()
        if (!enabled) return scan
        val all = runCatching { windows }.getOrNull().orEmpty()
        for (w in all) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() != ISLAND_PACKAGE) continue
            collectPillNodes(root, 0, scan)
        }
        scan.pills.sortBy { it.left }
        return scan
    }

    private fun collectPillNodes(node: AccessibilityNodeInfo, depth: Int, scan: Scan) {
        if (depth > MAX_NODE_DEPTH) return
        val id = node.viewIdResourceName
        if (id != null && FAKE_VIEW_IDS.any { id.endsWith(":id/$it") }) {
            if (node.isVisibleToUser) scan.animating = true
            return
        }
        if (id != null && PILL_VIEW_IDS.any { id.endsWith(":id/$it") }) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            val maxHeightPx = MAX_PILL_HEIGHT_DP * resources.displayMetrics.density
            if (node.isVisibleToUser && !r.isEmpty && r.height() <= maxHeightPx) scan.pills += r
            return
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectPillNodes(child, depth + 1, scan)
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
            alpha = 0f
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
            view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            rings += view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add island ring", e)
        }
    }

    private fun removeRings(fade: Boolean = true) {
        rings.forEach { view ->
            view.animate().cancel()
            if (fade) {
                view.animate().alpha(0f).setDuration(FADE_OUT_MS)
                    .withEndAction { runCatching { windowManager?.removeView(view) } }
                    .start()
            } else {
                runCatching { windowManager?.removeView(view) }
            }
        }
        rings.clear()
        ringBounds = emptyList()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        removeRings(fade = false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeRings(fade = false)
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
        // Present only while HyperOS animates an island.
        private val FAKE_VIEW_IDS = listOf("fake_big_island_view", "fake_small_island_view")
        private const val POLL_MS = 32L
        private const val POLL_WINDOW_MS = 600L
        private const val SETTLE_MS = 150L
        private const val FADE_IN_MS = 150L
        private const val FADE_OUT_MS = 80L
        private const val STROKE_DP = 1.5f
    }
}
