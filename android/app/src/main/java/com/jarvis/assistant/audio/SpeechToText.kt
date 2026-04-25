package com.jarvis.assistant.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Wrapper sobre SpeechRecognizer do Android.
 *
 * Em pt-BR ele transcreve a fala do usuario localmente / via Google Speech.
 * Quando uma frase final chega, mandamos para o servidor via WebSocket.
 *
 * Este reconhecedor reinicia automaticamente entre frases, criando o efeito
 * de "sempre escutando". A deteccao de palavra de ativacao ("JARVIS") fica
 * a cargo de outro componente (WakeWordDetector) — aqui apenas transcrevemos
 * tudo que e dito quando estamos em modo ativo.
 */
class SpeechToText(
    private val context: Context,
    private val language: String = "pt-BR",
    private val onPartial: (String) -> Unit = {},
    private val onFinal: (String) -> Unit,
    private val onError: (Int) -> Unit = {},
) {
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listening = false
    private var restartOnEnd = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Liga o reconhecedor de forma continua. */
    fun startContinuous() {
        restartOnEnd = true
        startSingle()
    }

    fun stop() {
        restartOnEnd = false
        listening = false
        mainHandler.post {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun startSingle() {
        if (listening) return
        listening = true
        mainHandler.post {
            val rec = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = rec
            rec.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            try {
                rec.startListening(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startListening failed: ${t.message}")
                listening = false
                rescheduleIfNeeded()
            }
        }
    }

    private fun rescheduleIfNeeded(delayMs: Long = 600) {
        if (!restartOnEnd) return
        mainHandler.postDelayed({
            listening = false
            startSingle()
        }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) onPartial(it)
            }
        }

        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) onFinal(it)
            }
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
            listening = false
            rescheduleIfNeeded(300)
        }

        override fun onError(error: Int) {
            // Erros 6 (sem fala) e 7 (sem reconhecimento) sao normais — apenas reinicie.
            Log.d(TAG, "STT error $error")
            try { recognizer?.destroy() } catch (_: Throwable) {}
            recognizer = null
            listening = false
            onError(error)
            val delay = when (error) {
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2000L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                else -> 500L
            }
            rescheduleIfNeeded(delay)
        }
    }

    companion object { private const val TAG = "JarvisSTT" }
}
