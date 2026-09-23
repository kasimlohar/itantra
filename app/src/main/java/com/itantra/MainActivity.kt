package com.itantra

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
import com.itantra.presentation.ITantraApp
import com.itantra.presentation.transceiver.TransceiverViewModel
import com.itantra.presentation.transceiver.TransceiverIntent


@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: TransceiverViewModel by viewModels()
    private var lastDown = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions first. Service will only start once RECORD_AUDIO is granted.
        // On Android 14+ (targetSdk 34), starting a microphone FGS without RECORD_AUDIO
        // already granted at runtime throws a SecurityException and crashes the app.
        try {
            val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                perms.add(android.Manifest.permission.BLUETOOTH_SCAN)
                perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            val missing = perms.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 101)
            } else {
                // All permissions already granted — safe to start service immediately
                startTransceiverService()
            }
        } catch (_: Throwable) {
            // Permissions check failed — still try to start service (will fail gracefully)
            startTransceiverService()
        }

        setContent {
            ITantraApp(viewModel = viewModel)
        }
    }

    private fun startTransceiverService() {
        try {
            val serviceIntent = android.content.Intent(this, com.itantra.service.TransceiverService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Throwable) {
            android.util.Log.e("iTantra", "Failed to start TransceiverService", e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            // Start service after user responds to permissions dialog.
            // RECORD_AUDIO must be granted for the microphone FGS to start on Android 14+.
            val audioGranted = permissions.indexOfFirst {
                it == android.Manifest.permission.RECORD_AUDIO
            }.let { idx -> idx >= 0 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED }

            if (audioGranted) {
                startTransceiverService()
            } else {
                android.util.Log.w("iTantra", "RECORD_AUDIO denied — TransceiverService not started (mic unavailable)")
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event != null && event.repeatCount > 0) return true
            val now = System.currentTimeMillis()
            if (now - lastDown < 80) return true
            lastDown = now
            viewModel.process(TransceiverIntent.FloorRequest)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            viewModel.process(TransceiverIntent.FloorRelease)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
