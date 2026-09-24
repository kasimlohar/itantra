package com.itantra.presentation.locate

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
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

private const val MAX_DISTANCE_FOR_RING = 20f

/**
 * LocateScreen — "Siren Navigation" for locating a trapped person without GPS.
 *
 * A sonar canvas shows expanding rings that pulse faster as the rescuer gets closer.
 * The siren tone played on the device mirrors this: higher frequency = nearer target.
 *
 * "Trigger Remote Siren" sends a FLAG_SIREN frame to make the peer's device emit sound.
 */
@Composable
fun LocateScreen(
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
        if (granted) onIntent(TransceiverIntent.StartSirenLocate)
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
                    "Siren Locate uses Bluetooth signal strength to gauge proximity. " +
                    "No location data is stored or sent over the internet.",
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

    // ── Sonar animation speed = siren frequency ───────────────────────────────
    val freqHz = state.sirenFreqHz.coerceIn(200, 2000)
    // Pulse duration: one full expand = 1000ms / freq, clamped 300..1200ms
    val pulseDurationMs = (1_000_000 / freqHz).coerceIn(300, 1200)

    val infiniteTransition = rememberInfiniteTransition(label = "sonar")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sonarPhase"
    )

    // Proximity colour: lerp outer→inner based on distance
    val metres = state.sirenDistanceMetres
    val proximity = if (metres >= 0f) (1f - metres / MAX_DISTANCE_FOR_RING).coerceIn(0f, 1f) else 0f
    val ringColor = lerp(ITantraColors.Error, ITantraColors.Success, proximity)

    val distanceLabel = if (metres >= 0f) "~%.0f m (est.)".format(metres) else "Measuring…"
    val pillLabel = if (state.sirenLocateActive) "ACTIVE" else "STANDBY"
    val pillType = if (state.sirenLocateActive) PillType.CONNECTED else PillType.STANDBY

    // ── Layout ────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ITantraColors.Background)
            .verticalScroll(rememberScrollState())
    ) {
        AppHeader(
            title = "Locate via Siren",
            subtitle = "Audio proximity navigation — no GPS",
            topEnd = { StatusPill(label = pillLabel, pillType = pillType) }
        )

        HorizontalDivider(color = ITantraColors.Border, thickness = 0.5.dp)

        Spacer(Modifier.height(24.dp))

        // ── Sonar canvas ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(260.dp)
                    .semantics { contentDescription = "Sonar distance indicator" }
            ) {
                val maxRadius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val offsets = listOf(0f, 0.33f, 0.66f)

                for (offset in offsets) {
                    val p = ((phase + offset) % 1f)
                    val radius = maxRadius * p
                    val alpha = (1f - p).coerceIn(0f, 1f) * 0.7f

                    drawCircle(
                        color = ringColor.copy(alpha = alpha * 0.25f),
                        radius = radius,
                        center = center
                    )
                    drawCircle(
                        color = ringColor.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.5f)
                    )
                }

                // Static centre dot
                drawCircle(
                    color = ringColor,
                    radius = 12f,
                    center = center
                )
            }

            // Distance + frequency overlay
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 140.dp)
            ) {
                Text(
                    text = distanceLabel,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (metres >= 0f) ringColor else ITantraColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "$freqHz Hz",
                    style = ITantraType.bodySmall.copy(
                        color = ITantraColors.TextSecondary,
                        fontWeight = FontWeight.Medium
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── How-to card ───────────────────────────────────────────────────────
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
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = ITantraColors.TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        "Walk toward the signal.",
                        style = ITantraType.body.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "Beeping speeds up as you get closer. Continuous tone = you've found them.",
                        style = ITantraType.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Action buttons ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryButton(
                text = "▶ Start Locate",
                onClick = { permLauncher.launch(permissions) },
                enabled = !state.sirenLocateActive,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "⬛ Stop",
                onClick = { onIntent(TransceiverIntent.StopSirenLocate) },
                enabled = state.sirenLocateActive,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Remote siren trigger ──────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val cmdStatus = state.sirenCommandStatus
            val sirenActive = cmdStatus == com.itantra.presentation.transceiver.SirenCommandStatus.ACTIVE

            // ── Start Siren button ────────────────────────────────────────────
            Button(
                onClick = { onIntent(TransceiverIntent.TriggerRemoteSiren) },
                enabled = !sirenActive &&
                    cmdStatus != com.itantra.presentation.transceiver.SirenCommandStatus.SENDING,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = ITantraShapes.Button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ITantraColors.Primary,
                    contentColor = Color.White,
                    disabledContainerColor = ITantraColors.Primary.copy(alpha = 0.4f),
                    disabledContentColor = Color.White.copy(alpha = 0.6f)
                )
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    when (cmdStatus) {
                        com.itantra.presentation.transceiver.SirenCommandStatus.SENDING -> "Sending…"
                        com.itantra.presentation.transceiver.SirenCommandStatus.ACTIVE  -> "Siren Active on Target"
                        else -> "Start Siren on Target"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }

            // ── Stop Siren button ─────────────────────────────────────────────
            if (sirenActive) {
                androidx.compose.material3.OutlinedButton(
                    onClick = { onIntent(TransceiverIntent.StopRemoteSiren) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = ITantraShapes.Button,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = ITantraColors.Error
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, ITantraColors.Error.copy(alpha = 0.6f)
                    )
                ) {
                    Text("Stop Siren on Target", fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Status / error text ───────────────────────────────────────────
            Text(
                text = when (cmdStatus) {
                    com.itantra.presentation.transceiver.SirenCommandStatus.IDLE        -> "Activates siren on peer's device"
                    com.itantra.presentation.transceiver.SirenCommandStatus.SENDING     -> "Sending command to target…"
                    com.itantra.presentation.transceiver.SirenCommandStatus.ACTIVE      -> "✓ Target device is sounding"
                    com.itantra.presentation.transceiver.SirenCommandStatus.NO_TARGET   -> "⚠ Connect to a target device first"
                    com.itantra.presentation.transceiver.SirenCommandStatus.UNREACHABLE -> "⚠ Target unreachable — check connection"
                },
                style = ITantraType.caption.copy(
                    color = when (cmdStatus) {
                        com.itantra.presentation.transceiver.SirenCommandStatus.NO_TARGET,
                        com.itantra.presentation.transceiver.SirenCommandStatus.UNREACHABLE -> ITantraColors.Error
                        com.itantra.presentation.transceiver.SirenCommandStatus.ACTIVE      -> ITantraColors.Success
                        else -> ITantraColors.TextSecondary
                    }
                ),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}
