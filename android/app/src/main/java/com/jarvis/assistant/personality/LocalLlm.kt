package com.jarvis.assistant.personality

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM 100% on-device (MediaPipe LLM Inference).
 *
 * O modelo `.task` precisa estar em
 *   /sdcard/Android/data/com.jarvis.assistant/files/llm/gemma.task
 * (a tela de "Cérebro local" no app baixa automaticamente). Recomendado:
 * Falcon-RW-1B INT8 (~570 MB) ou Gemma 2 2B INT4 (~1.4 GB).
 *
 * O JARVIS NAO depende de Anthropic, Claude ou qualquer API externa para
 * conversar — tudo acontece no proprio aparelho. Esta classe usa apenas
 * a API estavel `generateResponse(prompt)` para maxima compatibilidade
 * entre versoes do MediaPipe.
 */
class LocalLlm(private val context: Context) {

    private var inference: LlmInference? = null
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    fun isModelInstalled(): Boolean = modelFile().exists() && modelFile().length() > 1_000_000

    fun modelFile(): File =
        File(context.getExternalFilesDir(null), "llm/gemma.task")

    fun init(): Boolean {
        if (inference != null) return true
        val file = modelFile()
        if (!file.exists()) {
            Log.w(TAG, "Modelo nao encontrado: ${file.absolutePath}")
            return false
        }
        return try {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(1024)
                .build()
            inference = LlmInference.createFromOptions(context, opts)
            Log.i(TAG, "LLM local pronto: ${file.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao iniciar LLM: ${t.message}")
            false
        }
    }

    suspend fun reply(systemPrompt: String, history: List<Turn>, userText: String): String =
        withContext(ioDispatcher) {
            val engine = inference ?: run {
                if (!init()) return@withContext fallback()
                inference!!
            }
            try {
                val prompt = buildPrompt(systemPrompt, history, userText)
                engine.generateResponse(prompt).trim()
            } catch (t: Throwable) {
                Log.w(TAG, "Geracao falhou: ${t.message}")
                fallback()
            }
        }

    private fun buildPrompt(system: String, history: List<Turn>, userText: String): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>system\n").append(system).append("<end_of_turn>\n")
        for (turn in history.takeLast(8)) {
            val role = if (turn.role == Role.USER) "user" else "model"
            sb.append("<start_of_turn>").append(role).append('\n')
                .append(turn.content).append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>user\n").append(userText).append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun fallback(): String =
        "Modelo offline ainda nao foi carregado, senhor. " +
                "Abra o app e toque em \"Baixar agora\" na tela inicial."

    fun close() {
        try { inference?.close() } catch (_: Throwable) {}
        inference = null
    }

    enum class Role { USER, ASSISTANT }
    data class Turn(val role: Role, val content: String)

    companion object { private const val TAG = "JarvisLlm" }
}
