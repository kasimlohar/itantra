package com.itantra.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Radar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.data.transport.TransportState
import com.itantra.presentation.theme.ITantraColors
import com.itantra.presentation.theme.ITantraShapes
import com.itantra.presentation.theme.ITantraType
import com.itantra.presentation.transceiver.SosToastState
import com.itantra.presentation.transceiver.TransceiverIntent
import com.itantra.presentation.transceiver.TransceiverUiState

@Composable
fun HomeScreen(
    state: TransceiverUiState,
    onIntent: (TransceiverIntent) -> Unit,
    onNavigate: (String) -> Unit
) {
    var showSosDialog by remember { mutableStateOf(false) }

    // SOS confirmation dialog
    // Always kept in the composition tree to avoid Compose Stack.pop() crash
    // caused by changing group count on recomposition
    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            containerColor = Color.White,
            title = {
                Text(
                    "Send SOS Alert?",
                    fontWeight = FontWeight.Bold,
                    color = ITantraColors.TextPrimary
                )
            },
            text = {
                Text(
                    "This will broadcast an emergency SOS signal to all connected peers on the mesh network.",
                    style = ITantraType.body.copy(color = ITantraColors.TextSecondary)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onIntent(TransceiverIntent.SendAlert("SOS"))
                        showSosDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Error),
                    shape = ITantraShapes.Button
                ) {
                    Text("Send SOS", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) {
                    Text("Cancel", color = ITantraColors.TextSecondary)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ITantraColors.Background)
            .verticalScroll(rememberScrollState())
    ) {
        // ── App wordmark ───────────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp)
        ) {
            Text(
                text = "iTantra",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = ITantraColors.TextPrimary,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Offline Mesh Communication",
                style = ITantraType.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Mesh status panel ─────────────────────────────────────────────────
        MeshStatusPanel(state = state)

        Spacer(Modifier.height(12.dp))

        // ── Performance HUD card ──────────────────────────────────────────
        PerfHudCard(
            perf = state.devicePerf,
            expanded = state.showPerfHud,
            onToggleExpand = { onIntent(TransceiverIntent.TogglePerfHud) }
        )

        // ── SOS received banner ────────────────────────────────────────────
        AnimatedVisibility(
            visible = state.sosToast is SosToastState.Success && !state.findMyPhoneActive,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(300))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(1.dp, ITantraColors.Error.copy(alpha = 0.3f), ITantraShapes.Small),
                shape = ITantraShapes.Small,
                color = ITantraColors.Error.copy(alpha = 0.07f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️ SOS received",
                        style = ITantraType.body.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ITantraColors.Error
                        )
                    )
                    Text(
                        text = "Find Now →",
                        style = ITantraType.body.copy(
                            fontWeight = FontWeight.Bold,
                            color = ITantraColors.Primary
                        ),
                        modifier = Modifier.clickable { onNavigate("findphone") }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Section label ──────────────────────────────────────────────────────
        Text(
            text = "QUICK ACTIONS",
            style = ITantraType.caption.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = ITantraColors.TextSecondary
            ),
            modifier = Modifier.padding(start = 20.dp, bottom = 10.dp)
        )

        // ── 2 × 2 Action grid ─────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: Transceiver + Radar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionCard(
                    title = "Transceiver",
                    description = "Voice communication",
                    icon = Icons.Default.Mic,
                    iconTint = Color.White,
                    iconBg = ITantraColors.Primary,
                    isPrimary = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("transceiver") }
                )
                ActionCard(
                    title = "Mesh Radar",
                    description = "Nearby devices",
                    icon = Icons.Outlined.Radar,
                    iconTint = ITantraColors.TextPrimary,
                    iconBg = ITantraColors.SurfaceVariant,
                    isPrimary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("radar") }
                )
            }
            // Row 2: Downloads + Emergency SOS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionCard(
                    title = "Downloads",
                    description = "Offline AI models",
                    icon = Icons.Default.Download,
                    iconTint = ITantraColors.TextPrimary,
                    iconBg = ITantraColors.SurfaceVariant,
                    isPrimary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("downloads") }
                )
                ActionCard(
                    title = "Emergency SOS",
                    description = "Broadcast alert",
                    icon = Icons.Default.Warning,
                    iconTint = Color.White,
                    iconBg = ITantraColors.Error,
                    isPrimary = false,
                    isWarning = true,
                    modifier = Modifier.weight(1f),
                    onClick = { showSosDialog = true }
                )
            }
            // Row 3: Rescue Beacon + Siren Locate
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionCard(
                    title = "Rescue Beacon",
                    description = "Locate via Bluetooth",
                    icon = Icons.Default.LocationOn,
                    iconTint = ITantraColors.TextPrimary,
                    iconBg = ITantraColors.SurfaceVariant,
                    isPrimary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("findphone") }
                )
                ActionCard(
                    title = "Siren Locate",
                    description = "Audio proximity nav",
                    icon = Icons.Default.GraphicEq,
                    iconTint = ITantraColors.TextPrimary,
                    iconBg = ITantraColors.SurfaceVariant,
                    isPrimary = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("locate") }
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

