package com.jarvis.assistant.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.personality.ModelDownloader
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class ModelSetupActivity : ComponentActivity() {

    private lateinit var downloader: ModelDownloader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloader = ModelDownloader(applicationContext)

        val pickModel = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                MainScope().launch { downloader.importFromUri(uri) }
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ModelScreen(
                        downloader = downloader,
                        onPickFile = { pickModel.launch("*/*") },
                        onDone = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelScreen(
    downloader: ModelDownloader,
    onPickFile: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress by downloader.progress.collectAsState()
    var url by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf(downloader.isInstalled()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Cérebro local do JARVIS", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Para conversar offline, o JARVIS precisa de um modelo `.task` " +
                    "(MediaPipe LLM Inference). Sem o modelo ele ainda escuta, fala e " +
                    "controla o telefone, mas as respostas serão limitadas a comandos " +
                    "diretos.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (installed) {
            Text("✓ Modelo instalado.", color = Color(0xFF8FE0AA))
        }

        Text("OPÇÃO 1 — Selecionar um arquivo .task que você já tem", style = MaterialTheme.typography.titleMedium)
        Text(
            "Mais simples. Baixe o `.task` no seu PC ou celular (ex.: Gemma " +
                    "3 1B INT4 do HuggingFace) e selecione aqui.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (installed) "Trocar arquivo do modelo" else "Selecionar arquivo .task") }

        Spacer(Modifier.height(8.dp))

        Text("OPÇÃO 2 — Baixar direto de uma URL", style = MaterialTheme.typography.titleMedium)
        Text(
            "Cole o link direto pro arquivo `.task`. Modelos no HuggingFace " +
                    "exigem token de acesso na URL (use \"resolve/main\" e seu token).",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL do modelo .task") },
            placeholder = { Text("https://huggingface.co/.../arquivo.task") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { scope.launch { downloader.download(url) } },
            enabled = url.startsWith("http"),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Baixar pela URL") }

        Spacer(Modifier.height(12.dp))

        when (val p = progress) {
            is ModelDownloader.Progress.Idle -> { /* nada */ }
            is ModelDownloader.Progress.Downloading -> {
                Text(
                    "Copiando: ${if (p.total > 0) "${p.percent}%  (${human(p.received)} / ${human(p.total)})" else human(p.received)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (p.total > 0) {
                    LinearProgressIndicator(
                        progress = { (p.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is ModelDownloader.Progress.Done -> {
                installed = true
                Text("✓ Modelo instalado, senhor.", color = Color(0xFF8FE0AA))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Concluir") }
            }
            is ModelDownloader.Progress.Failed -> {
                Text("Falha: ${p.message}", color = Color(0xFFE07A7A))
                Text(
                    "Dica: \"HTTP 404\" geralmente significa URL errada. \"HTTP 401/403\" " +
                            "significa que precisa de autenticação. Use a OPÇÃO 1 para " +
                            "evitar problemas de URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA0BBD9),
                )
                Button(
                    onClick = { downloader.resetProgress() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tentar novamente") }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (installed) {
            OutlinedButton(
                onClick = { downloader.delete(); installed = false; downloader.resetProgress() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apagar modelo do aparelho") }
        }
    }
}

private fun human(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
