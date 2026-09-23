package com.itantra.presentation.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.data.transport.DiscoveredPeer
import com.itantra.presentation.theme.AppHeader
import com.itantra.presentation.theme.ITantraColors
import com.itantra.presentation.theme.ITantraShapes
import com.itantra.presentation.theme.ITantraType
import com.itantra.presentation.theme.PillType
import com.itantra.presentation.theme.PrimaryButton
import com.itantra.presentation.theme.SecondaryButton
import com.itantra.presentation.theme.StatusPill
import com.itantra.presentation.transceiver.PeerConnectionStatus
import com.itantra.presentation.transceiver.TransceiverIntent
import com.itantra.presentation.transceiver.TransceiverUiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarScreen(
    state: TransceiverUiState,
    onIntent: (TransceiverIntent) -> Unit,
    deviceId: String
) {
    // Use isDiscoveryActive for the sweep animation — not the transport connection state.
    // This makes the radar honest: sweep only runs when the user actively scans.
    val isScanning = state.isDiscoveryActive
    var selectedPeer by remember { mutableStateOf<DiscoveredPeer?>(null) }

    val pillType = when {
        state.discoveredPeers.isNotEmpty() -> PillType.CONNECTED
        isScanning -> PillType.SEARCHING
        else -> PillType.STANDBY
    }
    val pillLabel = when {
        state.discoveredPeers.isNotEmpty() -> "${state.discoveredPeers.size} FOUND"
        isScanning -> "SCANNING"
        else -> "STANDBY"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ITantraColors.Background)
    ) {
        // ── Header ─────────────────────────────────────────────────────────────
        AppHeader(
            title = "Mesh Radar",
            subtitle = "Device · $deviceId",
            topEnd = {
                StatusPill(label = pillLabel, pillType = pillType)
            }
        )

        // ── Action buttons ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryButton(
                text = "Search Peers",
                // Dispatches RestartDiscovery which actually restarts the UDP scanner
                onClick = { onIntent(TransceiverIntent.RestartDiscovery) },
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "Host Beacon",
                // Starts the TCP server so remote peers can connect to this device
                onClick = { onIntent(TransceiverIntent.ConnectPeer("")) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Radar canvas — constrained to ~42% of screen, not full-height ──────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            RadarCanvas(
                peers = state.discoveredPeers,
                isScanning = isScanning,
                selectedPeer = selectedPeer,
                onPeerTapped = { peer ->
                    selectedPeer = if (selectedPeer?.ip == peer.ip) null else peer
                },
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── Nearby devices list ─────────────────────────────────────────────────
        val peerCount = state.discoveredPeers.size
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Nearby Devices",
                style = ITantraType.sectionTitle.copy(fontSize = 15.sp)
            )
            Text(
                text = if (peerCount == 0) "None" else "$peerCount found",
                style = ITantraType.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (peerCount > 0) ITantraColors.Success else ITantraColors.TextSecondary
                )
            )
        }

        if (state.discoveredPeers.isEmpty()) {
            // Honest empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ITantraShapes.Card,
                    color = ITantraColors.Surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isScanning) "Scanning for nearby devices…" else "No devices found",
                            style = ITantraType.body.copy(
                                color = ITantraColors.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        )
                        if (!isScanning) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Tap Search Peers to scan",
                                style = ITantraType.bodySmall.copy(textAlign = TextAlign.Center)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.discoveredPeers, key = { it.ip }) { peer ->
                    val status = state.peerConnectionStatus(peer.ip)
                    PeerRow(
                        peer = peer,
                        isSelected = selectedPeer?.ip == peer.ip,
                        status = status,
                        onTap = {
                            selectedPeer = if (selectedPeer?.ip == peer.ip) null else peer
                        },
                        onConnect = { onIntent(TransceiverIntent.ConnectPeer(peer.ip)) },
                        onDisconnect = { onIntent(TransceiverIntent.Disconnect) }
                    )
                }
            }
        }
    }
}