// ── Mesh status panel ───────────────────────────────────────────────────────────
@Composable
private fun MeshStatusPanel(state: TransceiverUiState) {
    val (statusColor, statusText, _) = when (state.connectionState) {
        TransportState.CONNECTED -> Triple(ITantraColors.Success, "Connected", "Peer active")
        TransportState.CONNECTING -> Triple(ITantraColors.Amber, "Connecting…", "Establishing link")
        TransportState.DISCOVERING -> Triple(ITantraColors.Blue, "Discovering", "Scanning mesh network")
        else -> Triple(ITantraColors.TextSecondary, "Offline", "No network")
    }

    val peerCount = state.discoveredPeers.size

    // Pulsing dot animation for discovering state
    val infiniteTransition = rememberInfiniteTransition(label = "status")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot"
    )
    val shouldPulse = state.connectionState == TransportState.DISCOVERING ||
            state.connectionState == TransportState.CONNECTING

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = ITantraShapes.Card,
        color = ITantraColors.Surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Top row: label + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MESH NETWORK",
                    style = ITantraType.caption.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = ITantraColors.TextSecondary
                    )
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = if (shouldPulse) dotAlpha else 1f))
                    )
                    Text(
                        text = statusText,
                        style = ITantraType.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = ITantraColors.Border, thickness = 0.5.dp)
            Spacer(Modifier.height(10.dp))

            // Details row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Device ID
                Column {
                    Text(
                        text = "This Device",
                        style = ITantraType.caption
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = state.localIp,
                        style = ITantraType.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            color = ITantraColors.TextPrimary
                        )
                    )
                }
                // Peer count / Gateway
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Peers Found",
                        style = ITantraType.caption
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = if (peerCount == 0) "—" else "$peerCount device${if (peerCount == 1) "" else "s"}",
                        style = ITantraType.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            color = if (peerCount > 0) ITantraColors.Success else ITantraColors.TextPrimary
                        )
                    )
                }
            }
        }
    }
}

// ── Action card (replaces QuickCard) ───────────────────────────────────────────
@Composable
private fun ActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    isPrimary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false
) {
    val borderColor = when {
        isWarning -> ITantraColors.Error.copy(alpha = 0.25f)
        isPrimary -> ITantraColors.Primary.copy(alpha = 0.15f)
        else -> ITantraColors.Border
    }

    Surface(
        modifier = modifier
            .clip(ITantraShapes.Card)
            .border(width = 1.dp, color = borderColor, shape = ITantraShapes.Card)
            .clickable(onClick = onClick),
        shape = ITantraShapes.Card,
        color = Color.White,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = ITantraType.body.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    style = ITantraType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
