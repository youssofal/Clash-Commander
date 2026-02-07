package com.yoyostudios.clashcompanion.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClashCompanionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClashCompanion"
        var instance: ClashCompanionAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isTouchActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                isTouchActive = true
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                handler.postDelayed({ isTouchActive = false }, 100) // 100ms cooldown
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "AccessibilityService destroyed")
    }

    /**
     * Safe tap that avoids the Android 15/16 dispatchGesture freeze bug.
     * Won't fire if user is currently touching the screen.
     */
    fun safeTap(x: Float, y: Float, callback: GestureResultCallback? = null): Boolean {
        if (isTouchActive) {
            Log.w(TAG, "Touch active — skipping gesture (freeze prevention)")
            return false
        }
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, callback, null)
    }

    /**
     * Safe drag from one point to another.
     */
    fun safeDrag(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        durationMs: Long = 200,
        callback: GestureResultCallback? = null
    ): Boolean {
        if (isTouchActive) {
            Log.w(TAG, "Touch active — skipping drag (freeze prevention)")
            return false
        }
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, callback, null)
    }

    /**
     * Play a card: tap the card slot, wait briefly, then tap the target zone.
     */
    fun playCard(slotX: Float, slotY: Float, zoneX: Float, zoneY: Float) {
        val tapOk = safeTap(slotX, slotY, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Card slot tapped, now tap the zone after a short delay
                handler.postDelayed({
                    safeTap(zoneX, zoneY)
                }, 80)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Card slot tap cancelled")
            }
        })
        if (!tapOk) {
            Log.w(TAG, "playCard failed — touch active, release screen first")
        }
    }
}
