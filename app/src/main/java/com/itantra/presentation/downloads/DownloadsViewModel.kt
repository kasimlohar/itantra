package com.itantra.presentation.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ── Model entry data ──────────────────────────────────────────────────────────
enum class ModelInstallState {
    INSTALLED,
    DOWNLOADABLE,
    DOWNLOADING,
    FAILED
}

data class ModelEntry(
    val id: String,
    val displayName: String,
    val sizeMb: Int,
    val fileName: String,
    val subDir: String,
    val isVad: Boolean = false,
    var installState: ModelInstallState = ModelInstallState.DOWNLOADABLE,
    var downloadProgress: Float = 0f
)

data class DownloadsUiState(
    val models: List<ModelEntry> = emptyList(),
    val totalBundleMb: Int = 0,
    val isLoading: Boolean = true
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadsUiState(isLoading = true))
    val state: StateFlow<DownloadsUiState> = _state

    // Canonical model catalogue (per PRD §3.1)
    private val catalogue: List<ModelEntry> = listOf(
        ModelEntry("vad",       "Voice Activity Detector", 2,   "silero_vad_v4.onnx",      "vad",    isVad = true),
        ModelEntry("stt_hi",    "Hindi STT Engine",        188, "stt_hi_int8.onnx",         "stt/hi"),
        ModelEntry("stt_gu",    "Gujarati STT Engine",     188, "stt_gu_int8.onnx",         "stt/gu"),
        ModelEntry("stt_mr",    "Marathi STT Engine",      188, "stt_mr_int8.onnx",         "stt/mr"),
        ModelEntry("stt_kn",    "Kannada STT Engine",      188, "stt_kn_int8.onnx",         "stt/kn"),
        ModelEntry("stt_ml",    "Malayalam STT Engine",    188, "stt_ml_int8.onnx",         "stt/ml"),
        ModelEntry("stt_ta",    "Tamil STT Engine",        188, "stt_ta_int8.onnx",         "stt/ta"),
        ModelEntry("stt_te",    "Telugu STT Engine",       188, "stt_te_int8.onnx",         "stt/te"),
        ModelEntry("stt_bn",    "Bengali STT Engine",      188, "stt_bn_int8.onnx",         "stt/bn"),
        ModelEntry("stt_en",    "English STT Engine",      188, "stt_en_int8.onnx",         "stt/en"),
        ModelEntry("lid",       "Language Auto-Detector",    1, "lid_176.ftz",              "lid")
    )

    init {
        refreshInstallStates()
    }

    fun refreshInstallStates() {
        viewModelScope.launch(Dispatchers.IO) {
            val models = catalogue.map { entry ->
                val installed = isModelInstalled(entry)
                entry.copy(installState = if (installed) ModelInstallState.INSTALLED else ModelInstallState.DOWNLOADABLE)
            }
            val totalMb = models.filter { it.installState == ModelInstallState.DOWNLOADABLE }.sumOf { it.sizeMb }
            _state.value = DownloadsUiState(
                models = models,
                totalBundleMb = totalMb,
                isLoading = false
            )
        }
    }

    /**
     * Check if a model file exists in:
     * 1. context.filesDir/models/<subDir>/<fileName>
     * 2. assets/models/<subDir>/<fileName> (bundled)
     */
    private fun isModelInstalled(entry: ModelEntry): Boolean {
        val filesDir = File(context.filesDir, "models/${entry.subDir}/${entry.fileName}")
        if (filesDir.exists() && filesDir.length() > 1024L) return true

        return try {
            context.assets.open("models/${entry.subDir}/${entry.fileName}").use { true }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Initiate download for a single model.
     * Currently transitions state to DOWNLOADING and logs — real HTTP download
     * must be wired by the team when a CDN URL is available.
     */
    fun downloadModel(entry: ModelEntry) {
        val updated = _state.value.models.map {
            if (it.id == entry.id) it.copy(installState = ModelInstallState.DOWNLOADING, downloadProgress = 0f) else it
        }
        _state.value = _state.value.copy(models = updated)
        android.util.Log.i("iTantra", "Download requested for ${entry.displayName} — CDN integration pending")
    }

    /**
     * Initiate full bundle download.
     */
    fun downloadBundle() {
        val downloadable = _state.value.models.filter {
            it.installState == ModelInstallState.DOWNLOADABLE
        }
        downloadable.forEach { downloadModel(it) }
    }

    fun deleteModel(entry: ModelEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, "models/${entry.subDir}/${entry.fileName}")
                if (file.exists()) file.delete()
            } catch (_: Throwable) {}
            refreshInstallStates()
        }
    }
}
