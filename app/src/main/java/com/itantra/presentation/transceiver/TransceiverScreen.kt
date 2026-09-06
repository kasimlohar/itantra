package com.itantra.presentation.transceiver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverScreen(
  state: TransceiverUiState,
  onIntent: (TransceiverIntent) -> Unit
) {
  var showConnectDialog by remember { mutableStateOf(false) }
  var peerIpInput by remember { mutableStateOf("192.168.43.1") }
  var messageInput by remember { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("iTantra") },
        actions = {
          // Mode toggle
          Switch(
            checked = state.channelMode == com.itantra.domain.model.TransmitMode.DUPLEX,
            onCheckedChange = { onIntent(TransceiverIntent.ToggleMode) },
            modifier = Modifier.testTag("modeToggle").semantics { contentDescription = "Mode toggle" }
          )
          // Connection badge button
          FilledTonalButton(
            onClick = { showConnectDialog = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = when (state.connectionState) {
                com.itantra.data.transport.TransportState.CONNECTED -> Color(0xFF2E7D32)
                com.itantra.data.transport.TransportState.CONNECTING -> Color(0xFFE65100)
                com.itantra.data.transport.TransportState.DISCOVERING -> Color(0xFF1565C0)
                else -> Color(0xFF757575)
              },
              contentColor = Color.White
            ),
            modifier = Modifier.padding(horizontal = 4.dp)
          ) {
            Text(
              text = when (state.connectionState) {
                com.itantra.data.transport.TransportState.CONNECTED -> "● Connected"
                com.itantra.data.transport.TransportState.CONNECTING -> "○ Connecting..."
                com.itantra.data.transport.TransportState.DISCOVERING -> "◐ Listening"
                else -> "○ Connect"
              },
              style = MaterialTheme.typography.labelSmall
            )
          }
          // Language selector button
          FilledTonalButton(
            onClick = {
              val nextLang = if (state.srcLang == com.itantra.domain.model.Language.HINDI) {
                com.itantra.domain.model.Language.ENGLISH
              } else {
                com.itantra.domain.model.Language.HINDI
              }
              onIntent(TransceiverIntent.SelectLanguage(nextLang, state.dstLang))
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF455A64), contentColor = Color.White),
            modifier = Modifier.padding(horizontal = 2.dp)
          ) {
            Text(
              text = if (state.srcLang == com.itantra.domain.model.Language.HINDI) "HI" else "EN",
              style = MaterialTheme.typography.labelSmall
            )
          }
          // SOS Alert button
          FilledTonalButton(
            onClick = { onIntent(TransceiverIntent.SendAlert("SOS")) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.Red, contentColor = Color.White),
            modifier = Modifier.padding(horizontal = 2.dp)
          ) {
            Text("SOS", style = MaterialTheme.typography.labelSmall)
          }
        }
      )
    }
  ) { padding ->
    if (showConnectDialog) {
      AlertDialog(
        onDismissRequest = { showConnectDialog = false },
        title = { Text("Connect Peer (Wi-Fi Direct / Hotspot)") },
        text = {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Link Status: ${state.connectionState}")
            Text("Peer IP Address:")
            OutlinedTextField(
              value = peerIpInput,
              onValueChange = { peerIpInput = it },
              label = { Text("IP Address") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilledTonalButton(
                onClick = { peerIpInput = "192.168.43.1" },
                modifier = Modifier.weight(1f)
              ) {
                Text("Host (192.168.43.1)", style = MaterialTheme.typography.labelSmall)
              }
              FilledTonalButton(
                onClick = { peerIpInput = "192.168.43.188" },
                modifier = Modifier.weight(1f)
              ) {
                Text("Pad (192.168.43.188)", style = MaterialTheme.typography.labelSmall)
              }
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            OutlinedButton(
              onClick = {
                try {
                  context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                  })
                } catch (_: Throwable) {}
              },
              modifier = Modifier.fillMaxWidth()
            ) {
              Text("TTS Voice Settings (Download Offline Hindi)", style = MaterialTheme.typography.labelSmall)
            }
          }
        },
        confirmButton = {
          Button(onClick = {
            onIntent(TransceiverIntent.ConnectPeer(peerIpInput.trim()))
            showConnectDialog = false
          }) {
            Text("Connect")
          }
        },
        dismissButton = {
          TextButton(onClick = {
            onIntent(TransceiverIntent.ConnectPeer(""))
            showConnectDialog = false
          }) {
            Text("Start Server")
          }
        }
      )
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // PTT Button
      PttButton(
        state = state.pttUiState,
        isFloorLocked = state.isFloorLocked,
        floorHolderId = state.floorHolderId,
        volumeLevel = state.volumeLevel,
        onPress = { onIntent(TransceiverIntent.FloorRequest) },
        onRelease = { onIntent(TransceiverIntent.FloorRelease) },
        modifier = Modifier.testTag("pttButton")
      )
      // Message history
      LazyColumn(modifier = Modifier.weight(1f)) {
        items(state.messageHistory) { item ->
          Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
              Text(text = item.frame.payloadText)
              Text(text = "▸ ${String.format("%02d:%02d", (item.durationSec/60).toInt(), (item.durationSec%60).toInt())} ${item.frame.srcLang.name}", style = MaterialTheme.typography.bodySmall)
            }
          }
        }
      }
      // Alert banner
      if (state.isAlertActive && state.alertTranscript != null) {
        Card(
          modifier = Modifier.fillMaxWidth().testTag("alertBanner"),
          colors = CardDefaults.cardColors(containerColor = Color.Red, contentColor = Color.White)
        ) {
          Text(
            text = state.alertTranscript,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium
          )
        }
      }
      // Current transcript
      if (state.currentTranscript.isNotBlank()) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
          Text(
            text = "Transcript: ${state.currentTranscript}",
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodyMedium
          )
        }
      }

      // Outlined text input row for direct messaging
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
      ) {
        OutlinedTextField(
          value = messageInput,
          onValueChange = { messageInput = it },
          placeholder = { Text("Hold PTT or type message...", style = MaterialTheme.typography.bodySmall) },
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
        Button(
          onClick = {
            val txt = messageInput.trim()
            if (txt.isNotBlank()) {
              onIntent(TransceiverIntent.SendMessage(txt))
              messageInput = ""
            }
          }
        ) {
          Text("Send")
        }
      }
    }
  }
}

