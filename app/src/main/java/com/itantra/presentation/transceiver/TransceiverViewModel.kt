package com.itantra.presentation.transceiver
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.itantra.data.transport.TransportManager
import com.itantra.data.transport.TransportState
import com.itantra.data.ptt.PttStateMachine
import com.itantra.data.router.PriorityRouter
import com.itantra.domain.model.TransmitMode
import com.itantra.domain.model.PttEvent
import com.itantra.domain.model.Frame
import com.itantra.domain.model.PlaybackItem
import com.itantra.data.router.RouteDecision
import com.itantra.data.audio.AlertAudioManager

@HiltViewModel
class TransceiverViewModel @Inject constructor(
  private val transportManager: TransportManager,
  private val pttMachine: PttStateMachine,
  private val router: PriorityRouter,
  private val alertAudio: AlertAudioManager? = null,
  private val voiceTransceiver: com.itantra.data.audio.VoiceTransceiver? = null,
  @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context? = null
) : ViewModel() {
  private val _state = MutableStateFlow(TransceiverUiState())
  val state: StateFlow<TransceiverUiState> = _state
  private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var peerDiscoveryManager: com.itantra.data.transport.PeerDiscoveryManager? = null
  private var seqCounter = 1
  @Volatile private var capturedSpeech: String = ""
  @Volatile private var isSpeechFrameSent: Boolean = false
  @Volatile private var lastFloorReleaseTime: Long = 0L
  private var playbackJob: Job? = null
  private var floorWatchdogJob: Job? = null

  private fun toBcp47(lang: com.itantra.domain.model.Language): String = when (lang) {
    com.itantra.domain.model.Language.HINDI -> "hi-IN"
    com.itantra.domain.model.Language.GUJARATI -> "gu-IN"
    com.itantra.domain.model.Language.MARATHI -> "mr-IN"
    com.itantra.domain.model.Language.KANNADA -> "kn-IN"
    com.itantra.domain.model.Language.MALAYALAM -> "ml-IN"
    com.itantra.domain.model.Language.TAMIL -> "ta-IN"
    com.itantra.domain.model.Language.TELUGU -> "te-IN"
    com.itantra.domain.model.Language.ODIA -> "or-IN"
    com.itantra.domain.model.Language.BENGALI -> "bn-IN"
    com.itantra.domain.model.Language.ENGLISH -> {
      val def = try { java.util.Locale.getDefault().toLanguageTag() } catch (_: Throwable) { "en-US" }
      if (def.startsWith("en", ignoreCase = true)) def else "en-US"
    }
  }

  @Synchronized
  private fun sendSpeechFrame(text: String) {
    if (isSpeechFrameSent) return
    val clean = text.trim()
    if (clean.isBlank()) return
    isSpeechFrameSent = true
    sendTextFrame(clean)
  }

  private fun sendTextFrame(text: String) {
    val f = Frame(
      mode = _state.value.channelMode,
      isAlert = false,
      isStream = false,
      pttPressed = false,
      srcLang = _state.value.srcLang,
      dstLang = _state.value.dstLang,
      seqId = (seqCounter++) % 65535,
      payloadText = text
    )
    val item = MessageItem(f, false, isOutgoing = true, System.currentTimeMillis(), 2.0)
    _state.value = _state.value.copy(
      messageHistory = _state.value.messageHistory + item,
      currentTranscript = text,
      pttUiState = PttUiState.IDLE
    )
    vmScope.launch {
      transportManager.send(f)
    }
  }

  private fun isDalvik(): Boolean = try {
    System.getProperty("java.vm.name") == "Dalvik"
  } catch (_: Exception) { false }

  init {
    if (isDalvik()) {
      vmScope.launch {
        transportManager.startServer()
      }
      refreshNetworkInfo()
      // Small delay so the initial Compose composition completes before state changes
      vmScope.launch {
        delay(500L)
        startPeerDiscovery()
      }
    }
    vmScope.launch {
      transportManager.state.collect { connState ->
        _state.value = _state.value.copy(connectionState = connState)
        if (connState == TransportState.DISCONNECTED && isDalvik()) {
          refreshNetworkInfo()
        }
      }
    }
    vmScope.launch {
      transportManager.incomingFrames.collect { frame ->
        process(TransceiverIntent.OnFrameReceived(frame))
      }
    }
  }

  fun refreshNetworkInfo() {
    val local = com.itantra.data.transport.NetworkUtils.getLocalIpv4Address()
    val gateway = com.itantra.data.transport.NetworkUtils.getWifiGatewayIpv4(context)
    _state.value = _state.value.copy(localIp = local, gatewayIp = gateway)
  }

  private fun startPeerDiscovery() {
    peerDiscoveryManager?.stop()
    val mgr = com.itantra.data.transport.PeerDiscoveryManager(context)
    peerDiscoveryManager = mgr
    _state.value = _state.value.copy(isDiscoveryActive = true)
    mgr.start { peer ->
      val current = _state.value.discoveredPeers.filter { it.ip != peer.ip }
      _state.value = _state.value.copy(discoveredPeers = current + peer)
    }
    vmScope.launch {
      mgr.discoveredPeers.collect { peers ->
        _state.value = _state.value.copy(discoveredPeers = peers)
      }
    }
    // After 15 seconds without a new peer event, mark discovery as passive
    vmScope.launch {
      delay(15_000L)
      _state.value = _state.value.copy(isDiscoveryActive = false)
    }
  }

  fun process(intent: TransceiverIntent) {
    try {
      android.util.Log.d("iTantra", "ViewModel process intent: ${intent.javaClass.simpleName}")
    } catch (_: Throwable) {
      println("[iTantra] ViewModel process intent: ${intent.javaClass.simpleName}")
    }
    when (intent) {
      is TransceiverIntent.FloorRequest -> {
        if (_state.value.pttUiState == PttUiState.LISTENING) {
          return
        }
        if (_state.value.isFloorLocked) {
          try { alertAudio?.playBusyTone() } catch (_: Throwable) {}
          _state.value = _state.value.copy(pttUiState = PttUiState.BUSY)
          return
        }
        val ev = pttMachine.onLocalPress()
        val nextUi = when (ev) {
          is PttEvent.SendControlFrame -> {
            vmScope.launch {
              try { transportManager.send(ev.frame) } catch (_: Throwable) {}
            }
            PttUiState.LISTENING
          }
          is PttEvent.ChannelBusy -> {
            try { alertAudio?.playBusyTone() } catch (_: Throwable) {}
            PttUiState.BUSY
          }
          is PttEvent.FloorDenied -> {
            try { alertAudio?.playBusyTone() } catch (_: Throwable) {}
            PttUiState.LISTENING
          }
          is PttEvent.Debounced -> _state.value.pttUiState
          else -> PttUiState.LISTENING
        }
        if (nextUi == PttUiState.LISTENING) {
          capturedSpeech = ""
          isSpeechFrameSent = false
          _state.value = _state.value.copy(
            pttUiState = PttUiState.LISTENING,
            currentTranscript = "Listening... Speak now"
          )
          val langTag = toBcp47(_state.value.srcLang)
          try {
            voiceTransceiver?.startListening(
              langCode = langTag,
              onPartial = { partial ->
                if (partial.isNotBlank()) {
                  capturedSpeech = partial
                  _state.value = _state.value.copy(currentTranscript = partial)
                }
              },
              onRms = { rms ->
                _state.value = _state.value.copy(volumeLevel = (rms + 2f).coerceIn(0f, 10f))
              },
              onResult = { result ->
                if (result.isNotBlank()) {
                  val prev = capturedSpeech
                  capturedSpeech = result
                  _state.value = _state.value.copy(currentTranscript = result)
                  val recentlyReleased = (System.currentTimeMillis() - lastFloorReleaseTime) < 6000
                  if (_state.value.pttUiState == PttUiState.SENDING || (prev.isBlank() && recentlyReleased)) {
                    sendSpeechFrame(result)
                  }
                }
              }
            )
          } catch (e: Throwable) {
            try {
              android.util.Log.e("iTantra", "Error starting voice transceiver", e)
            } catch (_: Throwable) {
              println("[iTantra] Error starting voice transceiver: ${e.message}")
            }
          }
        } else {
          _state.value = _state.value.copy(pttUiState = nextUi)
        }
      }
      is TransceiverIntent.FloorRelease -> {
        if (_state.value.pttUiState != PttUiState.LISTENING) {
          return
        }
        val ev = try { pttMachine.onLocalRelease() } catch (_: Exception) { PttEvent.Ignored }
        if (ev is PttEvent.FloorReleased) {
          vmScope.launch {
            try { transportManager.send(ev.frame) } catch (_: Throwable) {}
          }
        }
        _state.value = _state.value.copy(pttUiState = PttUiState.SENDING)
        lastFloorReleaseTime = System.currentTimeMillis()
        try {
          voiceTransceiver?.stopListening()
        } catch (_: Throwable) {}

        vmScope.launch {
          var waited = 0
          while (capturedSpeech.isBlank() && waited < 4000) {
            delay(50)
            waited += 50
          }

          val textToSend = capturedSpeech.trim()
          if (textToSend.isNotBlank()) {
            sendSpeechFrame(textToSend)
          } else {
            if (_state.value.pttUiState == PttUiState.SENDING) {
              _state.value = _state.value.copy(
                pttUiState = PttUiState.IDLE,
                currentTranscript = "No speech detected (Hold PTT and speak)"
              )
            }
          }
        }
      }
      is TransceiverIntent.SendMessage -> {
        val textToSend = intent.text.trim()
        if (textToSend.isNotBlank()) {
          sendTextFrame(textToSend)
        }
      }
      is TransceiverIntent.ToggleMode -> {
        val newMode = if (_state.value.channelMode == TransmitMode.HALF_DUPLEX) TransmitMode.DUPLEX else TransmitMode.HALF_DUPLEX
        _state.value = _state.value.copy(channelMode = newMode)
        pttMachine.setMode(newMode)
      }
      is TransceiverIntent.SelectLanguage -> {
        _state.value = _state.value.copy(srcLang = intent.src, dstLang = intent.dst)
      }
      is TransceiverIntent.SendAlert -> {
        // Optimistically show SOS SENT; switch to Failure on transport error; auto-clear after 3s
        _state.value = _state.value.copy(
          isAlertActive = true,
          alertTranscript = intent.text,
          sosToast = SosToastState.Success
        )
        val alertFrame = Frame(
          mode = _state.value.channelMode,
          isAlert = true,
          isStream = false,
          pttPressed = false,
          srcLang = _state.value.srcLang,
          dstLang = _state.value.dstLang,
          seqId = (seqCounter++) % 65535,
          payloadText = intent.text
        )
        val item = MessageItem(alertFrame, true, isOutgoing = true, System.currentTimeMillis(), 4.0)
        _state.value = _state.value.copy(messageHistory = _state.value.messageHistory + item)
        vmScope.launch {
          try {
            transportManager.send(alertFrame)
          } catch (_: Throwable) {
            _state.value = _state.value.copy(sosToast = SosToastState.Failure)
          }
          delay(3_000L)
          _state.value = _state.value.copy(sosToast = null)
        }
      }
      is TransceiverIntent.ConnectPeer -> {
        vmScope.launch {
          if (intent.peerId.isBlank()) {
            transportManager.startServer()
          } else {
            transportManager.connectTo(intent.peerId, 4242)
          }
        }
      }
      is TransceiverIntent.Disconnect -> {
        transportManager.disconnect()
        _state.value = _state.value.copy(connectionState = TransportState.DISCONNECTED)
      }
      is TransceiverIntent.RefreshNetwork -> {
        refreshNetworkInfo()
      }
      is TransceiverIntent.RestartDiscovery -> {
        refreshNetworkInfo()
        startPeerDiscovery()
      }
      is TransceiverIntent.OnFrameReceived -> {
        val frame = intent.frame

        // 1. Check if this is a PTT Floor Control Frame (empty payload)
        if (frame.payloadText.isEmpty()) {
          val pttEv = pttMachine.onRemoteFrame(frame)
          when (pttEv) {
            is PttEvent.RemoteGranted -> {
              floorWatchdogJob?.cancel()
              floorWatchdogJob = vmScope.launch {
                delay(20000L)
                if (_state.value.isFloorLocked) {
                  pttMachine.clear()
                  _state.value = _state.value.copy(
                    isFloorLocked = false,
                    floorHolderId = null,
                    pttUiState = if (_state.value.pttUiState == PttUiState.BUSY) PttUiState.IDLE else _state.value.pttUiState
                  )
                }
              }
              _state.value = _state.value.copy(
                isFloorLocked = true,
                floorHolderId = "Remote Peer",
                pttUiState = PttUiState.BUSY
              )
              try { voiceTransceiver?.stopListening() } catch (_: Throwable) {}
              try { alertAudio?.playBusyTone() } catch (_: Throwable) {}
            }
            is PttEvent.RemoteReleased -> {
              floorWatchdogJob?.cancel()
              _state.value = _state.value.copy(
                isFloorLocked = false,
                floorHolderId = null,
                pttUiState = if (_state.value.pttUiState == PttUiState.BUSY) PttUiState.IDLE else _state.value.pttUiState
              )
            }
            else -> {}
          }
          return
        }

        // 2. Non-empty payload: Voice / Text / Alert message
        val item = MessageItem(frame, frame.isAlert, isOutgoing = false, System.currentTimeMillis(), 2.5)
        _state.value = _state.value.copy(
          messageHistory = _state.value.messageHistory + item,
          currentTranscript = frame.payloadText,
          isAlertActive = frame.isAlert || _state.value.isAlertActive,
          alertTranscript = if (frame.isAlert) frame.payloadText else _state.value.alertTranscript
        )

        // 3. Pass frame through PriorityRouter for preemption and FIFO ordering
        val decision = router.route(frame)
        when (decision) {
          is RouteDecision.PlayNow -> {
            playRoutedItem(decision.item, decision.preempted)
          }
          is RouteDecision.Enqueue -> {
            // Enqueued in router's pending queue; will play when current playback finishes
          }
          is RouteDecision.Drop -> {}
        }
      }
    }
  }

  private fun playRoutedItem(item: PlaybackItem, preempted: PlaybackItem?) {
    playbackJob?.cancel()
    if (item.isAlert || preempted != null) {
      try { voiceTransceiver?.stopSpeaking() } catch (_: Throwable) {}
      try {
        alertAudio?.acquireAlarmFocus()
        alertAudio?.vibrate(longArrayOf(0, 500, 200, 500))
      } catch (_: Throwable) {}
    }
    playbackJob = vmScope.launch {
      try {
        val langTag = toBcp47(item.frame.dstLang)
        voiceTransceiver?.speak(item.frame.payloadText, langTag)
      } catch (_: Throwable) {}

      val estimatedDurationMs = (item.frame.payloadText.length * 65L).coerceIn(1200L, 8000L)
      delay(estimatedDurationMs)

      router.onPlaybackFinished()
      val next = router.current()
      if (next != null) {
        playRoutedItem(next, null)
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    peerDiscoveryManager?.stop()
    peerDiscoveryManager = null
    try { voiceTransceiver?.release() } catch (_: Throwable) {}
    vmScope.cancel()
  }
}
