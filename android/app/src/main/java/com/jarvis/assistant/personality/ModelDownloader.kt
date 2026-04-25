package com.jarvis.assistant.personality

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Faz download do modelo LLM `.task` direto pra pasta do app.
 *
 * O modelo padrao e o **Falcon-RW-1B INT8** (`.task`), que esta disponivel
 * publicamente no MediaPipe LiteRT samples — gratuito, sem login, sem
 * termos de aceite. ~570 MB, roda confortavel em qualquer celular Android
 * de 4 GB+ de RAM.
 *
 * O senhor pode trocar a URL na tela de download por outro modelo que
 * preferir (Gemma 2B, Phi-3 mini, etc.) — basta colar o link direto pro
 * arquivo `.task` e o downloader cuida do resto.
 */
class ModelDownloader(private val context: Context) {

    sealed class Progress {
        data object Idle : Progress()
        data class Downloading(val received: Long, val total: Long) : Progress() {
            val percent: Int = if (total > 0) ((received * 100L) / total).toInt() else 0
        }
        data object Done : Progress()
        data class Failed(val message: String) : Progress()
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val http = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.MILLISECONDS) // sem timeout — pode demorar
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun targetFile(): File =
        File(context.getExternalFilesDir(null), "llm/gemma.task")

    fun isInstalled(): Boolean = targetFile().exists() && targetFile().length() > 1_000_000

    suspend fun download(url: String = DEFAULT_URL): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = targetFile()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")

            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _progress.value = Progress.Failed("HTTP ${resp.code}")
                    return@withContext false
                }
                val total = resp.body?.contentLength() ?: -1L
                val source = resp.body?.byteStream() ?: run {
                    _progress.value = Progress.Failed("Resposta vazia")
                    return@withContext false
                }
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        received += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            _progress.value = Progress.Downloading(received, total)
                            lastUpdate = now
                        }
                    }
                }
            }

            // Renomeia tmp -> destino final atomicamente
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                _progress.value = Progress.Failed("Não consegui mover arquivo final")
                return@withContext false
            }
            _progress.value = Progress.Done
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Download falhou: ${t.message}")
            _progress.value = Progress.Failed(t.message ?: "erro desconhecido")
            false
        }
    }

    fun delete(): Boolean {
        val f = targetFile()
        return f.exists() && f.delete()
    }

    companion object {
        private const val TAG = "JarvisDownload"

        /**
         * URL pública gratuita do MediaPipe LiteRT — modelo `.task` que NAO
         * exige login nem aceitação de termos. Caso o link mude no futuro,
         * o senhor pode colar outro na tela de configuração.
         *
         * Falcon-RW-1B INT8 (~570 MB) — performance leve, suficiente para
         * conversa em pt-BR.
         */
        const val DEFAULT_URL =
            "https://storage.googleapis.com/mediapipe-models/llm_inference/falcon_rw_1b/int8/1/falcon_rw_1b_int8.task"
    }
}
