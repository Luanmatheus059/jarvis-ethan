package com.jarvis.assistant.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.audio.LocalTextToSpeech
import com.jarvis.assistant.audio.VoiceProfile
import com.jarvis.assistant.service.JarvisForegroundService

class VoiceSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val voice = VoiceProfile(applicationContext)
        val tts = LocalTextToSpeech(applicationContext).also { it.init() }

        val pickAudio = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val profile = voice.importSample(uri)
                tts.reloadProfile()
                JarvisForegroundService.reloadVoiceProfile()
                tts.speak("Voz calibrada, senhor. Pitch ${"%.2f".format(profile.pitch)}, velocidade ${"%.2f".format(profile.speechRate)}.")
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VoiceScreen(
                        voice = voice,
                        tts = tts,
                        onPickAudio = { pickAudio.launch(arrayOf("audio/*")) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceScreen(
    voice: VoiceProfile,
    tts: LocalTextToSpeech,
    onPickAudio: () -> Unit,
) {
    var profile by remember { mutableStateOf(voice.current()) }
    var hasSample by remember { mutableStateOf(voice.hasCustomVoice()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Voz personalizada", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Envie um áudio (mp3, wav ou m4a) com a voz que o senhor quer ouvir. " +
                    "O JARVIS vai usar essa amostra para calibrar pitch e velocidade do " +
                    "TTS local — não é clonagem neural completa, mas dá um timbre " +
                    "muito mais próximo. Tudo offline e gratuito.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = onPickAudio,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (hasSample) "Trocar amostra" else "Escolher arquivo de áudio") }

        if (hasSample) {
            Text("Amostra atual: ${profile.sampleDurationMs} ms.")
        }

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

        OutlinedButton(
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
