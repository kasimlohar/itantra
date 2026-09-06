package com.itantra.data.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.itantra.data.asr.SherpaAsrEngine
import com.itantra.domain.model.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device Android Voice Transceiver for real-time speech capture and synthesis.
 * Uses native Sherpa-ONNX ASR (AI4Bharat IndicConformer CTC INT8) as primary offline engine,
 * with standard SpeechRecognizer fallback, and TextToSpeech with walkie-talkie squelch/Roger-beep.
 */
class AndroidVoiceTransceiver(
    private val context: Context,
    private val sherpaAsr: SherpaAsrEngine? = null
) : VoiceTransceiver {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val asrScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var audioPipeline: PttAudioCapturePipeline? = null
    private var isTtsReady = false
    private var isListening = false
    private var isListeningRequested = false
    private var currentFocusRequest: AudioFocusRequest? = null

    private data class PendingUtterance(
        val text: String,
        val langCode: String,
        val hasRetried: Boolean = false
    )
    private val pendingUtterances = ConcurrentHashMap<String, PendingUtterance>()

    init {
        // Eagerly warm up Sherpa-ONNX model in background
        asrScope.launch {
            try {
                sherpaAsr?.load(Language.HINDI)
                Log.i("iTantra", "SherpaAsrEngine eager load initialized: ready=${sherpaAsr?.isReady()}")
            } catch (e: Throwable) {
                Log.w("iTantra", "SherpaAsrEngine eager load failed", e)
            }
        }

        mainHandler.post {
            try {
                textToSpeech = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        isTtsReady = true
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                val attr = AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                                textToSpeech?.setAudioAttributes(attr)
                            }
                            val initLoc = Locale.forLanguageTag("hi-IN")
                            if (textToSpeech?.isLanguageAvailable(initLoc) ?: -1 >= TextToSpeech.LANG_AVAILABLE) {
                                textToSpeech?.language = initLoc
                            } else {
                                textToSpeech?.language = Locale.US
                            }
                        } catch (_: Throwable) {}
                        setupTtsListener()
                        Log.i("iTantra", "TextToSpeech initialized successfully")
                    } else {
                        Log.w("iTantra", "TextToSpeech init status: $status")
                    }
                }
            } catch (e: Throwable) {
                Log.e("iTantra", "Failed to create TextToSpeech", e)
            }
        }
    }

    private fun setupTtsListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d("iTantra", "TTS onStart: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d("iTantra", "TTS onDone: $utteranceId")
                if (utteranceId != null) pendingUtterances.remove(utteranceId)
                abandonFocus()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w("iTantra", "TTS onError: $utteranceId")
                handleTtsError(utteranceId, -1)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w("iTantra", "TTS onError: $utteranceId (code: $errorCode)")
                handleTtsError(utteranceId, errorCode)
            }

            private fun handleTtsError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == null) {
                    abandonFocus()
                    return
                }
                val pending = pendingUtterances.remove(utteranceId)
                if (pending != null && !pending.hasRetried) {
                    Log.w("iTantra", "TTS synthesis failed (code=$errorCode). Retrying utterance '${pending.text}' with safe offline fallback...")
                    mainHandler.post {
                        retryWithFallback(pending.text)
                    }
                } else {
                    abandonFocus()
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
        })
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

        // 1. Primary: Native Sherpa-ONNX offline ASR with direct PCM capture
        if (sherpaAsr != null && (sherpaAsr.isReady() || sherpaAsr.load(Language.HINDI).isSuccess)) {
            Log.i("iTantra", "Starting offline capture via PttAudioCapturePipeline + SherpaAsrEngine")
            isListening = true
            audioPipeline?.stopRecording()
            audioPipeline = PttAudioCapturePipeline(
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
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            13 -> "Language unavailable"
                            12 -> "Language not supported"
                            11 -> "Cannot check support"
                            else -> "Unknown error $error"
                        }
                        Log.w("iTantra", "SpeechRecognizer onError: $msg ($error) for $targetLocale")
                        isListening = false

                        val shouldFallback = error == 13 || error == 12 || error == 11 ||
                                error == SpeechRecognizer.ERROR_NETWORK ||
                                error == SpeechRecognizer.ERROR_SERVER ||
                                error == SpeechRecognizer.ERROR_CLIENT

                        if (isListeningRequested && shouldFallback && remainingCandidates.isNotEmpty()) {
                            val nextLocale = remainingCandidates.first()
                            Log.i("iTantra", "Retrying speech recognition with fallback locale: $nextLocale")
                            startListeningInternal(remainingCandidates, onPartial, onRms, onResult)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        Log.i("iTantra", "SpeechRecognizer final result: '$text' in $targetLocale")
                        if (text.isNotBlank()) {
                            onResult(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isNotBlank()) {
                            Log.d("iTantra", "SpeechRecognizer partial: '$text' in $targetLocale")
                            onPartial(text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLocale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLocale)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }

                recognizer.startListening(intent)
                Log.i("iTantra", "Started speech listening for $targetLocale (offline preferred)")
            } catch (e: Throwable) {
                Log.e("iTantra", "Exception starting SpeechRecognizer", e)
            }
        }
    }

    override fun stopListening() {
        isListeningRequested = false
        if (audioPipeline != null) {
            Log.i("iTantra", "Stopping PttAudioCapturePipeline")
            audioPipeline?.stopRecording()
            audioPipeline = null
            isListening = false
            return
        }

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                isListening = false
                Log.i("iTantra", "SpeechRecognizer stopped listening")
            } catch (e: Throwable) {
                Log.e("iTantra", "Exception stopping SpeechRecognizer", e)
            }
        }
    }

    override fun speak(text: String, langCode: String) {
        if (text.isBlank()) return
        mainHandler.post {
            try {
                try {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    mainHandler.postDelayed({ tg.release() }, 250)
                } catch (_: Throwable) {}

                if (isTtsReady && textToSpeech != null) {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val tts = textToSpeech!!

                    val allVoices = try { tts.voices } catch (_: Throwable) { null } ?: emptySet()
                    val offlineVoices = allVoices.filter { v ->
                        !v.isNetworkConnectionRequired &&
                        v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                    }
                    Log.i("iTantra", "TTS voices: total=${allVoices.size}, offlineEmbedded=${offlineVoices.size}")

                    val reqLocale = try {
                        Locale.forLanguageTag(langCode)
                    } catch (_: Throwable) {
                        Locale("hi", "IN")
                    }

                    // 1. Look for matching offline voice for requested language (e.g. Hindi "hi")
                    val matchingOfflineVoice = offlineVoices.firstOrNull { v ->
                        v.locale.language.equals(reqLocale.language, ignoreCase = true)
                    }

                    // 2. Fallback offline voice: en-IN -> en-US -> any offline voice
                    val fallbackOfflineVoice = offlineVoices.firstOrNull { v ->
                        v.locale.language.equals("en", ignoreCase = true) && v.locale.country.equals("IN", ignoreCase = true)
                    } ?: offlineVoices.firstOrNull { v ->
                        v.locale.language.equals("en", ignoreCase = true)
                    } ?: offlineVoices.firstOrNull()

                    val (chosenVoice, textToSynthesize, targetLocale) = when {
                        matchingOfflineVoice != null -> {
                            Log.i("iTantra", "Selected native offline voice: ${matchingOfflineVoice.name} for ${reqLocale.language}")
                            Triple(matchingOfflineVoice, text, matchingOfflineVoice.locale)
                        }
                        fallbackOfflineVoice != null -> {
                            val romanized = if (DevanagariTransliterator.containsDevanagari(text)) {
                                DevanagariTransliterator.transliterate(text)
                            } else {
                                text
                            }
                            Log.w("iTantra", "No offline voice for ${reqLocale.language}. Using fallback voice ${fallbackOfflineVoice.name}. Transliterated: '$text' -> '$romanized'")
                            Triple(fallbackOfflineVoice, romanized, fallbackOfflineVoice.locale)
                        }
                        else -> {
                            val avail = tts.isLanguageAvailable(reqLocale)
                            val loc = if (avail < TextToSpeech.LANG_AVAILABLE) Locale.US else reqLocale
                            val finalTxt = if (loc.language != "hi" && DevanagariTransliterator.containsDevanagari(text)) {
                                DevanagariTransliterator.transliterate(text)
                            } else {
                                text
                            }
                            Log.w("iTantra", "No enumerable offline voices. Fallback to locale $loc. Text: '$finalTxt'")
                            Triple(null, finalTxt, loc)
                        }
                    }

                    if (chosenVoice != null) {
                        try {
                            tts.voice = chosenVoice
                        } catch (e: Throwable) {
                            Log.w("iTantra", "Failed to set voice ${chosenVoice.name}", e)
                        }
                    }
                    tts.language = targetLocale

                    if (am != null) {
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

                    val params = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }
                    val utteranceId = "itantra_tts_${System.currentTimeMillis()}"
                    pendingUtterances[utteranceId] = PendingUtterance(text = text, langCode = langCode, hasRetried = false)

                    val speakResult = tts.speak(
                        textToSynthesize,
                        TextToSpeech.QUEUE_FLUSH,
                        params,
                        utteranceId
                    )
                    Log.i("iTantra", "TTS speak called: '$textToSynthesize' via ${chosenVoice?.name ?: targetLocale} (result=$speakResult)")
                } else {
                    Log.w("iTantra", "TTS not ready yet when speak requested (isTtsReady=$isTtsReady)")
                }
            } catch (e: Throwable) {
                Log.e("iTantra", "Exception in TTS speak", e)
            }
        }
    }

    private fun retryWithFallback(text: String) {
        try {
            if (!isTtsReady || textToSpeech == null) {
                return
            }
            val tts = textToSpeech!!
            val allVoices = try { tts.voices } catch (_: Throwable) { null } ?: emptySet()
            val offlineFallback = allVoices.firstOrNull { v ->
                !v.isNetworkConnectionRequired &&
                v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true &&
                v.locale.language.equals("en", ignoreCase = true)
            } ?: allVoices.firstOrNull { !it.isNetworkConnectionRequired }

            val retryText = if (DevanagariTransliterator.containsDevanagari(text)) {
                DevanagariTransliterator.transliterate(text)
            } else {
                text
            }

            if (offlineFallback != null) {
                try { tts.voice = offlineFallback } catch (_: Throwable) {}
                tts.language = offlineFallback.locale
                Log.i("iTantra", "Retrying with fallback voice: ${offlineFallback.name}, text: '$retryText'")
            } else {
                tts.language = Locale.US
                Log.i("iTantra", "Retrying with Locale.US, text: '$retryText'")
            }

            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val retryUtteranceId = "itantra_tts_retry_${System.currentTimeMillis()}"
            pendingUtterances[retryUtteranceId] = PendingUtterance(text = text, langCode = "en", hasRetried = true)
            val res = tts.speak(retryText, TextToSpeech.QUEUE_FLUSH, params, retryUtteranceId)
            Log.i("iTantra", "Retry speak result: $res")
        } catch (e: Throwable) {
            Log.e("iTantra", "Exception in retryWithFallback", e)
        }
    }

    override fun stopSpeaking() {
        mainHandler.post {
            try {
                textToSpeech?.stop()
            } catch (_: Throwable) {}
        }
    }

    override fun release() {
        audioPipeline?.stopRecording()
        audioPipeline = null
        sherpaAsr?.unload()
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Throwable) {}
            try {
                textToSpeech?.stop()
                textToSpeech?.shutdown()
                textToSpeech = null
            } catch (_: Throwable) {}
            Log.i("iTantra", "VoiceTransceiver released")
        }
    }
}

/**
 * Host JVM fallback implementation for unit tests.
 */
class FakeVoiceTransceiver : VoiceTransceiver {
    override fun startListening(
        langCode: String,
        onPartial: (String) -> Unit,
        onRms: (Float) -> Unit,
        onResult: (String) -> Unit
    ) {
        onRms(4.0f)
        onPartial("परीक्षण")
        onResult("नमस्ते परीक्षण")
    }

    override fun stopListening() {}
    override fun speak(text: String, langCode: String) {}
    override fun isAvailable(): Boolean = true
    override fun release() {}
}
