package com.itantra.data.harness

import com.itantra.domain.model.Language
import java.io.File

object HarnessCsv {
    fun header() = "lang,audioSec,sttRtf,ttsRtf,cer,wer,transcription"
    fun row(lang: Language, r: HarnessResult): String {
        val clean = r.transcription.replace(",", " ").replace("\n", " ").replace("\r", " ")
        val cerStr = r.cer?.let { String.format("%.4f", it) } ?: ""
        val werStr = r.wer?.let { String.format("%.4f", it) } ?: ""
        return "${lang.name.lowercase()},${String.format("%.3f", r.audioDurationSec)},${String.format("%.4f", r.sttRtf)},${String.format("%.4f", r.ttsRtf)},$cerStr,$werStr,$clean"
    }
    fun write(file: File, lang: Language, r: HarnessResult) {
        val needHeader = !file.exists() || file.length() == 0L
        if (needHeader) file.parentFile?.mkdirs()
        file.appendText((if (needHeader) header() + "\n" else "") + row(lang, r) + "\n")
    }
}
