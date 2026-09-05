package com.itantra.presentation.transceiver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverScreen(
  state: TransceiverUiState,
  onIntent: (TransceiverIntent) -> Unit
) {
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
          // Connection badge
          Text(
            text = when (state.connectionState) {
              com.itantra.data.transport.TransportState.CONNECTED -> "● Connected"
              com.itantra.data.transport.TransportState.CONNECTING -> "○ Connecting"
              com.itantra.data.transport.TransportState.DISCOVERING -> "◐ Discovering"
              else -> "○ Disconnected"
            },
            modifier = Modifier.padding(horizontal = 8.dp)
          )
        }
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { onIntent(TransceiverIntent.SendAlert("SOS")) },
        containerColor = Color.Red,
        contentColor = Color.White
      ) { Text("SOS Alert") }
    }
  ) { padding ->
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
        Text(text = state.currentTranscript, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
fun PttButton(
  state: PttUiState,
  volumeLevel: Float,
  onPress: () -> Unit,
  onRelease: () -> Unit,
  modifier: Modifier = Modifier
) {
  val color = when (state) {
    PttUiState.IDLE -> Color.Gray
    PttUiState.LISTENING -> Color(0xFF4CAF50) // green pulsing
    PttUiState.SENDING -> Color(0xFF2196F3) // blue
    PttUiState.SENT -> Color(0xFF8BC34A)
    PttUiState.BUSY -> Color.Red
  }
  val text = when (state) {
    PttUiState.IDLE -> "PTT"
    PttUiState.LISTENING -> "● LISTENING"
    PttUiState.SENDING -> "⋯ SENDING"
    PttUiState.SENT -> "✓ SENT"
    PttUiState.BUSY -> "✕ BUSY"
  }
  // Use FilledTonalButton with combinedClickable for press/release
  // For host test, simple Button with click will suffice to have hasClickAction
  FilledTonalButton(
    onClick = { onPress(); onRelease() },
    modifier = modifier
      .size(96.dp)
      .semantics { contentDescription = "Press to talk" },
    colors = ButtonDefaults.filledTonalButtonColors(containerColor = color)
  ) {
    Text(text)
  }
}
