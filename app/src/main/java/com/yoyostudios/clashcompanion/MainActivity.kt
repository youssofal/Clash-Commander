package com.yoyostudios.clashcompanion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yoyostudios.clashcompanion.accessibility.ClashCompanionAccessibilityService
import com.yoyostudios.clashcompanion.capture.ScreenCaptureService
import com.yoyostudios.clashcompanion.overlay.OverlayManager
import com.yoyostudios.clashcompanion.util.Coordinates

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private var overlayManager: OverlayManager? = null
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Coordinates.init(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val btnOverlayPerm = findViewById<Button>(R.id.btnOverlayPermission)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnCapture = findViewById<Button>(R.id.btnCapture)
        val btnStartOverlay = findViewById<Button>(R.id.btnStartOverlay)

        // Register MediaProjection result handler
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                updateStatus("Screen capture started")
            } else {
                updateStatus("Screen capture permission denied")
            }
        }

        btnOverlayPerm.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                updateStatus("Overlay permission already granted")
            }
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnCapture.setOnClickListener {
            if (ScreenCaptureService.instance != null) {
                updateStatus("Screen capture already running")
                return@setOnClickListener
            }
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        btnStartOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                updateStatus("ERROR: Grant overlay permission first")
                return@setOnClickListener
            }
            if (ClashCompanionAccessibilityService.instance == null) {
                updateStatus("ERROR: Enable Accessibility Service first")
                return@setOnClickListener
            }
            if (overlayManager == null) {
                overlayManager = OverlayManager(applicationContext)
            }
            overlayManager?.show()
            updateStatus("Overlay started — switch to Clash Royale")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = ClashCompanionAccessibilityService.instance != null
        val captureOk = ScreenCaptureService.instance != null

        val sb = StringBuilder()
        sb.appendLine("── Clash Companion Status ──")
        sb.appendLine()
        sb.appendLine("Overlay Permission: ${if (overlayOk) "GRANTED" else "NOT GRANTED"}")
        sb.appendLine("Accessibility Service: ${if (accessibilityOk) "ENABLED" else "NOT ENABLED"}")
        sb.appendLine("Screen Capture: ${if (captureOk) "RUNNING" else "NOT STARTED"}")
        sb.appendLine()
        if (overlayOk && accessibilityOk) {
            sb.appendLine("Ready! Start capture, then overlay, then switch to CR.")
        } else {
            sb.appendLine("Complete the setup steps above first.")
        }

        statusText.text = sb.toString()
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayManager?.hide()
    }
}
