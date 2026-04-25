package com.jarvis.assistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.personality.ModelDownloader
import kotlinx.coroutines.launch

class ModelSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val downloader = ModelDownloader(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ModelScreen(downloader, onDone = { finish() })
                }
            }
        }
    }
}

@Composable
private fun ModelScreen(downloader: ModelDownloader, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress by downloader.progress.collectAsState()
    var url by remember { mutableStateOf(ModelDownloader.DEFAULT_URL) }
    var installed by remember { mutableStateOf(downloader.isInstalled()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Cérebro local do JARVIS", style = MaterialTheme.typography.headlineSmall)
        Text(
            "O modelo `.task` roda 100% no seu aparelho. Padrão: Falcon-RW-1B INT8 (~570 MB) " +
                    "do MediaPipe — gratuito, sem login, sem API. Para celulares mais potentes " +
                    "o senhor pode trocar por Gemma 2B ou Phi-3 mini colando outro link abaixo.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL do modelo .task") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        when (val p = progress) {
            is ModelDownloader.Progress.Idle -> {
                if (installed) {
                    Text("✓ Modelo já instalado.", color = androidx.compose.ui.graphics.Color(0xFF55C481))
                }
                Button(
                    onClick = { scope.launch { installed = downloader.download(url) } },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(if (installed) "Reinstalar" else "Baixar agora") }
            }
            is ModelDownloader.Progress.Downloading -> {
                Text(
                    "Baixando: ${p.percent}%  (${human(p.received)} / ${human(p.total)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { (p.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is ModelDownloader.Progress.Done -> {
                Text("✓ Modelo instalado, senhor.", color = androidx.compose.ui.graphics.Color(0xFF55C481))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Concluir") }
            }
            is ModelDownloader.Progress.Failed -> {
                Text(
                    "Falha: ${p.message}",
                    color = androidx.compose.ui.graphics.Color(0xFFE07A7A),
                )
                Button(
                    onClick = { scope.launch { installed = downloader.download(url) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tentar de novo") }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (installed) {
            OutlinedButton(
                onClick = { downloader.delete(); installed = false },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apagar modelo") }
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
