package com.jarvis.assistant.personality

import android.content.Context
import android.net.Uri
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
 * Instala o modelo .task usado pelo MediaPipe LLM Inference.
 *
 * Existem dois caminhos:
 *  1. **Importar arquivo do aparelho** (importFromUri) — o senhor baixou o
 *     `.task` no PC ou no celular e seleciona via picker. Funciona 100%
 *     offline, sem login, sem URL.
 *  2. **Baixar de uma URL** (download) — para servidores publicos. A maioria
 *     dos modelos `.task` decentes esta no HuggingFace e exige um token de
 *     acesso (gratuito, mas precisa cadastro). Por isso o caminho 1 e o
 *     padrao na UI.
 *
 * Em ambos os casos, o arquivo final fica em
 *   <externalFilesDir>/llm/gemma.task
 * e e detectado automaticamente pelo LocalLlm.
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
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun targetFile(): File =
        File(context.getExternalFilesDir(null), "llm/gemma.task")

    fun isInstalled(): Boolean = targetFile().exists() && targetFile().length() > 1_000_000

    /** Importa um arquivo .task que o senhor selecionou via SAF. */
    suspend fun importFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            _progress.value = Progress.Downloading(0, -1)
            val target = targetFile()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            val total = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L

            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    _progress.value = Progress.Failed("Não consegui abrir o arquivo escolhido.")
                    return@withContext false
                }
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        received += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250) {
                            _progress.value = Progress.Downloading(received, total)
                            lastUpdate = now
                        }
                    }
                }
            }
            // Validação básica — arquivos `.task` válidos têm dezenas/centenas
            // de MB e começam com bytes binários (geralmente "TFL" ou similar).
            if (tmp.length() < 50_000_000L) {
                val size = human(tmp.length())
                tmp.delete()
                _progress.value = Progress.Failed(
                    "Arquivo selecionado tem apenas $size — pequeno demais " +
                            "para um modelo LLM. Tem certeza de que é um `.task`?"
                )
                return@withContext false
            }
            val firstByte = tmp.inputStream().use { it.read() }
            if (firstByte == '<'.code) {
                tmp.delete()
                _progress.value = Progress.Failed(
                    "O arquivo começa com HTML — provavelmente é uma página salva, não um modelo."
                )
                return@withContext false
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                _progress.value = Progress.Failed("Não consegui mover o arquivo final.")
                return@withContext false
            }
            _progress.value = Progress.Done
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Import falhou: ${t.message}")
            _progress.value = Progress.Failed(t.message ?: "erro desconhecido")
            false
        }
    }

    /**
     * Baixa de uma URL HTTP/HTTPS aberta. Valida que o resultado realmente
     * parece um modelo `.task` — recusa páginas HTML (HuggingFace 401, blob
     * preview), arquivos pequenos demais e respostas com Content-Type texto.
     */
    suspend fun download(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = targetFile()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")

            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _progress.value = Progress.Failed(
                        "HTTP ${resp.code} — URL provavelmente errada ou pede login. " +
                                "Use a Opção 1 (selecionar arquivo)."
                    )
                    return@withContext false
                }
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("text/") || ct.contains("html") || ct.contains("json")) {
                    _progress.value = Progress.Failed(
                        "URL retornou \"$ct\" em vez de um arquivo binário. " +
                                "Use o link \"resolve/main\" do HuggingFace, não o \"blob/main\"."
                    )
                    return@withContext false
                }
                val total = resp.body?.contentLength() ?: -1L
                val source = resp.body?.byteStream() ?: run {
                    _progress.value = Progress.Failed("Resposta vazia.")
                    return@withContext false
                }

                var firstBytes = ByteArray(0)
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val read = source.read(buf)
                        if (read <= 0) break
                        // Captura os primeiros bytes pra detectar HTML
                        if (firstBytes.isEmpty() && read > 0) {
                            val len = minOf(read, 32)
                            firstBytes = buf.copyOfRange(0, len)
                            // Se começa com "<" provavelmente é HTML/XML.
                            if (firstBytes.isNotEmpty() && firstBytes[0] == '<'.code.toByte()) {
                                tmp.delete()
                                _progress.value = Progress.Failed(
                                    "Resposta começa com HTML/XML — não é um modelo binário. " +
                                            "Verifique a URL."
                                )
                                return@withContext false
                            }
                        }
                        out.write(buf, 0, read)
                        received += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 400) {
                            _progress.value = Progress.Downloading(received, total)
                            lastUpdate = now
                        }
                    }
                }
            }

            // Validação de tamanho — modelos LLM .task vão de ~500MB pra cima.
            // Aceitar 50MB+ como limiar seguro contra páginas de erro.
            if (tmp.length() < 50_000_000L) {
                tmp.delete()
                _progress.value = Progress.Failed(
                    "Arquivo baixado tem apenas ${human(tmp.length())} — " +
                            "muito pequeno para um modelo LLM. URL provavelmente está errada."
                )
                return@withContext false
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                _progress.value = Progress.Failed("Não consegui mover o arquivo final.")
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

    private fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return "%.1f %s".format(v, units[i])
    }

    fun delete(): Boolean {
        val f = targetFile()
        return f.exists() && f.delete()
    }

    fun resetProgress() { _progress.value = Progress.Idle }

    companion object {
        private const val TAG = "JarvisDownload"
    }
}
