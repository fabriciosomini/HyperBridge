package com.d4viddf.hyperbridge.service

/**
 * Early warning that the HyperOS islands are about to change: an island notification was posted
 * or removed. The notification listener hears about it before SystemUI starts animating, while
 * the accessibility tree only reflects the animation 100-180 ms later, so
 * [IslandAccessibilityService] uses this to hide its rings in time.
 *
 * In-process only: the listener and the accessibility service share the app's default process.
 */
object IslandChangeHint {
    @Volatile
    var listener: (() -> Unit)? = null

    fun fire() {
        listener?.invoke()
    }
}