// ── Peer list row ───────────────────────────────────────────────────────────────
@Composable
private fun PeerRow(
    peer: DiscoveredPeer,
    isSelected: Boolean,
    status: PeerConnectionStatus,
    onTap: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit = {}
) {
    val isConnected = status == PeerConnectionStatus.CONNECTED
    val isConnecting = status == PeerConnectionStatus.CONNECTING
    val isFailed = status == PeerConnectionStatus.FAILED

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ITantraShapes.Card)
            .border(
                width = if (isSelected || isConnected) 1.5.dp else 0.5.dp,
                color = when {
                    isConnected   -> ITantraColors.Success
                    isConnecting  -> ITantraColors.Amber
                    isFailed      -> ITantraColors.Error.copy(alpha = 0.5f)
                    isSelected    -> ITantraColors.Primary
                    else          -> ITantraColors.Border
                },
                shape = ITantraShapes.Card
            )
            .clickable(onClick = onTap),
        shape = ITantraShapes.Card,
        color = when {
            isConnected   -> ITantraColors.Success.copy(alpha = 0.04f)
            isConnecting  -> ITantraColors.Amber.copy(alpha = 0.04f)
            isSelected    -> ITantraColors.Primary.copy(alpha = 0.04f)
            else          -> ITantraColors.Background
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isConnected   -> ITantraColors.Success
                                isConnecting  -> ITantraColors.Amber
                                isFailed      -> ITantraColors.Error
                                else          -> ITantraColors.Border
                            }
                        )
                )
                Column {
                    Text(
                        text = peer.name,
                        style = ITantraType.body.copy(fontWeight = FontWeight.Medium),
                        fontSize = 14.sp
                    )
                    Text(
                        text = peer.ip,
                        style = ITantraType.caption.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
            when (status) {
                PeerConnectionStatus.DISCOVERED -> {
                    Text(
                        text = "Connect →",
                        style = ITantraType.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ITantraColors.TextPrimary
                        ),
                        modifier = Modifier
                            .clip(ITantraShapes.Small)
                            .clickable(onClick = onConnect)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                PeerConnectionStatus.CONNECTING -> {
                    Text(
                        text = "Connecting…",
                        style = ITantraType.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ITantraColors.Amber
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                PeerConnectionStatus.CONNECTED -> {
                    Text(
                        text = "Connected ✓",
                        style = ITantraType.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ITantraColors.Success
                        ),
                        modifier = Modifier
                            .clip(ITantraShapes.Small)
                            .clickable(onClick = onDisconnect)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                PeerConnectionStatus.FAILED -> {
                    Text(
                        text = "Retry →",
                        style = ITantraType.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ITantraColors.Error
                        ),
                        modifier = Modifier
                            .clip(ITantraShapes.Small)
                            .clickable(onClick = onConnect)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                PeerConnectionStatus.DISCONNECTED -> {
                    Text(
                        text = "Reconnect →",
                        style = ITantraType.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ITantraColors.TextPrimary
                        ),
                        modifier = Modifier
                            .clip(ITantraShapes.Small)
                            .clickable(onClick = onConnect)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ── Radar Canvas ───────────────────────────────────────────────────────────────
@Composable
private fun RadarCanvas(
    peers: List<DiscoveredPeer>,
    isScanning: Boolean,
    selectedPeer: DiscoveredPeer?,
    onPeerTapped: (DiscoveredPeer) -> Unit,
    modifier: Modifier = Modifier
) {
    val ringColor = Color(0xFFDDDDDD)
    val gridColor = Color(0xFFE8E8E8)
    val centerColor = Color(0xFF111111)
    val peerColor = Color(0xFF111111)
    val selectedPeerColor = Color(0xFF2563EB)

    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = minOf(cx, cy) - 2f

        // Background fill
        drawCircle(color = Color(0xFFF8F9FA), radius = maxR)

        // Outer border
        drawCircle(
            color = Color(0xFF333333),
            radius = maxR,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Concentric rings (3)
        listOf(0.33f, 0.56f, 0.78f).forEach { frac ->
            drawCircle(
                color = ringColor,
                radius = maxR * frac,
                style = Stroke(width = 0.8.dp.toPx())
            )
        }

        // Subtle crosshair grid lines
        val lineColor = gridColor
        drawLine(lineColor, Offset(cx, cy - maxR), Offset(cx, cy + maxR), 0.6.dp.toPx())
        drawLine(lineColor, Offset(cx - maxR, cy), Offset(cx + maxR, cy), 0.6.dp.toPx())
        // Diagonal grid lines at 45°
        val d = (maxR * 0.707f)
        drawLine(lineColor, Offset(cx - d, cy - d), Offset(cx + d, cy + d), 0.6.dp.toPx())
        drawLine(lineColor, Offset(cx + d, cy - d), Offset(cx - d, cy + d), 0.6.dp.toPx())

        // Sweep arc — only when actively scanning (user-initiated)
        if (isScanning) {
            rotate(sweepAngle, pivot = Offset(cx, cy)) {
                drawArc(
                    color = Color(0xFF111111).copy(alpha = 0.07f),
                    startAngle = -45f,
                    sweepAngle = 45f,
                    useCenter = true,
                    topLeft = Offset(cx - maxR, cy - maxR),
                    size = size
                )
                drawLine(
                    color = Color(0xFF111111).copy(alpha = 0.28f),
                    start = Offset(cx, cy),
                    end = Offset(cx + maxR, cy),
                    strokeWidth = 1.2.dp.toPx()
                )
            }
        }

        // Center device marker — "this device"
        drawCircle(color = centerColor, radius = 7.dp.toPx(), center = Offset(cx, cy))
        drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(cx, cy))

        // Peer dots — evenly distributed, placed on real rings (no fake positions)
        peers.forEachIndexed { idx, peer ->
            val angle = (idx.toFloat() / peers.size.coerceAtLeast(1)) * 2 * PI.toFloat() - (PI / 2).toFloat()
            // Distribute peers across middle rings only (not innermost, not edge)
            val ringFractions = listOf(0.38f, 0.58f, 0.72f)
            val r = maxR * ringFractions[idx % ringFractions.size]
            val px = cx + r * cos(angle.toDouble()).toFloat()
            val py = cy + r * sin(angle.toDouble()).toFloat()
            val isSelected = selectedPeer?.ip == peer.ip
            val dotColor = if (isSelected) selectedPeerColor else peerColor
            drawCircle(color = dotColor, radius = 7.dp.toPx(), center = Offset(px, py))
            drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(px, py))
            // Selection ring
            if (isSelected) {
                drawCircle(
                    color = selectedPeerColor.copy(alpha = 0.25f),
                    radius = 13.dp.toPx(),
                    center = Offset(px, py),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}
