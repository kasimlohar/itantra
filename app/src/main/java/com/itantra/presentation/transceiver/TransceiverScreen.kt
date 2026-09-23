package com.itantra.presentation.transceiver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.itantra.domain.model.Language
import com.itantra.presentation.theme.ITantraColors
import com.itantra.presentation.theme.ITantraShapes
import com.itantra.presentation.theme.ITantraType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverScreen(
    state: TransceiverUiState,
    onIntent: (TransceiverIntent) -> Unit
) {
    var showConnectDialog by remember { mutableStateOf(false) }
    var peerIpInput by remember { mutableStateOf("192.168.43.1") }
    var messageInput by remember { mutableStateOf("") }
    var langDropdownExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Ordered language list: Hindi + English first, then the rest
    val languageOrder = listOf(
        Language.HINDI, Language.ENGLISH, Language.MARATHI,
        Language.GUJARATI, Language.KANNADA, Language.MALAYALAM,
        Language.TAMIL, Language.TELUGU, Language.ODIA, Language.BENGALI
    )

    // Auto-scroll to latest message
    LaunchedEffect(state.messageHistory.size) {
        if (state.messageHistory.isNotEmpty()) {
            listState.animateScrollToItem(state.messageHistory.size - 1)
        }
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

    Scaffold(
        containerColor = ITantraColors.Background,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ITantraColors.Background,
                        titleContentColor = ITantraColors.TextPrimary
                    ),
                    title = {
                        Text(
                            "Transceiver",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = ITantraColors.TextPrimary
                        )
                    },
                    actions = {
                        val isDuplex = state.channelMode == com.itantra.domain.model.TransmitMode.DUPLEX
                        // Mode chip: Walkie Talkie (PTT/half-duplex) vs Phone (full-duplex)
                        CompactChip(
                            label = if (isDuplex) "Phone" else "Walkie",
                            onClick = { onIntent(TransceiverIntent.ToggleMode) },
                            containerColor = if (isDuplex) ITantraColors.Primary else ITantraColors.Surface,
                            contentColor = if (isDuplex) Color.White else ITantraColors.TextSecondary,
                            borderColor = if (isDuplex) ITantraColors.Primary else ITantraColors.Border
                        )
                        Spacer(Modifier.width(6.dp))
                        // Language dropdown — all 10 languages
                        Box {
                            CompactChip(
                                label = if (state.isAutoLangDetect) "Auto" else "${langCode(state.srcLang)} ▾",
                                onClick = {
                                    langDropdownExpanded = !langDropdownExpanded
                                },
                                containerColor = ITantraColors.Surface,
                                contentColor = ITantraColors.TextPrimary,
                                borderColor = ITantraColors.Border
                            )
                            DropdownMenu(
                                expanded = langDropdownExpanded,
                                onDismissRequest = { langDropdownExpanded = false },
                                modifier = Modifier
                                    .background(Color.White)
                                    .widthIn(min = 160.dp)
                            ) {
                                languageOrder.forEach { lang ->
                                    val isSelected = !state.isAutoLangDetect && lang == state.srcLang
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = langCode(lang),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) ITantraColors.Primary else ITantraColors.TextSecondary,
                                                    modifier = Modifier.width(28.dp)
                                                )
                                                Text(
                                                    text = langName(lang),
                                                    fontSize = 14.sp,
                                                    color = if (isSelected) ITantraColors.Primary
                                                            else ITantraColors.TextPrimary,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold
                                                                 else FontWeight.Normal
                                                )
                                                if (isSelected) {
                                                    Spacer(Modifier.weight(1f))
                                                    Text(
                                                        text = "✓",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = ITantraColors.Primary
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onIntent(TransceiverIntent.SelectLanguage(lang, lang))
                                            langDropdownExpanded = false
                                        },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        CompactChip(
                            label = "SOS",
                            onClick = { onIntent(TransceiverIntent.SendAlert("SOS")) },
                            containerColor = ITantraColors.Error,
                            contentColor = Color.White,
                            borderColor = ITantraColors.Error
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                )
                ConnectionStatusBar(
                    connectionState = state.connectionState,
                    localIp = state.localIp,
                    gatewayIp = state.gatewayIp,
                    onClick = {
                        onIntent(TransceiverIntent.RefreshNetwork)
                        showConnectDialog = true
                    }
                )
                HorizontalDivider(color = ITantraColors.Border, thickness = 1.dp)
            }
        }
    ) { padding ->

        LaunchedEffect(showConnectDialog) {
            if (showConnectDialog) onIntent(TransceiverIntent.RefreshNetwork)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)
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
                                "No messages yet\nHold Walkie Talkie to transmit",
                                color = ITantraColors.TextSecondary,
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

            if (state.currentTranscript.isNotBlank() &&
                state.pttUiState == PttUiState.LISTENING
            ) {
                TranscriptBar(text = state.currentTranscript)
            }

            // Auto-detect language toggle bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ITantraColors.Surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Language Detection",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = ITantraColors.TextPrimary
                        )
                        Text(
                            if (state.isAutoLangDetect) "Auto · detecting from speech"
                            else "Manual · tap language chip to change",
                            fontSize = 11.sp,
                            color = ITantraColors.TextSecondary
                        )
                    }
                    Switch(
                        checked = state.isAutoLangDetect,
                        onCheckedChange = { onIntent(TransceiverIntent.ToggleAutoLangDetect) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ITantraColors.Primary,
                            uncheckedThumbColor = ITantraColors.TextSecondary,
                            uncheckedTrackColor = ITantraColors.Border
                        )
                    )
                }
            }

            HorizontalDivider(color = ITantraColors.Border, thickness = 1.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ITantraColors.Background)
                    .padding(top = 12.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "TRANSMISSION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ITantraColors.TextSecondary,
                        letterSpacing = 2.sp
                    )
                    Button(
                        onClick = { onIntent(TransceiverIntent.SendAlert("SOS")) },
                        colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Error),
                        shape = ITantraShapes.Button,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp)
                    ) {
                        Text("⚠ SOS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    PttButton(
                        state = state.pttUiState,
                        isFloorLocked = state.isFloorLocked,
                        floorHolderId = state.floorHolderId,
                        volumeLevel = state.volumeLevel,
                        onPress = { onIntent(TransceiverIntent.FloorRequest) },
                        onRelease = { onIntent(TransceiverIntent.FloorRelease) },
                        modifier = Modifier.testTag("pttButton")
                    )
                    AnimatedVisibility(
                        visible = state.sosToast != null,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(300))
                    ) {
                        state.sosToast?.let { toast -> SosToastRow(toast = toast) }
                    }
                }
            }

            HorizontalDivider(color = ITantraColors.Border, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ITantraColors.Background)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { messageInput = it },
                    placeholder = {
                        Text(
                            "Hold Walkie Talkie or type...",
                            fontSize = 15.sp,
                            color = ITantraColors.TextSecondary
                        )
                    },
                    singleLine = true,
                    shape = ITantraShapes.Input,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ITantraColors.Primary,
                        unfocusedBorderColor = ITantraColors.Border,
                        focusedTextColor = ITantraColors.TextPrimary,
                        unfocusedTextColor = ITantraColors.TextPrimary
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
                    shape = ITantraShapes.Button,
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text("Send", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CompactChip(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = ITantraShapes.Pill,
        color = containerColor,
        modifier = modifier
            .border(1.dp, borderColor, ITantraShapes.Pill)
            .height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
        }
    }
}

