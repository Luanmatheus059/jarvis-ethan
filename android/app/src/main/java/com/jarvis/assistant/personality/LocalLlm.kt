package com.jarvis.assistant.personality

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM 100% on-device (MediaPipe LLM Inference + Gemma).
 *
 * O modelo `.task` precisa estar em /sdcard/Android/data/com.jarvis.assistant/files/llm/gemma.task
 * (ou outro caminho que o senhor configurar). Recomendado: Gemma 2 2B IT
 * quantizado em INT4, ~1.4 GB.
 *
 * O JARVIS NAO depende de Anthropic, Claude ou qualquer API externa para
 * conversar — tudo acontece no proprio aparelho.
 */
class LocalLlm(private val context: Context) {

    private var inference: LlmInference? = null
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    fun isModelInstalled(): Boolean = modelFile().exists()

    private fun modelFile(): File =
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
                .setMaxTopK(40)
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
            val engine = inference ?: run { if (!init()) return@withContext fallback(userText); inference!! }
            try {
                val sessionOpts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(0.6f)
                    .setTopK(40)
                    .build()
                val session = LlmInferenceSession.createFromOptions(engine, sessionOpts)
                session.use {
                    val prompt = buildPrompt(systemPrompt, history, userText)
                    it.addQueryChunk(prompt)
                    it.generateResponse().trim()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Geracao falhou: ${t.message}")
                fallback(userText)
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

    private fun fallback(userText: String): String {
        // Resposta de cortesia caso o modelo ainda nao esteja instalado.
        return "Modelo offline ainda nao foi carregado, senhor. " +
                "Coloque o arquivo Gemma .task na pasta do app e me chame de novo."
    }

    fun close() {
        try { inference?.close() } catch (_: Throwable) {}
        inference = null
    }

    enum class Role { USER, ASSISTANT }
    data class Turn(val role: Role, val content: String)

    companion object { private const val TAG = "JarvisLlm" }
}
