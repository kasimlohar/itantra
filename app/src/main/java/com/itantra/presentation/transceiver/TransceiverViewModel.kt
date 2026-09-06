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
import com.itantra.data.audio.AlertAudioManager

@HiltViewModel
class TransceiverViewModel @Inject constructor(
  private val transportManager: TransportManager,
  private val pttMachine: PttStateMachine,
  private val router: PriorityRouter,
  private val alertAudio: AlertAudioManager? = null,
  private val voiceTransceiver: com.itantra.data.audio.VoiceTransceiver? = null
) : ViewModel() {
  private val _state = MutableStateFlow(TransceiverUiState())
  val state: StateFlow<TransceiverUiState> = _state
  private val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var seqCounter = 1
  @Volatile private var capturedSpeech: String = ""
  @Volatile private var isSpeechFrameSent: Boolean = false
  @Volatile private var lastFloorReleaseTime: Long = 0L

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
    val item = MessageItem(f, false, System.currentTimeMillis(), 2.0)
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
    }
    vmScope.launch {
      transportManager.state.collect { connState ->
        _state.value = _state.value.copy(connectionState = connState)
      }
    }
    vmScope.launch {
      transportManager.incomingFrames.collect { frame ->
        process(TransceiverIntent.OnFrameReceived(frame))
      }
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
        val ev = pttMachine.onLocalPress()
        val nextUi = when (ev) {
          is PttEvent.SendControlFrame -> PttUiState.LISTENING
          is PttEvent.ChannelBusy -> PttUiState.BUSY
          is PttEvent.FloorDenied -> PttUiState.LISTENING
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
            println("[iTantra] Error starting voice transceiver: ${e.message}")
          }
        } else {
          _state.value = _state.value.copy(pttUiState = nextUi)
        }
      }
      is TransceiverIntent.FloorRelease -> {
        if (_state.value.pttUiState != PttUiState.LISTENING) {
          return
        }
        try { pttMachine.onLocalRelease() } catch (_: Exception) {}
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
      }
      is TransceiverIntent.SelectLanguage -> {
        _state.value = _state.value.copy(srcLang = intent.src, dstLang = intent.dst)
      }
      is TransceiverIntent.SendAlert -> {
        _state.value = _state.value.copy(isAlertActive = true, alertTranscript = intent.text)
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
        val item = MessageItem(alertFrame, true, System.currentTimeMillis(), 4.0)
        _state.value = _state.value.copy(messageHistory = _state.value.messageHistory + item)
        vmScope.launch {
          transportManager.send(alertFrame)
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
      is TransceiverIntent.OnFrameReceived -> {
        val item = MessageItem(intent.frame, intent.frame.isAlert, System.currentTimeMillis(), 2.5)
        if (intent.frame.isAlert) {
          try {
            alertAudio?.acquireAlarmFocus()
            alertAudio?.vibrate(longArrayOf(0, 500, 200, 500))
          } catch (_: Exception) {}
        }
        _state.value = _state.value.copy(
          messageHistory = _state.value.messageHistory + item,
          currentTranscript = intent.frame.payloadText,
          isAlertActive = intent.frame.isAlert || _state.value.isAlertActive,
          alertTranscript = if (intent.frame.isAlert) intent.frame.payloadText else _state.value.alertTranscript
        )
        try {
          val langTag = toBcp47(intent.frame.dstLang)
          voiceTransceiver?.speak(intent.frame.payloadText, langTag)
        } catch (_: Throwable) {}
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    try { voiceTransceiver?.release() } catch (_: Throwable) {}
    vmScope.cancel()
  }
}
