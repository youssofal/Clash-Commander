package com.yoyostudios.clashcompanion.overlay

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yoyostudios.clashcompanion.accessibility.ClashCompanionAccessibilityService
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.util.Coordinates

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "ClashCompanion"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var statusText: TextView? = null

    fun show() {
        if (overlayView != null) return

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            setPadding(24, 16, 24, 16)
        }

        // Status text
        val status = TextView(context).apply {
            text = "Clash Companion Ready"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        statusText = status
        layout.addView(status)

        // Test tap card slot 1
        val btnTapSlot = Button(context).apply {
            text = "Tap Card Slot 1"
            textSize = 12f
            setOnClickListener {
                val svc = ClashCompanionAccessibilityService.instance
                if (svc == null) {
                    updateStatus("ERROR: Accessibility not enabled")
                    return@setOnClickListener
                }
                val (x, y) = Coordinates.CARD_SLOT_1
                val ok = svc.safeTap(x, y)
                updateStatus(if (ok) "Tapped slot 1 ($x, $y)" else "BLOCKED — release screen")
            }
        }
        layout.addView(btnTapSlot)

        // Test play card: slot 1 -> left bridge
        val btnPlayCard = Button(context).apply {
            text = "Play Slot 1 -> Left Bridge"
            textSize = 12f
            setOnClickListener {
                val svc = ClashCompanionAccessibilityService.instance
                if (svc == null) {
                    updateStatus("ERROR: Accessibility not enabled")
                    return@setOnClickListener
                }
                val (sx, sy) = Coordinates.CARD_SLOT_1
                val (zx, zy) = Coordinates.LEFT_BRIDGE
                svc.playCard(sx, sy, zx, zy)
                updateStatus("Playing slot 1 -> left bridge")
            }
        }
        layout.addView(btnPlayCard)

        // Test play card: slot 2 -> right bridge
        val btnPlayCard2 = Button(context).apply {
            text = "Play Slot 2 -> Right Bridge"
            textSize = 12f
            setOnClickListener {
                val svc = ClashCompanionAccessibilityService.instance
                if (svc == null) {
                    updateStatus("ERROR: Accessibility not enabled")
                    return@setOnClickListener
                }
                val (sx, sy) = Coordinates.CARD_SLOT_2
                val (zx, zy) = Coordinates.RIGHT_BRIDGE
                svc.playCard(sx, sy, zx, zy)
                updateStatus("Playing slot 2 -> right bridge")
            }
        }
        layout.addView(btnPlayCard2)

        // Save screenshot test button
        val btnScreenshot = Button(context).apply {
            text = "Save Screenshot"
            textSize = 12f
            setOnClickListener {
                val frame = ScreenCaptureService.getLatestFrame()
                if (frame == null) {
                    updateStatus("ERROR: No frame (start capture first)")
                    return@setOnClickListener
                }
                val saved = saveScreenshot(frame)
                if (saved) {
                    updateStatus("Saved! ${frame.width}x${frame.height}")
                } else {
                    updateStatus("ERROR: Failed to save screenshot")
                }
            }
        }
        layout.addView(btnScreenshot)

        // Close overlay button
        val btnClose = Button(context).apply {
            text = "Close Overlay"
            textSize = 12f
            setOnClickListener { hide() }
        }
        layout.addView(btnClose)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        overlayView = layout
        windowManager.addView(layout, params)
        Log.i(TAG, "Overlay shown")
    }

    fun hide() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
            statusText = null
            Log.i(TAG, "Overlay hidden")
        }
    }

    fun updateStatus(message: String) {
        statusText?.text = message
        Log.i(TAG, "Status: $message")
    }

    private fun saveScreenshot(bitmap: Bitmap): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "clash_test_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ClashCompanion")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Log.i(TAG, "Screenshot saved: ${bitmap.width}x${bitmap.height}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot: ${e.message}")
            false
        }
    }
}
