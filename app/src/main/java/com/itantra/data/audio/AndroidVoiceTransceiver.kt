package com.itantra.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.itantra.data.asr.SherpaAsrEngine
import com.itantra.data.tts.SherpaTtsEngine
import com.itantra.domain.model.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * On-device Android Voice Transceiver for real-time speech capture and synthesis.
 * Uses native Sherpa-ONNX ASR (AI4Bharat IndicConformer CTC INT8) and Silero VAD (v5)
 * as primary offline capture pipeline, and Sherpa-ONNX Piper VITS neural TTS with
 * direct AudioTrack PCM streaming for offline walkie-talkie playback.
 * Fulfills PRD FR-03, FR-05, US-01, US-02, and KPIs K1, K2.
 */
class AndroidVoiceTransceiver(
    private val context: Context,
    private val sherpaAsr: SherpaAsrEngine? = null,
    private val sherpaTts: SherpaTtsEngine? = null
) : VoiceTransceiver {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val asrScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ttsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var speechRecognizer: SpeechRecognizer? = null
    private var audioPipeline: PttAudioCapturePipeline? = null
    private var currentAudioTrack: AudioTrack? = null
    private var currentFocusRequest: AudioFocusRequest? = null

    private var isListening = false
    private var isListeningRequested = false

    init {
        // Eagerly warm up Sherpa-ONNX ASR and Piper VITS TTS in background
        asrScope.launch {
            try {
                sherpaAsr?.load(Language.HINDI)
                Log.i("iTantra", "SherpaAsrEngine eager load initialized: ready=${sherpaAsr?.isReady()}")
            } catch (e: Throwable) {
                Log.w("iTantra", "SherpaAsrEngine eager load failed", e)
            }
            try {
                sherpaTts?.loadVoice(Language.HINDI)
                Log.i("iTantra", "SherpaTtsEngine eager load initialized: ready=${sherpaTts?.isReady()}")
            } catch (e: Throwable) {
                Log.w("iTantra", "SherpaTtsEngine eager load failed", e)
            }
        }
    }

    override fun isAvailable(): Boolean {
        if (sherpaAsr?.isReady() == true) return true
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
    }

    override fun startListening(
        langCode: String,
        onPartial: (String) -> Unit,
        onRms: (Float) -> Unit,
        onResult: (String) -> Unit
    ) {
        isListeningRequested = true

        // 1. Primary: Native Sherpa-ONNX offline ASR with direct PCM capture and Silero VAD
        if (sherpaAsr != null && (sherpaAsr.isReady() || sherpaAsr.load(Language.HINDI).isSuccess)) {
            Log.i("iTantra", "Starting offline capture via PttAudioCapturePipeline + Silero VAD + SherpaAsrEngine")
            isListening = true
            audioPipeline?.stopRecording()
            audioPipeline = PttAudioCapturePipeline(
                context = context,
                continuousDuplex = false,
                onRms = onRms,
                onUtteranceRecorded = { waveform ->
                    isListening = false
                    asrScope.launch {
                        try {
                            Log.i("iTantra", "Transcribing ${waveform.size} samples with SherpaAsrEngine...")
                            val res = sherpaAsr.transcribeFloat(waveform, Language.HINDI)
                            val text = res.getOrDefault("").trim()
                            Log.i("iTantra", "SherpaAsrEngine result: '$text'")
                            mainHandler.post {
                                if (text.isNotBlank()) {
                                    onResult(text)
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e("iTantra", "Error in Sherpa transcription", e)
                        }
                    }
                }
            )
            audioPipeline?.startRecording()
            return
        }

        // 2. Fallback: Android SpeechRecognizer with multi-locale cascade
        Log.w("iTantra", "SherpaAsr not ready, falling back to platform SpeechRecognizer")
        val defaultLocaleTag = try {
            Locale.getDefault().toLanguageTag()
        } catch (_: Throwable) {
            "en-US"
        }
        val candidates = linkedSetOf<String>()
        candidates.add(langCode)
        candidates.add(defaultLocaleTag)
        candidates.add("en-US")
        candidates.add("en-IN")
        if (langCode.contains("-")) {
            candidates.add(langCode.substringBefore("-"))
        }

        startListeningInternal(candidates.toList(), onPartial, onRms, onResult)
    }

    private fun startListeningInternal(
        candidateLocales: List<String>,
        onPartial: (String) -> Unit,
        onRms: (Float) -> Unit,
        onResult: (String) -> Unit
    ) {
        if (!isListeningRequested) {
            Log.d("iTantra", "startListeningInternal skipped because listening is no longer requested")
            return
        }
        if (candidateLocales.isEmpty()) {
            Log.w("iTantra", "No more fallback candidate locales available for speech recognition")
            return
        }

        val targetLocale = candidateLocales.first()
        val remainingCandidates = candidateLocales.drop(1)

        mainHandler.post {
            if (!isListeningRequested) return@post
            try {
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer?.cancel()
                        speechRecognizer?.destroy()
                    } catch (_: Throwable) {}
                    speechRecognizer = null
                }

                val appContext = context.applicationContext
                val recognizer = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
                        Log.i("iTantra", "Creating on-device SpeechRecognizer")
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                    } else {
                        Log.i("iTantra", "Creating standard SpeechRecognizer")
                        SpeechRecognizer.createSpeechRecognizer(appContext)
                    }
                } catch (e: Throwable) {
                    Log.w("iTantra", "Failed to create on-device recognizer, falling back", e)
                    try {
                        SpeechRecognizer.createSpeechRecognizer(appContext)
                    } catch (e2: Throwable) {
                        Log.e("iTantra", "Failed to create standard recognizer", e2)
                        null
                    }
                }

                if (recognizer == null) {
                    Log.w("iTantra", "SpeechRecognizer instance could not be created")
                    return@post
                }

                speechRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("iTantra", "Ready for speech in $targetLocale")
                        isListening = true
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d("iTantra", "Beginning of speech detected")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        onRms(rmsdB)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d("iTantra", "End of speech detected")
                    }

                    override fun onError(error: Int) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
                            SpeechRecognizer.ERROR_SERVER -> "Error from server"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            else -> "Unknown error ($error)"
                        }
                        Log.w("iTantra", "SpeechRecognizer error: $msg (code: $error) for locale: $targetLocale")
                        isListening = false

                        val isRecoverableLanguageError = (
                            error == SpeechRecognizer.ERROR_SERVER ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_NO_MATCH
                        )
                        if (isRecoverableLanguageError && remainingCandidates.isNotEmpty() && isListeningRequested) {
                            Log.w("iTantra", "Cascading to next fallback locale: ${remainingCandidates.first()}")
                            startListeningInternal(remainingCandidates, onPartial, onRms, onResult)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.i("iTantra", "Speech recognition final result: '$text'")
                        isListening = false
                        if (text.isNotBlank()) {
                            onResult(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            onPartial(text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLocale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLocale)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, targetLocale)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    }
                }

                recognizer.startListening(intent)
            } catch (e: Throwable) {
                Log.e("iTantra", "Exception in startListening", e)
                isListening = false
                if (remainingCandidates.isNotEmpty() && isListeningRequested) {
                    startListeningInternal(remainingCandidates, onPartial, onRms, onResult)
                }
            }
        }
    }

    override fun stopListening() {
        isListeningRequested = false
        if (!isListening && audioPipeline == null && speechRecognizer == null) return
        isListening = false

        audioPipeline?.let { pipeline ->
            Log.i("iTantra", "Stopping PttAudioCapturePipeline")
            pipeline.stopRecording()
            audioPipeline = null
        }

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Throwable) {
                Log.e("iTantra", "Exception stopping SpeechRecognizer", e)
            }
        }
    }

    private var playbackJob: Job? = null

    override fun speak(text: String, langCode: String) {
        if (text.isBlank()) return

        // Cancel previous speech/playback job to avoid concurrent AudioTrack writes
        playbackJob?.cancel()

        playbackJob = ttsScope.launch {
            try {
                stopSpeaking()

                // Play walkie-talkie tone beep before speech
                try {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    mainHandler.postDelayed({ tg.release() }, 250)
                } catch (_: Throwable) {}

                // Request Audio Focus
                requestAudioFocus()

                val tts = sherpaTts
                if (tts != null) {
                    if (!tts.isReady()) {
                        tts.loadVoice(Language.HINDI)
                    }
                    Log.i("iTantra", "Synthesizing text with SherpaTtsEngine (Piper VITS): '$text'")
                    val synthResult = tts.synthesize(text, Language.HINDI)
                    if (synthResult.isSuccess) {
                        val speechBuffer = synthResult.getOrThrow()
                        Log.i("iTantra", "Streaming ${speechBuffer.pcm.size} PCM samples @ ${speechBuffer.sampleRate} Hz to AudioTrack")
                        playPcm(speechBuffer.pcm, speechBuffer.sampleRate)
                    } else {
                        Log.w("iTantra", "SherpaTts synthesis failed: ${synthResult.exceptionOrNull()}")
                    }
                } else {
                    Log.w("iTantra", "SherpaTtsEngine not provided to AndroidVoiceTransceiver")
                }
            } catch (e: CancellationException) {
                // Normal job cancellation due to preemption
            } catch (e: Throwable) {
                Log.e("iTantra", "Exception in speak()", e)
            } finally {
                abandonFocus()
            }
        }
    }

    private suspend fun playPcm(pcm: ShortArray, sampleRate: Int) {
        if (pcm.isEmpty()) return
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferCapacity = maxOf(minBufferSize, pcm.size * 2)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferCapacity)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            Log.e("iTantra", "Failed to create AudioTrack for TTS playback", e)
            return
        }

        currentAudioTrack = track
        try {
            track.play()
            var offset = 0
            val chunkSize = 1024
            while (offset < pcm.size && currentAudioTrack == track) {
                val toWrite = minOf(chunkSize, pcm.size - offset)
                val written = track.write(pcm, offset, toWrite)
                if (written <= 0) break
                offset += written
            }
            // Responsively wait for playback head to drain the buffer without blocking threads
            if (currentAudioTrack == track) {
                val totalFrames = pcm.size
                val maxWaitMs = ((totalFrames * 1000L) / sampleRate) + 200L
                val startTime = System.currentTimeMillis()
                while (currentAudioTrack == track &&
                    track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < totalFrames &&
                    (System.currentTimeMillis() - startTime) < maxWaitMs
                ) {
                    delay(30L)
                }
            }
        } catch (e: Throwable) {
            Log.w("iTantra", "AudioTrack playback interrupted or failed", e)
        } finally {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (_: Throwable) {}
            if (currentAudioTrack == track) {
                currentAudioTrack = null
            }
        }
    }

    override fun stopSpeaking() {
        try {
            currentAudioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
                track.release()
            }
        } catch (e: Throwable) {
            Log.w("iTantra", "Error stopping AudioTrack", e)
        } finally {
            currentAudioTrack = null
            abandonFocus()
        }
    }

    private fun requestAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            currentFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        mainHandler.post {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                currentFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    override fun release() {
        stopSpeaking()
        audioPipeline?.stopRecording()
        audioPipeline = null
        sherpaAsr?.unload()
        sherpaTts?.unload()
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Throwable) {}
            Log.i("iTantra", "VoiceTransceiver released")
        }
    }
}
