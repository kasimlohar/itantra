package com.itantra.presentation.findphone

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.presentation.theme.AppHeader
import com.itantra.presentation.theme.ITantraColors
import com.itantra.presentation.theme.ITantraShapes
import com.itantra.presentation.theme.ITantraType
import com.itantra.presentation.theme.PillType
import com.itantra.presentation.theme.PrimaryButton
import com.itantra.presentation.theme.SecondaryButton
import com.itantra.presentation.theme.StatusPill
import com.itantra.presentation.transceiver.TransceiverIntent
import com.itantra.presentation.transceiver.TransceiverUiState

private const val LABEL_HINT = "Walk toward the survivor's signal"
private const val LABEL_START = "Start Homing"
private const val LABEL_STOP = "Stop"
private const val LABEL_UNKNOWN = "Getting signal…"
private const val MAX_DISTANCE_METRES = 30f

/**
 * FindMyPhoneScreen — BLE RSSI proximity homing for disaster rescuers.
 *
 * Shows an animated concentric-ring canvas whose innermost rings fill from red to green
 * as the rescuer physically moves toward the SOS sender. Requires BLUETOOTH_SCAN permission.
 */
@Composable
fun FindMyPhoneScreen(
    state: TransceiverUiState,
    onIntent: (TransceiverIntent) -> Unit
) {
    // ── Permission handling ───────────────────────────────────────────────────
    var showRationale by remember { mutableStateOf(false) }
    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = if (Build.VERSION.SDK_INT >= 31) {
            grants[Manifest.permission.BLUETOOTH_SCAN] == true
        } else {
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
        if (granted) onIntent(TransceiverIntent.StartFindMyPhone)
        else showRationale = true
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            containerColor = Color.White,
            title = {
                Text(
                    "Bluetooth Scan Required",
                    fontWeight = FontWeight.Bold,
                    color = ITantraColors.TextPrimary
                )
            },
            text = {
                Text(
                    "This feature scans for nearby Bluetooth devices to estimate distance. " +
                    "No location data is stored or transmitted.",
                    style = ITantraType.body.copy(color = ITantraColors.TextSecondary)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRationale = false
                        permLauncher.launch(permissions)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                    shape = ITantraShapes.Button
                ) { Text("Allow", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text("Cancel", color = ITantraColors.TextSecondary)
                }
            }
        )
    }

    // ── Proximity metrics ─────────────────────────────────────────────────────
    val metres = state.findMyPhoneDistanceMetres
    val fillFraction = if (metres >= 0f) (1f - (metres / MAX_DISTANCE_METRES)).coerceIn(0f, 1f)
                       else 0f
    val animatedFill by animateFloatAsState(
        targetValue = fillFraction,
        animationSpec = tween(600),
        label = "proximity fill"
    )
    val ringColor = lerp(ITantraColors.Error, ITantraColors.Success, animatedFill)
    val distanceLabel = if (metres >= 0f) "~%.0f m".format(metres) else LABEL_UNKNOWN
    val pillType = if (state.findMyPhoneActive) PillType.SEARCHING else PillType.STANDBY
    val pillLabel = if (state.findMyPhoneActive) "SCANNING" else "STANDBY"

    // ── Layout ────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ITantraColors.Background)
            .verticalScroll(rememberScrollState())
    ) {
        AppHeader(
            title = "Rescue Beacon",
            subtitle = if (state.findMyPhonePeerName.isNotBlank())
                "Homing on: ${state.findMyPhonePeerName}"
            else
                "Homing in on survivor's beacon",
            topEnd = { StatusPill(label = pillLabel, pillType = pillType) }
        )

        HorizontalDivider(color = ITantraColors.Border, thickness = 0.5.dp)

        // ── Error banner ──────────────────────────────────────────────────────
        state.findMyPhoneError?.let { err ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                shape = ITantraShapes.Small,
                color = ITantraColors.Error.copy(alpha = 0.08f)
            ) {
                Text(
                    text = err,
                    style = ITantraType.bodySmall.copy(color = ITantraColors.Error),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Proximity ring canvas ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(260.dp)
                    .semantics { contentDescription = "Distance indicator" }
            ) {
                val maxRadius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val numRings = 4
                val filledRings = (animatedFill * numRings).toInt().coerceIn(0, numRings)

                for (i in numRings downTo 1) {
                    val radius = maxRadius * (i.toFloat() / numRings)
                    val isFilled = (numRings - i) < filledRings
                    if (isFilled) {
                        drawCircle(
                            color = ringColor.copy(alpha = 0.15f + 0.1f * (numRings - i)),
                            radius = radius,
                            center = center
                        )
                    }
                    drawCircle(
                        color = if (isFilled) ringColor else ITantraColors.Border,
                        radius = radius,
                        center = center,
                        style = Stroke(width = if (i == 1) 4f else 2f)
                    )
                }
            }

            // Distance text overlay
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = distanceLabel,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (metres >= 0f) ringColor else ITantraColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                if (metres >= 0f) {
                    Text(
                        text = if (metres < 2f) "You're close!" else "Keep moving",
                        style = ITantraType.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Hint card ─────────────────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = ITantraShapes.Card,
            color = ITantraColors.Surface
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = ITantraColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = LABEL_HINT,
                    style = ITantraType.body.copy(color = ITantraColors.TextSecondary)
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // ── Action buttons ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryButton(
                text = LABEL_START,
                onClick = { permLauncher.launch(permissions) },
                enabled = !state.findMyPhoneActive,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = LABEL_STOP,
                onClick = { onIntent(TransceiverIntent.StopFindMyPhone) },
                enabled = state.findMyPhoneActive,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}
