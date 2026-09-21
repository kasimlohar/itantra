package com.itantra.presentation.transceiver

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Design tokens
private val Navy = Color(0xFF1B2B4B)        // accent only (chips, send btn)
private val NavyLight = Color(0xFF2C3E6B)
private val PttIdle = Color(0xFF37474F)      // dark blue-grey — not navy slop
private val Surface = Color(0xFFF7F8FA)
private val DividerColor = Color(0xFFE4E7ED)
private val TextPrimary = Color(0xFF111827)
private val TextSecondary = Color(0xFF6B7280)
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFDC2626)
private val Amber = Color(0xFFD97706)
private val Blue = Color(0xFF2563EB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverScreen(
    state: TransceiverUiState,
    onIntent: (TransceiverIntent) -> Unit
) {
    var showConnectDialog by remember { mutableStateOf(false) }
    var peerIpInput by remember { mutableStateOf("192.168.43.1") }
    var messageInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to latest message
    LaunchedEffect(state.messageHistory.size) {
        if (state.messageHistory.isNotEmpty()) {
            listState.animateScrollToItem(state.messageHistory.size - 1)
        }
    }

    Scaffold(
        containerColor = Color.White,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White,
                        titleContentColor = TextPrimary
                    ),
                    title = {
                        Text(
                            "iTantra",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Navy
                        )
                    },
                    actions = {
                        // Mode toggle: PTT / Full Duplex
                        val isDuplex = state.channelMode == com.itantra.domain.model.TransmitMode.DUPLEX
                        CompactChip(
                            label = if (isDuplex) "FD" else "PTT",
                            onClick = { onIntent(TransceiverIntent.ToggleMode) },
                            containerColor = if (isDuplex) PttIdle else Surface,
                            contentColor = if (isDuplex) Color.White else TextSecondary,
                            borderColor = if (isDuplex) PttIdle else DividerColor
                        )
                        Spacer(Modifier.width(6.dp))
                        // Language selector
                        CompactChip(
                            label = when (state.srcLang) {
                                com.itantra.domain.model.Language.HINDI -> "HI"
                                com.itantra.domain.model.Language.ENGLISH -> "EN"
                                com.itantra.domain.model.Language.MARATHI -> "MR"
                                else -> "HI"
                            },
                            onClick = {
                                val next = when (state.srcLang) {
                                    com.itantra.domain.model.Language.HINDI -> com.itantra.domain.model.Language.ENGLISH
                                    com.itantra.domain.model.Language.ENGLISH -> com.itantra.domain.model.Language.MARATHI
                                    else -> com.itantra.domain.model.Language.HINDI
                                }
                                onIntent(TransceiverIntent.SelectLanguage(next, next))
                            },
                            containerColor = Surface,
                            contentColor = Navy,
                            borderColor = Navy.copy(alpha = 0.25f)
                        )
                        Spacer(Modifier.width(6.dp))
                        // SOS
                        CompactChip(
                            label = "SOS",
                            onClick = { onIntent(TransceiverIntent.SendAlert("SOS")) },
                            containerColor = Red,
                            contentColor = Color.White,
                            borderColor = Red
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                )
                // Connection status strip
                ConnectionStatusBar(
                    connectionState = state.connectionState,
                    localIp = state.localIp,
                    gatewayIp = state.gatewayIp,
                    onClick = {
                        onIntent(TransceiverIntent.RefreshNetwork)
                        showConnectDialog = true
                    }
                )
                HorizontalDivider(color = DividerColor, thickness = 1.dp)
            }
        }
    ) { padding ->

        LaunchedEffect(showConnectDialog) {
            if (showConnectDialog) onIntent(TransceiverIntent.RefreshNetwork)
        }

        if (showConnectDialog) {
            ConnectDialog(
                state = state,
                peerIpInput = peerIpInput,
                onIpChange = { peerIpInput = it },
                onConnect = { ip ->
                    onIntent(TransceiverIntent.ConnectPeer(ip.trim()))
                    showConnectDialog = false
                },
                onStartServer = {
                    onIntent(TransceiverIntent.ConnectPeer(""))
                    showConnectDialog = false
                },
                onDismiss = { showConnectDialog = false }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Message feed ──────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (state.messageHistory.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No messages yet\nHold PTT to transmit",
                                color = TextSecondary,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
                items(state.messageHistory, key = { it.timestamp }) { item ->
                    MessageBubble(item = item)
                }
            }

            // ── Live transcript ───────────────────────────────────
            if (state.currentTranscript.isNotBlank() &&
                state.pttUiState == PttUiState.LISTENING
            ) {
                TranscriptBar(text = state.currentTranscript)
            }

            // ── Alert banner ──────────────────────────────────────
            if (state.isAlertActive && state.alertTranscript != null) {
                AlertBanner(text = state.alertTranscript)
            }

            HorizontalDivider(color = DividerColor, thickness = 1.dp)

            // ── Transmit zone ─────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(top = 12.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Section label
                    Text(
                        text = "TRANSMISSION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 2.sp
                    )
                    // PTT button — primary action
                    PttButton(
                        state = state.pttUiState,
                        isFloorLocked = state.isFloorLocked,
                        floorHolderId = state.floorHolderId,
                        volumeLevel = state.volumeLevel,
                        onPress = { onIntent(TransceiverIntent.FloorRequest) },
                        onRelease = { onIntent(TransceiverIntent.FloorRelease) },
                        modifier = Modifier.testTag("pttButton")
                    )
                    // SOS — secondary, below PTT, compact
                    Button(
                        onClick = { onIntent(TransceiverIntent.SendAlert("SOS")) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("sosButton")
                    ) {
                        Text(
                            text = "⚠  SOS",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }

            // ── Text input row ────────────────────────────────────
            HorizontalDivider(color = DividerColor, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { messageInput = it },
                    placeholder = {
                        Text(
                            "Hold PTT or type...",
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Navy,
                        unfocusedBorderColor = DividerColor,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
                )
                Button(
                    onClick = {
                        val txt = messageInput.trim()
                        if (txt.isNotBlank()) {
                            onIntent(TransceiverIntent.SendMessage(txt))
                            messageInput = ""
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Navy),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text("Send", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun CompactChip(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        modifier = Modifier
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .height(36.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}

@Composable
private fun ConnectionStatusBar(
    connectionState: com.itantra.data.transport.TransportState,
    localIp: String,
    gatewayIp: String?,
    onClick: () -> Unit
) {
    val dotColor by animateColorAsState(
        targetValue = when (connectionState) {
            com.itantra.data.transport.TransportState.CONNECTED -> Green
            com.itantra.data.transport.TransportState.CONNECTING -> Amber
            com.itantra.data.transport.TransportState.DISCOVERING -> Blue
            else -> TextSecondary
        },
        animationSpec = tween(400),
        label = "dot"
    )
    val statusLabel = when (connectionState) {
        com.itantra.data.transport.TransportState.CONNECTED -> "Connected"
        com.itantra.data.transport.TransportState.CONNECTING -> "Connecting…"
        com.itantra.data.transport.TransportState.DISCOVERING -> "Discovering"
        else -> "Not connected"
    }
    Surface(
        onClick = onClick,
        color = Surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // IP address
            Text(
                text = localIp,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary
            )
            // Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    statusLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
                if (gatewayIp != null && connectionState != com.itantra.data.transport.TransportState.CONNECTED) {
                    Text("· $gatewayIp", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = TextSecondary.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(item: MessageItem) {
    val isSent = !item.isAlert && item.frame.payloadText.isNotBlank()
    val isAlert = item.isAlert
    val time = remember(item.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
    }
    val langTag = item.frame.srcLang.name.take(2)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent && !isAlert) Arrangement.End else Arrangement.Start
    ) {
        if (isAlert) {
            // Alert bubble — full width, red tint
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFFEF2F2),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Red.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "⚠ ${item.frame.payloadText}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Red,
                        modifier = Modifier.weight(1f)
                    )
                    Text(time, fontSize = 12.sp, color = Red.copy(alpha = 0.6f))
                }
            }
        } else {
            // Chat bubble
            val bubbleColor = if (isSent) PttIdle else Surface
            val textColor = if (isSent) Color.White else TextPrimary
            val metaColor = if (isSent) Color.White.copy(alpha = 0.6f) else TextSecondary
            val shape = if (isSent)
                RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)
            else
                RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

            Surface(
                shape = shape,
                color = bubbleColor,
                modifier = Modifier.widthIn(min = 80.dp, max = 280.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = item.frame.payloadText,
                        fontSize = 15.sp,
                        color = textColor,
                        lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(langTag, fontSize = 11.sp, color = metaColor, fontWeight = FontWeight.SemiBold)
                        Text(time, fontSize = 11.sp, color = metaColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptBar(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF0F4FF))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Green)
        )
        Text(
            text = text,
            fontSize = 13.sp,
            color = Navy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AlertBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Red)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "⚠  $text",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun PttButton(
    state: PttUiState,
    isFloorLocked: Boolean,
    floorHolderId: String?,
    volumeLevel: Float,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isListening = state == PttUiState.LISTENING
    val isBusy = isFloorLocked || state == PttUiState.BUSY

    val targetColor = when {
        isBusy -> Red
        isListening -> Green
        state == PttUiState.SENDING -> Blue
        state == PttUiState.SENT -> Green.copy(alpha = 0.7f)
        else -> PttIdle   // dark blue-grey slate
    }
    val buttonColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(200),
        label = "pttColor"
    )
    val scale by animateFloatAsState(
        targetValue = if (isListening) 1.06f else 1f,
        animationSpec = tween(150),
        label = "pttScale"
    )

    val label = when {
        isBusy -> "CHANNEL\nBUSY"
        isListening -> "RELEASE\nTO SEND"
        state == PttUiState.SENDING -> "TRANSMITTING…"
        state == PttUiState.SENT -> "SENT ✓"
        else -> "HOLD\nTO TALK"
    }

    Box(
        modifier = modifier
            .size(168.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(buttonColor)
            .semantics { contentDescription = "Push to talk" }
            .pointerInput(isFloorLocked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onPress()
                    val up = waitForUpOrCancellation()
                    up?.consume()
                    if (!isFloorLocked) onRelease()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Subtle ring when listening
        if (isListening) {
            Box(
                Modifier
                    .size(154.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            )
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = if (state == PttUiState.IDLE) 18.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}

@Composable
private fun ConnectDialog(
    state: TransceiverUiState,
    peerIpInput: String,
    onIpChange: (String) -> Unit,
    onConnect: (String) -> Unit,
    onStartServer: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Text(
                "Connect to Peer",
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Device info
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Your IP", fontSize = 12.sp, color = TextSecondary)
                            Text(
                                state.localIp,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                        if (state.gatewayIp != null) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Gateway", fontSize = 12.sp, color = TextSecondary)
                                Text(
                                    state.gatewayIp,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Navy
                                )
                            }
                        }
                    }
                }

                // Discovered peers
                if (state.discoveredPeers.isNotEmpty()) {
                    Text("Nearby peers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    state.discoveredPeers.forEach { peer ->
                        Surface(
                            onClick = { onConnect(peer.ip) },
                            shape = RoundedCornerShape(8.dp),
                            color = Surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, DividerColor, RoundedCornerShape(8.dp))
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(peer.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                    Text(peer.ip, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
                                }
                                Text("Connect →", fontSize = 12.sp, color = Navy, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Quick presets
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.gatewayIp != null) {
                        OutlinedButton(
                            onClick = { onIpChange(state.gatewayIp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DividerColor)
                        ) {
                            Text("Gateway", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                    OutlinedButton(
                        onClick = { onIpChange("192.168.43.1") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DividerColor)
                    ) {
                        Text("Hotspot", fontSize = 12.sp, color = TextSecondary)
                    }
                }

                // Manual IP input
                OutlinedTextField(
                    value = peerIpInput,
                    onValueChange = onIpChange,
                    label = { Text("Peer IP address", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Navy,
                        unfocusedBorderColor = DividerColor
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(peerIpInput) },
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onStartServer) {
                Text("Start server", color = TextSecondary)
            }
        }
    )
}
