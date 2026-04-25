package com.jarvis.assistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.JarvisSettings
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = JarvisSettings(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(settings)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: JarvisSettings) {
    val scope = rememberCoroutineScope()

    val lang by settings.recognitionLanguage.collectAsState(initial = "pt-BR")
    val wake by settings.wakeWordEnabled.collectAsState(initial = true)
    val bg by settings.backgroundEnabled.collectAsState(initial = true)

    var languageInput by remember { mutableStateOf(lang) }
    LaunchedEffect(lang) { languageInput = lang }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Configurações do JARVIS", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tudo roda 100% no seu aparelho. Apenas a busca de notícias / GitHub / arXiv usa internet quando ativada.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = languageInput,
            onValueChange = {
                languageInput = it
                scope.launch { settings.setRecognitionLanguage(it) }
            },
            label = { Text("Idioma do reconhecimento (ex.: pt-BR)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row("Palavra de ativação \"JARVIS\" (mãos livres)", wake) {
            scope.launch { settings.setWakeWord(it) }
        }
        Row("Manter ativo em segundo plano", bg) {
            scope.launch { settings.setBackground(it) }
        }
    }
}

@Composable
private fun Row(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
