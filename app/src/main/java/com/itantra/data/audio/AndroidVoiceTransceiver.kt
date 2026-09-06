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
import android.util.Log
import java.util.Locale

/**
 * On-device Android Voice Transceiver for real-time speech capture and synthesis.
 * Uses standard SpeechRecognizer for robust compatibility across Android OEM vendors,
 * and TextToSpeech with walkie-talkie squelch/Roger-beep for incoming transmissions.
 */
class AndroidVoiceTransceiver(
    private val context: Context
) : VoiceTransceiver {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var isListening = false

    init {
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
                            // Set initial default
                            val initLoc = Locale.forLanguageTag("hi-IN")
                            if (textToSpeech?.isLanguageAvailable(initLoc) ?: -1 >= TextToSpeech.LANG_AVAILABLE) {
                                textToSpeech?.language = initLoc
                            } else {
                                textToSpeech?.language = Locale.US
                            }
                        } catch (_: Throwable) {}
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

    override fun isAvailable(): Boolean {
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
        mainHandler.post {
            try {
                // Cancel and release any previous recognizer instance
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer?.cancel()
                        speechRecognizer?.destroy()
                    } catch (_: Throwable) {}
                    speechRecognizer = null
                }

                val appContext = context.applicationContext
                val recognizer = try {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                } catch (e: Throwable) {
                    Log.w("iTantra", "Failed to create SpeechRecognizer", e)
                    null
                }

                if (recognizer == null) {
                    Log.w("iTantra", "SpeechRecognizer instance could not be created")
                    return@post
                }

                speechRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("iTantra", "Ready for speech in $langCode")
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
                        Log.w("iTantra", "SpeechRecognizer onError: $msg ($error)")
                        isListening = false
                        if ((error == 13 || error == 12 || error == 11 || error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_SERVER) && langCode != "en-IN") {
                            Log.i("iTantra", "Retrying speech recognition with fallback locale en-IN")
                            startListening("en-IN", onPartial, onRms, onResult)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        Log.i("iTantra", "SpeechRecognizer final result: '$text'")
                        if (text.isNotBlank()) {
                            onResult(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isNotBlank()) {
                            Log.d("iTantra", "SpeechRecognizer partial: '$text'")
                            onPartial(text)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langCode)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                    putExtra("android.speech.extra.DICTATION_MODE", true)
                }

                recognizer.startListening(intent)
                Log.i("iTantra", "Started speech listening for $langCode")
            } catch (e: Throwable) {
                Log.e("iTantra", "Exception starting SpeechRecognizer", e)
            }
        }
    }

    override fun stopListening() {
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
                // Play short walkie-talkie Roger beep on incoming transmission
                try {
                    val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                    mainHandler.postDelayed({ tg.release() }, 250)
                } catch (_: Throwable) {}

                if (isTtsReady && textToSpeech != null) {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val loc = try {
                        Locale.forLanguageTag(langCode)
                    } catch (_: Throwable) {
                        Locale.US
                    }
                    val avail = textToSpeech?.isLanguageAvailable(loc) ?: -1
                    val targetLoc = if (avail < TextToSpeech.LANG_AVAILABLE) {
                        Log.w("iTantra", "TTS locale $loc not available (avail=$avail), falling back to US English")
                        Locale.US
                    } else {
                        loc
                    }
                    textToSpeech?.language = targetLoc

                    // Request transient audio focus so Xiaomi AudioHardening allows playback
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
                    val speakResult = textToSpeech?.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        params,
                        utteranceId
                    )
                    Log.i("iTantra", "TTS speak called: '$text' in $targetLoc (result=$speakResult)")
                } else {
                    Log.w("iTantra", "TTS not ready yet when speak requested (isTtsReady=$isTtsReady)")
                }
            } catch (e: Throwable) {
                Log.e("iTantra", "Exception in TTS speak", e)
            }
        }
    }

    override fun release() {
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
