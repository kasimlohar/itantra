package com.itantra.data.harness

data class GateResult(val pass: Boolean, val sttPass: Boolean, val ttsPass: Boolean, val reason: String)

object HarnessGate {
    const val STT_THR = 0.30
    const val TTS_THR = 0.20
    fun check(r: HarnessResult): GateResult {
        val sttPass = r.sttRtf < STT_THR
        val ttsPass = r.ttsRtf < TTS_THR
        val pass = sttPass && ttsPass
        val reason = if (pass) "PASS" else "FAIL sttRtf=${r.sttRtf} ttsRtf=${r.ttsRtf} thr $STT_THR/$TTS_THR"
        return GateResult(pass, sttPass, ttsPass, reason)
    }
}
