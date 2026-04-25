package com.jarvis.assistant.wakeword

import android.content.Context
import com.jarvis.assistant.audio.SpeechToText

/**
 * Detector de palavra de ativacao "JARVIS".
 *
 * Implementacao pragmatica usando o SpeechRecognizer do Android: ouvimos
 * em loop continuo e procuramos a palavra "JARVIS" (ou variantes que o
 * STT costuma errar — "travis", "jarves") nos resultados parciais.
 *
 * Para qualidade superior (reconhecimento on-device sub-100ms) e
 * possivel trocar por Picovoice Porcupine / OpenWakeWord; a interface
 * abaixo facilita essa substituicao.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWake: () -> Unit,
) {
    private val variants = listOf(
        "jarvis", "járvis", "jarves", "travis", "garvis", "yarvis",
    )

    private var stt: SpeechToText? = null

    fun start() {
        if (stt != null) return
        val rec = SpeechToText(
            context = context,
            language = "pt-BR",
            onPartial = { text -> if (matchesWake(text)) onWake() },
            onFinal = { text -> if (matchesWake(text)) onWake() },
            onError = { /* reconhecedor reinicia sozinho */ },
        )
        rec.startContinuous()
        stt = rec
    }

    fun stop() {
        stt?.stop()
        stt = null
    }

    private fun matchesWake(text: String): Boolean {
        val lower = text.lowercase()
        return variants.any { lower.contains(it) }
    }
}
