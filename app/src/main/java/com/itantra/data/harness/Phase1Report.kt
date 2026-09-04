package com.itantra.data.harness

import com.itantra.domain.model.Language

class Phase1Report {
    private val thresholds = mapOf(
        Language.HINDI to 0.082,
        Language.BENGALI to 0.091,
        Language.TAMIL to 0.098,
        Language.TELUGU to 0.104,
        Language.MARATHI to 0.101,
        Language.GUJARATI to 0.113,
        Language.KANNADA to 0.109,
        Language.MALAYALAM to 0.117,
        Language.ODIA to 0.124,
        Language.ENGLISH to 0.072
    )
    private val results = mutableMapOf<Language, HarnessResult>()

    fun expectedLangs() = Language.entries.toList()
    fun thresholdFor(lang: Language) = thresholds[lang] ?: 0.124
    fun record(lang: Language, result: HarnessResult) { results[lang] = result }

    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.append("| Lang | WER | CER | RTF STT | RTF TTS | Thr |\n")
        sb.append("|---|---|---|---|---|---|\n")
        for (lang in expectedLangs()) {
            val r = results[lang]
            val wer = r?.wer?.let { String.format("%.2f%%", it * 100) } ?: "pending"
            val cer = r?.cer?.let { String.format("%.2f%%", it * 100) } ?: "pending"
            val stt = r?.let { String.format("%.3f", it.sttRtf) } ?: "-"
            val tts = r?.let { String.format("%.3f", it.ttsRtf) } ?: "-"
            val thr = String.format("%.1f%%", thresholdFor(lang) * 100)
            sb.append("| ${lang.name.lowercase()} | $wer | $cer | $stt | $tts | $thr |\n")
        }
        return sb.toString()
    }
}
