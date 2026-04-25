package com.jarvis.assistant.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Text-to-Speech local em pt-BR.
 *
 * Estratégia para soar como JARVIS (homem, voz grave):
 *  1. Listar todas as vozes pt-BR instaladas no aparelho (TextToSpeech.getVoices()).
 *  2. Se houver uma voz que pareça masculina pelo nome, escolher essa.
 *  3. Caso contrário, escolher a primeira pt-BR e aplicar pitch baixo (0.78).
 *  4. O perfil do usuário pode sobrescrever a voz manualmente na tela "Voz".
 *
 * Tudo offline e gratuito. Para qualidade superior, o usuário pode
 * instalar Google TTS (atualizado), Vocalizer ou RHVoice via Play Store.
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
                applyProfile()
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

    /** Aplica o perfil atual: voz preferida, pitch e velocidade. */
    private fun applyProfile() {
        val engine = tts ?: return
        val p = profile.current()

        // Idioma
        engine.language = Locale("pt", "BR")

        // Escolha da voz
        val all = engine.voices.orEmpty().filter {
            it.locale.language.equals("pt", ignoreCase = true)
        }
        val target = when {
            p.voiceName.isNotBlank() -> all.firstOrNull { it.name == p.voiceName }
            else -> all.firstOrNull { looksMale(it.name) } ?: all.firstOrNull()
        }
        if (target != null) {
            try { engine.voice = target } catch (_: Throwable) {}
            Log.i(TAG, "Voz TTS selecionada: ${target.name}")
        }

        engine.setPitch(p.pitch)
        engine.setSpeechRate(p.speechRate)
    }

    /** Recarrega o perfil de voz (depois de upload de amostra ou troca manual). */
    fun reloadProfile() {
        applyProfile()
    }

    /** Lista de vozes pt-BR disponíveis. Útil para o seletor da UI. */
    fun listPtBrVoices(): List<Voice> {
        val engine = tts ?: return emptyList()
        return engine.voices.orEmpty().filter {
            it.locale.language.equals("pt", ignoreCase = true)
        }
    }

    fun speak(text: String, utteranceId: String = "jarvis") {
        if (!ready) {
            init { if (it) speak(text, utteranceId) }
            return
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "JarvisTts"

        /** Heurística: nome de voz tipicamente masculino. */
        fun looksMale(name: String): Boolean {
            val low = name.lowercase()
            val femaleHints = listOf("female", "fem", "mulher", "afs", "maria", "ana", "luciana", "patricia")
            if (femaleHints.any { it in low }) return false
            val maleHints = listOf("male", "masc", "homem", "ams", "bruno", "miguel", "ricardo", "joao", "antonio", "carlos")
            return maleHints.any { it in low }
        }
    }
}
