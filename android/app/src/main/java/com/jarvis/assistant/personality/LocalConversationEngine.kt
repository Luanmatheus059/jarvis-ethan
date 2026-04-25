package com.jarvis.assistant.personality

import android.content.Context
import android.util.Log
import com.jarvis.assistant.accessibility.PhoneActionRouter
import com.jarvis.assistant.audio.LocalTextToSpeech
import com.jarvis.assistant.learning.RealtimeLearner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Motor de conversa 100% local:
 *
 *   STT (Android) -> LocalConversationEngine.handle(text)
 *      -> RealtimeLearner (se a frase pedir pesquisa)
 *      -> LocalLlm (Gemma on-device) gera resposta com personalidade JARVIS
 *      -> Extrai tags [PHONE:...] e dispara via PhoneActionRouter
 *      -> Lê o restante em voz alta com LocalTextToSpeech (pt-BR)
 *
 * Sem Anthropic, sem Fish Audio, sem servidor externo. Tudo dentro do
 * aparelho. Apenas as buscas em tempo real (GitHub/arXiv/Noticias)
 * usam a internet — e isso e proposital, conforme pedido do senhor.
 */
class LocalConversationEngine(
    private val context: Context,
    private val tts: LocalTextToSpeech,
) {
    private val llm = LocalLlm(context)
    private val learner = RealtimeLearner(context)
    private val history = ArrayDeque<LocalLlm.Turn>(16)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    enum class State { IDLE, THINKING, SPEAKING }

    fun warmUp() {
        scope.launch(Dispatchers.IO) { llm.init() }
    }

    fun close() {
        llm.close()
        scope.cancel()
    }

    fun handle(userText: String) {
        scope.launch {
            _state.value = State.THINKING
            try {
                val researchHint = if (mentionsResearch(userText)) {
                    val snips = learner.research(userText)
                    learner.summarizeForPrompt(snips)
                } else ""

                val system = buildString {
                    append(JarvisPersona.SYSTEM_PROMPT_PT_BR)
                    if (researchHint.isNotBlank()) {
                        append("\n\n").append(researchHint)
                    }
                }

                history.addLast(LocalLlm.Turn(LocalLlm.Role.USER, userText))
                while (history.size > 12) history.removeFirst()

                val raw = withContext(Dispatchers.IO) {
                    llm.reply(system, history.toList(), userText)
                }
                val reply = raw.ifBlank { "Receio nao ter resposta agora, senhor." }
                history.addLast(LocalLlm.Turn(LocalLlm.Role.ASSISTANT, reply))

                // Executa qualquer acao no telefone solicitada pelo modelo.
                PhoneActionRouter.executeAll(context, reply)

                // Trata pedido de pesquisa explicito ("[LEARN] ..."): faz e
                // re-fala a frase principal.
                handleLearnTags(reply)?.let { followUp ->
                    history.addLast(LocalLlm.Turn(LocalLlm.Role.ASSISTANT, followUp))
                    speak(followUp)
                }

                speak(stripTags(reply))
            } catch (t: Throwable) {
                Log.w(TAG, "Conversation failed: ${t.message}")
                speak("Tivemos um pequeno contratempo, senhor.")
            } finally {
                _state.value = State.IDLE
            }
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        _state.value = State.SPEAKING
        tts.speak(text)
    }

    private fun mentionsResearch(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "noticia", "notícia", "novidade", "ultim", "última", "ultimo",
            "github", "repositorio", "repositório", "arxiv", "paper",
            "pesquisa", "pesquise", "como funciona", "explica",
            "atualiza", "informação sobre", "informacao sobre",
        ).any { t.contains(it) }
    }

    private suspend fun handleLearnTags(text: String): String? {
        val match = Regex("""\[LEARN\]\s*([^\n\[]+)""").find(text) ?: return null
        val query = match.groupValues[1].trim()
        val snippets = learner.research(query)
        if (snippets.isEmpty()) return "Nao encontrei nada novo sobre isso, senhor."
        val first = snippets.first()
        return "Encontrei algo, senhor: ${first.title}. ${first.summary.take(180)}"
    }

    private fun stripTags(text: String): String {
        return text
            .replace(Regex("""\[PHONE:[^\]]+\][^\n\[]*"""), "")
            .replace(Regex("""\[LEARN\][^\n\[]*"""), "")
            .trim()
    }

    companion object { private const val TAG = "JarvisLocalEngine" }
}