/** Short 2-letter language code for the chip label. */
private fun langCode(lang: com.itantra.domain.model.Language): String = when (lang) {
    com.itantra.domain.model.Language.HINDI     -> "HI"
    com.itantra.domain.model.Language.ENGLISH   -> "EN"
    com.itantra.domain.model.Language.MARATHI   -> "MR"
    com.itantra.domain.model.Language.GUJARATI  -> "GU"
    com.itantra.domain.model.Language.KANNADA   -> "KN"
    com.itantra.domain.model.Language.MALAYALAM -> "ML"
    com.itantra.domain.model.Language.TAMIL     -> "TA"
    com.itantra.domain.model.Language.TELUGU    -> "TE"
    com.itantra.domain.model.Language.ODIA      -> "OD"
    com.itantra.domain.model.Language.BENGALI   -> "BN"
}

/** Full language display name for the dropdown. */
private fun langName(lang: com.itantra.domain.model.Language): String = when (lang) {
    com.itantra.domain.model.Language.HINDI     -> "Hindi"
    com.itantra.domain.model.Language.ENGLISH   -> "English"
    com.itantra.domain.model.Language.MARATHI   -> "Marathi"
    com.itantra.domain.model.Language.GUJARATI  -> "Gujarati"
    com.itantra.domain.model.Language.KANNADA   -> "Kannada"
    com.itantra.domain.model.Language.MALAYALAM -> "Malayalam"
    com.itantra.domain.model.Language.TAMIL     -> "Tamil"
    com.itantra.domain.model.Language.TELUGU    -> "Telugu"
    com.itantra.domain.model.Language.ODIA      -> "Odia"
    com.itantra.domain.model.Language.BENGALI   -> "Bengali"
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
            com.itantra.data.transport.TransportState.CONNECTED -> ITantraColors.Success
            com.itantra.data.transport.TransportState.CONNECTING -> ITantraColors.Amber
            com.itantra.data.transport.TransportState.DISCOVERING -> ITantraColors.Blue
            else -> ITantraColors.TextSecondary
        },
        animationSpec = tween(400), label = "dot"
    )
    val statusLabel = when (connectionState) {
        com.itantra.data.transport.TransportState.CONNECTED -> "Connected"
        com.itantra.data.transport.TransportState.CONNECTING -> "Connecting..."
        com.itantra.data.transport.TransportState.DISCOVERING -> "Discovering"
        else -> "Not connected"
    }
    Surface(onClick = onClick, color = ITantraColors.Surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(localIp, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = ITantraColors.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Text(statusLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ITantraColors.TextSecondary)
                if (gatewayIp != null && connectionState != com.itantra.data.transport.TransportState.CONNECTED) {
                    Text("- $gatewayIp", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = ITantraColors.TextSecondary.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(item: MessageItem) {
    val isAlert = item.isAlert
    val time = remember(item.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
    }
    val langTag = item.frame.srcLang.name.take(2)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (item.isOutgoing && !isAlert) Arrangement.End else Arrangement.Start
    ) {
        if (isAlert) {
            Surface(
                shape = ITantraShapes.Small,
                color = Color(0xFFFEF2F2),
                modifier = Modifier.fillMaxWidth().border(1.dp, ITantraColors.Error.copy(alpha = 0.3f), ITantraShapes.Small)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("! ${item.frame.payloadText}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ITantraColors.Error, modifier = Modifier.weight(1f))
                    Text(time, fontSize = 12.sp, color = ITantraColors.Error.copy(alpha = 0.6f))
                }
            }
        } else {
            val bubbleColor = if (item.isOutgoing) ITantraColors.Primary else ITantraColors.Surface
            val textColor = if (item.isOutgoing) Color.White else ITantraColors.TextPrimary
            val metaColor = if (item.isOutgoing) Color.White.copy(alpha = 0.6f) else ITantraColors.TextSecondary
            val shape = if (item.isOutgoing)
                RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)
            else
                RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)

            Surface(shape = shape, color = bubbleColor, modifier = Modifier.widthIn(min = 80.dp, max = 280.dp)) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(item.frame.payloadText, fontSize = 15.sp, color = textColor, lineHeight = 22.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(langTag, fontSize = 11.sp, color = metaColor, fontWeight = FontWeight.SemiBold)
                        Text(time, fontSize = 11.sp, color = metaColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun SosToastRow(toast: SosToastState) {
    val isSuccess = toast is SosToastState.Success
    val contentColor = if (isSuccess) ITantraColors.Success else ITantraColors.Error
    val bgColor = contentColor.copy(alpha = 0.1f)
    val borderColor = contentColor.copy(alpha = 0.3f)
    val icon = if (isSuccess) "OK" else "!!"
    val title = if (isSuccess) "SOS SENT" else "SOS FAILED"
    val subtitle = if (isSuccess) "Emergency alert transmitted" else "Unable to send SOS"

    Surface(
        shape = ITantraShapes.Small,
        color = bgColor,
        modifier = Modifier.widthIn(max = 280.dp).border(1.dp, borderColor, ITantraShapes.Small)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(icon, fontSize = 14.sp, color = contentColor, fontWeight = FontWeight.Bold)
            Column {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = contentColor)
                Text(subtitle, fontSize = 11.sp, color = contentColor.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun TranscriptBar(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(ITantraColors.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(ITantraColors.Success))
        Text(
            text = text,
            style = ITantraType.bodySmall.copy(color = ITantraColors.TextPrimary),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
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
        isBusy      -> ITantraColors.Error
        isListening -> ITantraColors.Success
        state == PttUiState.SENDING -> ITantraColors.Blue
        state == PttUiState.SENT    -> ITantraColors.Success.copy(alpha = 0.7f)
        else        -> ITantraColors.Primary
    }
    val buttonColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(200), label = "pttColor")
    val scale by animateFloatAsState(targetValue = if (isListening) 1.06f else 1f, animationSpec = tween(150), label = "pttScale")

    val label = when {
        isBusy      -> "CHANNEL\nBUSY"
        isListening -> "RELEASE\nTO SEND"
        state == PttUiState.SENDING -> "TRANSMITTING..."
        state == PttUiState.SENT    -> "SENT"
        else        -> "HOLD\nTO TALK"
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
        if (isListening) {
            Box(Modifier.size(154.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape))
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
        title = { Text("Connect to Peer", fontWeight = FontWeight.Bold, color = ITantraColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = ITantraShapes.Small, color = ITantraColors.Surface, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Your IP", fontSize = 12.sp, color = ITantraColors.TextSecondary)
                            Text(state.localIp, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = ITantraColors.TextPrimary)
                        }
                        if (state.gatewayIp != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Gateway", fontSize = 12.sp, color = ITantraColors.TextSecondary)
                                Text(state.gatewayIp, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = ITantraColors.TextPrimary)
                            }
                        }
                    }
                }
                if (state.discoveredPeers.isNotEmpty()) {
                    Text("Nearby peers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ITantraColors.TextSecondary)
                    state.discoveredPeers.forEach { peer ->
                        Surface(
                            onClick = { onConnect(peer.ip) },
                            shape = ITantraShapes.Small,
                            color = ITantraColors.Surface,
                            modifier = Modifier.fillMaxWidth().border(1.dp, ITantraColors.Border, ITantraShapes.Small)
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(peer.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ITantraColors.TextPrimary)
                                    Text(peer.ip, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ITantraColors.TextSecondary)
                                }
                                Text("Connect", fontSize = 12.sp, color = ITantraColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.gatewayIp != null) {
                        OutlinedButton(onClick = { onIpChange(state.gatewayIp) }, modifier = Modifier.weight(1f), shape = ITantraShapes.Small, border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.Border)) {
                            Text("Gateway", fontSize = 12.sp, color = ITantraColors.TextSecondary)
                        }
                    }
                    OutlinedButton(onClick = { onIpChange("192.168.43.1") }, modifier = Modifier.weight(1f), shape = ITantraShapes.Small, border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.Border)) {
                        Text("Hotspot", fontSize = 12.sp, color = ITantraColors.TextSecondary)
                    }
                }
                OutlinedTextField(
                    value = peerIpInput, onValueChange = onIpChange,
                    label = { Text("Peer IP address", fontSize = 12.sp) },
                    singleLine = true, shape = ITantraShapes.Small,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ITantraColors.Primary, unfocusedBorderColor = ITantraColors.Border),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(peerIpInput) }, colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary), shape = ITantraShapes.Button) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onStartServer) { Text("Start server", color = ITantraColors.TextSecondary) }
        }
    )
}