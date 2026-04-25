package com.jarvis.assistant.ui

import android.net.Uri
import android.os.Bundle
import android.speech.tts.Voice
import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.audio.LocalTextToSpeech
import com.jarvis.assistant.audio.VoiceProfile
import com.jarvis.assistant.service.JarvisForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceSetupActivity : ComponentActivity() {

    private lateinit var voice: VoiceProfile
    private lateinit var tts: LocalTextToSpeech

    /** Estado visível na UI — feedback do upload do áudio. */
    private val statusFlow = kotlinx.coroutines.flow.MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = VoiceProfile(applicationContext)
        tts = LocalTextToSpeech(applicationContext).also { it.init() }

        // Picker que aceita QUALQUER tipo de arquivo — alguns sistemas
        // (MIUI/OriginOS) escondem áudios quando o filtro é "audio/*".
        // Validamos depois pela extensão e tentando ler com MediaExtractor.
        val pickAny = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? -> handlePickedUri(uri) }

        val pickAudioOnly = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? -> handlePickedUri(uri) }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceScreen(
                        voice = voice,
                        tts = tts,
                        onPickAudio = { pickAudioOnly.launch("audio/*") },
                        onPickAny = { pickAny.launch("*/*") },
                        statusFlow = statusFlow,
                    )
                }
            }
        }
    }

    private fun handlePickedUri(uri: Uri?) {
        if (uri == null) {
            statusFlow.value = "Nenhum arquivo selecionado."
            return
        }
        statusFlow.value = "Importando arquivo..."
        MainScope().launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { voice.importSample(uri) }
            }
            if (result.isFailure) {
                val msg = result.exceptionOrNull()?.message ?: "erro desconhecido"
                statusFlow.value = "✗ Falha ao importar: $msg"
                Toast.makeText(
                    this@VoiceSetupActivity,
                    "Não consegui importar o áudio: $msg",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val profile = result.getOrThrow()
            tts.reloadProfile()
            JarvisForegroundService.reloadVoiceProfile()
            statusFlow.value = "✓ Importado (${profile.sampleDurationMs} ms). Pitch %.2f, velocidade %.2f.".format(profile.pitch, profile.speechRate)
            tts.speak(
                "Voz calibrada com a sua amostra, senhor. " +
                        "Pitch ${"%.2f".format(profile.pitch)}, " +
                        "velocidade ${"%.2f".format(profile.speechRate)}."
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
    }
}

@Composable
private fun VoiceScreen(
    voice: VoiceProfile,
    tts: LocalTextToSpeech,
    onPickAudio: () -> Unit,
    onPickAny: () -> Unit,
    statusFlow: kotlinx.coroutines.flow.StateFlow<String>,
) {
    var profile by remember { mutableStateOf(voice.current()) }
    var hasSample by remember { mutableStateOf(voice.hasCustomVoice()) }
    var voices by remember { mutableStateOf(emptyList<Voice>()) }
    var selectedVoice by remember { mutableStateOf<String?>(profile.voiceName.takeIf { it.isNotBlank() }) }
    val status by statusFlow.collectAsState()

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        voices = tts.listPtBrVoices()
    }

    LaunchedEffect(status) {
        // recarrega ao receber sinal de "✓ Importado"
        if (status.startsWith("✓")) {
            profile = voice.current()
            hasSample = voice.hasCustomVoice()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Voz do JARVIS", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Clonagem neural completa offline ainda exige modelos de vários GB " +
                    "que não rodam bem em celular comum. O JARVIS faz o melhor possível " +
                    "100% gratuito: escolhe a voz pt-BR mais grave (masculina) instalada " +
                    "no seu aparelho e ajusta pitch + velocidade pra imitar a sua amostra.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFA0BBD9),
        )

        Text("1. Voz pt-BR instalada no aparelho", style = MaterialTheme.typography.titleMedium)
        if (voices.isEmpty()) {
            Text(
                "Nenhuma voz pt-BR detectada ainda — abrir Configurações → Acessibilidade → " +
                        "Saída de texto pra fala e instalar dados em Português.",
                color = Color(0xFFE0BB7A),
            )
        } else {
            voices.forEach { v ->
                val isMale = LocalTextToSpeech.looksMale(v.name)
                val isSelected = selectedVoice == v.name
                OutlinedButton(
                    onClick = {
                        selectedVoice = v.name
                        profile = profile.copy(voiceName = v.name)
                        voice.save(profile)
                        tts.reloadProfile()
                        JarvisForegroundService.reloadVoiceProfile()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = (if (isSelected) "✓ " else "  ") + v.name +
                                if (isMale) "  (masculina)" else "",
                        color = if (isSelected) Color(0xFF8FE0AA)
                                else if (isMale) Color(0xFFCDEBFF) else Color(0xFFA0BBD9),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("2. Amostra de áudio (opcional, calibra pitch automaticamente)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Se o picker abaixo não mostrar seus áudios, use \"Selecionar qualquer arquivo\" " +
                    "— alguns lançadores escondem mp3/wav quando o filtro é estrito.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFA0BBD9),
        )
        Button(
            onClick = onPickAudio,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(if (hasSample) "Trocar amostra (apenas áudios)" else "Selecionar áudio (apenas áudios)") }
        OutlinedButton(
            onClick = onPickAny,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Selecionar qualquer arquivo") }

        if (status.isNotBlank()) {
            Text(
                status,
                color = when {
                    status.startsWith("✓") -> Color(0xFF8FE0AA)
                    status.startsWith("✗") -> Color(0xFFE07A7A)
                    else -> Color(0xFFE0BB7A)
                },
            )
        }

        if (hasSample) {
            Text("Amostra atual: ${profile.sampleDurationMs} ms.", color = Color(0xFFA0BBD9))
        }

        Spacer(Modifier.height(8.dp))
        Text("3. Ajuste fino", style = MaterialTheme.typography.titleMedium)
        Text("Pitch (grave ↔ agudo): ${"%.2f".format(profile.pitch)}")
        Slider(
            value = profile.pitch,
            onValueChange = {
                profile = profile.copy(pitch = it)
                voice.save(profile)
                tts.reloadProfile()
                JarvisForegroundService.reloadVoiceProfile()
            },
            valueRange = 0.5f..1.5f,
        )
        Text("Velocidade da fala: ${"%.2f".format(profile.speechRate)}")
        Slider(
            value = profile.speechRate,
            onValueChange = {
                profile = profile.copy(speechRate = it)
                voice.save(profile)
                tts.reloadProfile()
                JarvisForegroundService.reloadVoiceProfile()
            },
            valueRange = 0.5f..1.6f,
        )

        Button(
            onClick = { tts.speak("Bom dia, senhor. Espero ter sido bem calibrado.") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Testar voz") }

        if (hasSample) {
            OutlinedButton(
                onClick = {
                    voice.clear()
                    profile = voice.current()
                    hasSample = false
                    tts.reloadProfile()
                    JarvisForegroundService.reloadVoiceProfile()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apagar amostra") }
        }
    }
}
