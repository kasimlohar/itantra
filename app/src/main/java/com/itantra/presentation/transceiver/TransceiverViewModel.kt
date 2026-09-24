package com.itantra.presentation.transceiver
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
import com.itantra.data.audio.SirenPlayer
import com.itantra.data.perf.PerformanceMonitor
import com.itantra.data.proximity.BleRssiScanner

@HiltViewModel
class TransceiverViewModel @Inject constructor(
  private val transportManager: TransportManager,
  private val pttMachine: PttStateMachine,
  private val router: PriorityRouter,
  private val alertAudio: AlertAudioManager? = null,
  private val voiceTransceiver: com.itantra.data.audio.VoiceTransceiver? = null,
  @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context? = null,
  private val bleRssiScanner: BleRssiScanner? = null,
  private val sirenPlayer: SirenPlayer? = null,
  private val performanceMonitor: PerformanceMonitor? = null,
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
  /** Coroutine job for active BLE distance collection (Rescue Beacon or Siren). */
  private var proximityJob: Job? = null
  /** Timestamp (ms) when VAD speech starts, for pipeline latency measurement. */
  @Volatile private var pipelineStartMs: Long = -1L

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

  /**
   * Heuristically detect language from transcribed text using Unicode block prevalence.
   * Returns null if text is ambiguous or empty (so caller keeps the current language).
   */
  private fun detectLanguageFromText(text: String): com.itantra.domain.model.Language? {
    if (text.isBlank()) return null
    val devanagari = text.count { it.code in 0x0900..0x097F }  // Hindi, Marathi
    val latin      = text.count { it.code in 0x0041..0x007A }  // English
    val gujarati   = text.count { it.code in 0x0A80..0x0AFF }
    val kannada    = text.count { it.code in 0x0C80..0x0CFF }
    val malayalam  = text.count { it.code in 0x0D00..0x0D7F }
    val tamil      = text.count { it.code in 0x0B80..0x0BFF }
    val telugu     = text.count { it.code in 0x0C00..0x0C7F }
    val oriya      = text.count { it.code in 0x0B00..0x0B7F }
    val bengali    = text.count { it.code in 0x0980..0x09FF }
    val total = text.length.toFloat().coerceAtLeast(1f)
    // Only return a language if it has strong signal (>40% of chars)
    return when {
      latin     / total > 0.40f -> com.itantra.domain.model.Language.ENGLISH
      gujarati  / total > 0.40f -> com.itantra.domain.model.Language.GUJARATI
      kannada   / total > 0.40f -> com.itantra.domain.model.Language.KANNADA
      malayalam / total > 0.40f -> com.itantra.domain.model.Language.MALAYALAM
      tamil     / total > 0.40f -> com.itantra.domain.model.Language.TAMIL
      telugu    / total > 0.40f -> com.itantra.domain.model.Language.TELUGU
      oriya     / total > 0.40f -> com.itantra.domain.model.Language.ODIA
      bengali   / total > 0.40f -> com.itantra.domain.model.Language.BENGALI
      devanagari / total > 0.40f -> com.itantra.domain.model.Language.HINDI  // Hindi as default Devanagari
      else -> null
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
    // Start performance monitoring (silent no-op if PerformanceMonitor not injected)
    performanceMonitor?.let { pm ->
      vmScope.launch {
        pm.perfFlow(1000L).collect { perf ->
          // Inject current pipeline latency into the perf snapshot
          val latency = _state.value.devicePerf?.pipelineLatencyMs ?: -1L
          _state.value = _state.value.copy(devicePerf = perf.copy(pipelineLatencyMs = latency))
        }
      }
    }
    vmScope.launch {
      transportManager.state.collect { connState ->
        _state.value = _state.value.copy(connectionState = connState)
        when (connState) {
          TransportState.CONNECTED -> {
            val remoteIp = transportManager.remoteAddress?.trim()?.removePrefix("/")
            if (!remoteIp.isNullOrBlank()) {
              _state.value = _state.value.copy(
                peerConnectionStates = _state.value.peerConnectionStates + (remoteIp to PeerConnectionStatus.CONNECTED)
              )
            }
          }
          TransportState.DISCONNECTED -> {
            val updatedMap = _state.value.peerConnectionStates.mapValues { (_, status) ->
              if (status == PeerConnectionStatus.CONNECTED) PeerConnectionStatus.DISCONNECTED
              else if (status == PeerConnectionStatus.CONNECTING) PeerConnectionStatus.FAILED
              else status
            }
            _state.value = _state.value.copy(peerConnectionStates = updatedMap)
            if (isDalvik()) {
              refreshNetworkInfo()
            }
          }
          else -> {}
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
          pipelineStartMs = System.currentTimeMillis()   // start pipeline clock
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
                  // Auto language detection: detect from result if enabled
                  if (_state.value.isAutoLangDetect) {
                    val detected = detectLanguageFromText(result)
                    if (detected != null && detected != _state.value.srcLang) {
                      _state.value = _state.value.copy(srcLang = detected, dstLang = detected)
                    }
                  }
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
        _state.value = _state.value.copy(
          srcLang = intent.src,
          dstLang = intent.dst,
          isAutoLangDetect = false
        )
      }
      is TransceiverIntent.ToggleAutoLangDetect -> {
        _state.value = _state.value.copy(isAutoLangDetect = !_state.value.isAutoLangDetect)
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
        val targetIp = intent.peerId.trim()
        if (targetIp.isNotBlank()) {
          _state.value = _state.value.copy(
            peerConnectionStates = _state.value.peerConnectionStates + (targetIp to PeerConnectionStatus.CONNECTING)
          )
        }
        vmScope.launch {
          if (targetIp.isBlank()) {
            transportManager.startServer()
          } else {
            val res = transportManager.connectTo(targetIp, 4242)
            if (res.isSuccess) {
              _state.value = _state.value.copy(
                peerConnectionStates = _state.value.peerConnectionStates + (targetIp to PeerConnectionStatus.CONNECTED)
              )
            } else {
              _state.value = _state.value.copy(
                peerConnectionStates = _state.value.peerConnectionStates + (targetIp to PeerConnectionStatus.FAILED)
              )
            }
          }
        }
      }
      is TransceiverIntent.Disconnect -> {
        transportManager.disconnect()
        val updatedMap = _state.value.peerConnectionStates.mapValues { (_, status) ->
          if (status == PeerConnectionStatus.CONNECTED) PeerConnectionStatus.DISCONNECTED else status
        }
        _state.value = _state.value.copy(
          connectionState = TransportState.DISCONNECTED,
          peerConnectionStates = updatedMap
        )
      }
      is TransceiverIntent.RefreshNetwork -> {
        refreshNetworkInfo()
      }
      is TransceiverIntent.RestartDiscovery -> {
        refreshNetworkInfo()
        startPeerDiscovery()
      }

      // ── Feature A: Find My Phone ───────────────────────────────────────────
      is TransceiverIntent.StartFindMyPhone -> {
        val scanner = bleRssiScanner
        if (scanner == null) {
          _state.value = _state.value.copy(findMyPhoneError = "BLE not available on this device")
          return
        }
        val peer = _state.value.discoveredPeers.firstOrNull()
        val peerLabel = peer?.name ?: "nearest beacon"
        _state.value = _state.value.copy(
          findMyPhoneActive = true,
          findMyPhonePeerName = peerLabel,
          findMyPhoneError = null
        )
        // Broadcast this phone's BLE beacon so the other device can home in
        scanner.startBeacon()

        proximityJob?.cancel()
        // Use scanFlow: prioritizes iTantra beacons and falls back gracefully to nearest device
        val distFlow = scanner.scanFlow(peer?.name)
        proximityJob = vmScope.launch {
          try {
            distFlow.collect { metres ->
              _state.value = _state.value.copy(findMyPhoneDistanceMetres = metres)
            }
          } catch (e: Exception) {
            _state.value = _state.value.copy(
              findMyPhoneError = "Signal lost — move to an open area",
              findMyPhoneDistanceMetres = -1f
            )
          }
        }
      }
      is TransceiverIntent.StopFindMyPhone -> {
        bleRssiScanner?.stopBeacon()
        proximityJob?.cancel()
        proximityJob = null
        _state.value = _state.value.copy(
          findMyPhoneActive = false,
          findMyPhoneDistanceMetres = -1f,
          findMyPhoneError = null
        )
      }

      // ── Feature B: Performance HUD ─────────────────────────────────────────
      is TransceiverIntent.TogglePerfHud -> {
        _state.value = _state.value.copy(showPerfHud = !_state.value.showPerfHud)
      }

      // ── Feature C: Locate via Siren ────────────────────────────────────────
      is TransceiverIntent.StartSirenLocate -> {
        val player = sirenPlayer
        if (player == null) return
        _state.value = _state.value.copy(sirenLocateActive = true, sirenDistanceMetres = -1f)
        val scanner = bleRssiScanner
        scanner?.startBeacon()
        val peer    = _state.value.discoveredPeers.firstOrNull()
        val distFlow = if (scanner != null) scanner.scanFlow(peer?.name)
                       else kotlinx.coroutines.flow.emptyFlow()
        // Start the siren — it loops continuously regardless of BLE updates
        player.start(vmScope, distFlow)
        // Also update UI state when distance changes
        proximityJob?.cancel()
        proximityJob = vmScope.launch {
          try {
            distFlow.collect { d ->
              val (freqHz, _) = player.mappingForDistance(d)
              _state.value = _state.value.copy(sirenDistanceMetres = d, sirenFreqHz = freqHz)
            }
          } catch (_: Throwable) {}
        }
      }
      is TransceiverIntent.StopSirenLocate -> {
        sirenPlayer?.stop()
        bleRssiScanner?.stopBeacon()
        proximityJob?.cancel()
        proximityJob = null
        _state.value = _state.value.copy(sirenLocateActive = false, sirenDistanceMetres = -1f)
      }
      is TransceiverIntent.TriggerRemoteSiren -> {
        // Guard: require an active transport connection before sending
        if (_state.value.connectionState != TransportState.CONNECTED) {
          _state.value = _state.value.copy(
            sirenCommandStatus = SirenCommandStatus.NO_TARGET
          )
          return
        }
        _state.value = _state.value.copy(sirenCommandStatus = SirenCommandStatus.SENDING)
        val sirenFrame = Frame(
          mode = _state.value.channelMode,
          isAlert = false,
          isStream = false,
          pttPressed = false,
          isSiren = true,
          // Use a non-empty sentinel payload ("SIREN") so the receiver does NOT
          // mistake this for a PTT floor-control frame (empty-payload check).
          payloadText = "SIREN",
          srcLang = _state.value.srcLang,
          dstLang = _state.value.dstLang,
          seqId = (seqCounter++) % 65535
        )
        vmScope.launch {
          try {
            transportManager.send(sirenFrame)
            // Controller sent successfully — mark Active; controller stays silent.
            _state.value = _state.value.copy(sirenCommandStatus = SirenCommandStatus.ACTIVE)
          } catch (_: Throwable) {
            _state.value = _state.value.copy(sirenCommandStatus = SirenCommandStatus.UNREACHABLE)
          }
        }
      }
      is TransceiverIntent.StopRemoteSiren -> {
        if (_state.value.connectionState != TransportState.CONNECTED) {
          _state.value = _state.value.copy(sirenCommandStatus = SirenCommandStatus.NO_TARGET)
          return
        }
        val stopFrame = Frame(
          mode = _state.value.channelMode,
          isAlert = false,
          isStream = false,
          pttPressed = false,
          isSiren = true,
          payloadText = "SIREN_STOP",
          srcLang = _state.value.srcLang,
          dstLang = _state.value.dstLang,
          seqId = (seqCounter++) % 65535
        )
        vmScope.launch {
          try {
            transportManager.send(stopFrame)
            _state.value = _state.value.copy(sirenCommandStatus = SirenCommandStatus.IDLE)
          } catch (_: Throwable) {}
        }
      }

      is TransceiverIntent.OnFrameReceived -> {
        val frame = intent.frame

        // 1. Siren command check FIRST — siren frames carry an empty or sentinel payload
        //    and must be handled before the PTT empty-payload early-return below.
        if (frame.isSiren) {
          when (frame.payloadText) {
            "SIREN" -> {
              // TARGET phone: received remote siren command — play siren on this device.
              sirenPlayer?.start(vmScope, kotlinx.coroutines.flow.flowOf(-1f))
            }
            "SIREN_STOP" -> {
              // TARGET phone: received remote stop command — stop siren on this device.
              sirenPlayer?.stop()
            }
            // Legacy empty-payload siren frames (pre-fix): treat as start.
            else -> {
              sirenPlayer?.start(vmScope, kotlinx.coroutines.flow.flowOf(-1f))
            }
          }
          return
        }

        // 2. Check if this is a PTT Floor Control Frame (empty payload)
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

        // 3. Non-empty, non-siren payload: Voice / Text / Alert

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
        // Record pipeline latency (VAD start → TTS begin) for the HUD
        if (pipelineStartMs > 0L) {
          val elapsedMs = System.currentTimeMillis() - pipelineStartMs
          pipelineStartMs = -1L
          val current = _state.value.devicePerf
          if (current != null) {
            _state.value = _state.value.copy(devicePerf = current.copy(pipelineLatencyMs = elapsedMs))
          }
        }
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
    try { bleRssiScanner?.stopBeacon() } catch (_: Throwable) {}
    try { voiceTransceiver?.release() } catch (_: Throwable) {}
    vmScope.cancel()
  }
}