@Composable
fun PttButton(
  state: PttUiState,
  isFloorLocked: Boolean = false,
  floorHolderId: String? = null,
  volumeLevel: Float,
  onPress: () -> Unit,
  onRelease: () -> Unit,
  modifier: Modifier = Modifier
) {
  val color = when {
    isFloorLocked || state == PttUiState.BUSY -> Color(0xFFC62828)
    state == PttUiState.IDLE -> Color(0xFF37474F)
    state == PttUiState.LISTENING -> Color(0xFF2E7D32)
    state == PttUiState.SENDING -> Color(0xFF1565C0)
    state == PttUiState.SENT -> Color(0xFF558B2F)
    else -> Color(0xFF37474F)
  }
  val text = when {
    isFloorLocked -> "✕ FLOOR BUSY\n(${floorHolderId?.uppercase() ?: "PEER"} SPEAKING)"
    state == PttUiState.BUSY -> "✕ BUSY"
    state == PttUiState.IDLE -> "PUSH TO TALK\n(HOLD & SPEAK)"
    state == PttUiState.LISTENING -> "● LISTENING...\n(SPEAK NOW)"
    state == PttUiState.SENDING -> "⋯ SENDING..."
    state == PttUiState.SENT -> "✓ SENT"
    else -> "PUSH TO TALK"
  }
  FilledTonalButton(
    onClick = {},
    modifier = modifier
      .fillMaxWidth()
      .height(76.dp)
      .semantics { contentDescription = "Press to talk" }
      .pointerInput(isFloorLocked) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          down.consume()
          onPress()
          val up = waitForUpOrCancellation()
          up?.consume()
          if (!isFloorLocked) {
            onRelease()
          }
        }
      },
    colors = ButtonDefaults.filledTonalButtonColors(containerColor = color)
  ) {
    Text(
      text = text,
      textAlign = TextAlign.Center,
      style = MaterialTheme.typography.titleMedium,
      color = Color.White
    )
  }
}
