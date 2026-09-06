package com.itantra

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels
import com.itantra.presentation.transceiver.TransceiverViewModel
import com.itantra.presentation.transceiver.TransceiverIntent
import com.itantra.presentation.transceiver.TransceiverScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: TransceiverViewModel by viewModels()
    private var lastDown = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val serviceIntent = android.content.Intent(this, com.itantra.service.TransceiverService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Throwable) {
            try { android.util.Log.e("iTantra", "Failed to start TransceiverService", e) } catch (_: Throwable) {}
        }
        try {
            val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 101)
            }
        } catch (_: Throwable) {}
        setContent {
            val state by viewModel.state.collectAsState()
            TransceiverScreen(state = state, onIntent = { viewModel.process(it) })
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
