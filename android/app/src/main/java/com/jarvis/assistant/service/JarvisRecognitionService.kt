package com.jarvis.assistant.service

import android.content.Intent
import android.speech.RecognitionService

/**
 * Stub de RecognitionService — necessario apenas para que o JARVIS
 * possa ser declarado como assistente digital. O reconhecimento real
 * acontece no JarvisForegroundService usando o SpeechRecognizer do
 * proprio sistema.
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        // Delegamos ao servico em primeiro plano.
        JarvisForegroundService.triggerListen(this)
        listener.endOfSpeech()
    }

    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
