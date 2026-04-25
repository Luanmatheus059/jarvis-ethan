package com.jarvis.assistant.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Text-to-Speech local — usa o motor TTS do proprio Android.
 *
 * Em pt-BR a maioria dos aparelhos ja vem com a voz do Google instalada.
 * Para uma voz mais grave / "JARVIS-like", o senhor pode:
 *   1. Configuracoes → Acessibilidade → Saida de texto pra fala
 *   2. Instalar uma voz pt-BR mais natural (ex.: Vocalizer, RHVoice)
 *   3. Ajustar pitch e taxa abaixo (defaults: pitch 0.85, rate 1.0).
 *
 * Nao depende de internet nem de chave de API.
 */
class LocalTextToSpeech(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val profile = VoiceProfile(context)

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun init(onReady: (Boolean) -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val p = profile.current()
                tts?.language = Locale("pt", "BR")
                tts?.setPitch(p.pitch)         // grave por padrao, ajustado pelo perfil
                tts?.setSpeechRate(p.speechRate)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
                    override fun onDone(utteranceId: String?) { _isSpeaking.value = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { _isSpeaking.value = false }
                })
                ready = true
                onReady(true)
            } else {
                Log.w(TAG, "TTS init falhou: $status")
                onReady(false)
            }
        }
    }

    fun speak(text: String, utteranceId: String = "jarvis") {
        if (!ready) init { if (it) speak(text, utteranceId) }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /** Recarrega o perfil de voz (ex.: depois do senhor enviar uma nova amostra). */
    fun reloadProfile() {
        val p = profile.current()
        tts?.setPitch(p.pitch)
        tts?.setSpeechRate(p.speechRate)
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ready = false
    }

    companion object { private const val TAG = "JarvisTts" }
}
