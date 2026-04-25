package com.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.audio.VoiceProfile
import com.jarvis.assistant.personality.LocalLlm
import com.jarvis.assistant.service.JarvisForegroundService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                JarvisRoot(
                    onAskPermissions = ::ensurePermissions,
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenAssistantPicker = {
                        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    },
                    onOpenBatterySettings = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                    },
                    onOpenModel = { startActivity(Intent(this, ModelSetupActivity::class.java)) },
                    onOpenVoice = { startActivity(Intent(this, VoiceSetupActivity::class.java)) },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
        // Pede as permissoes essenciais imediatamente — assim, na proxima
        // vez que o senhor abrir, ja podemos ligar o servico sozinhos.
        ensurePermissions()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions.launch(missing.toTypedArray())
        }
    }
}

@Composable
private fun JarvisRoot(
    onAskPermissions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAssistantPicker: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state by JarvisForegroundService.state.collectAsState()

    // Sondamos o status do servico a cada segundo — assim a UI mostra
    // imediatamente "rodando em segundo plano" quando o senhor reabre o app.
    var running by remember { mutableStateOf(JarvisForegroundService.isRunning()) }
    var modelInstalled by remember { mutableStateOf(LocalLlm(ctx).isModelInstalled()) }
    var voiceInstalled by remember { mutableStateOf(VoiceProfile(ctx).hasCustomVoice()) }
    var accessibilityOn by remember { mutableStateOf(JarvisAccessibilityService.isEnabled()) }

    LaunchedEffect(Unit) {
        while (true) {
            running = JarvisForegroundService.isRunning()
            modelInstalled = LocalLlm(ctx).isModelInstalled()
            voiceInstalled = VoiceProfile(ctx).hasCustomVoice()
            accessibilityOn = JarvisAccessibilityService.isEnabled()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000816))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
            JarvisOrb(state)

            Text(
                text = stringResource(statusRes(state)),
                color = Color(0xFFCDEBFF),
                style = MaterialTheme.typography.titleMedium,
            )

            if (running) {
                Text(
                    "Rodando em segundo plano, senhor.",
                    color = Color(0xFF8FE0AA),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "Inativo. Toque em \"Ativar JARVIS\" para começar.",
                    color = Color(0xFFFFB0B0),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (running) {
                Button(
                    onClick = { JarvisForegroundService.stop(ctx) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB02A2A)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Parar JARVIS") }
            } else {
                Button(
                    onClick = {
                        onAskPermissions()
                        JarvisForegroundService.start(ctx)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B7FF)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Ativar JARVIS") }
            }

            Spacer(Modifier.height(8.dp))

            SetupCard(
                title = "Cérebro local (LLM)",
                description = if (modelInstalled)
                    "Modelo instalado. Pronto para conversar offline."
                else
                    "Modelo ainda não baixado. Toque para instalar (~570 MB, gratuito).",
                actionLabel = if (modelInstalled) "Trocar / reinstalar" else "Baixar agora",
                onAction = onOpenModel,
                ok = modelInstalled,
            )

            SetupCard(
                title = "Voz personalizada",
                description = if (voiceInstalled)
                    "Amostra de voz importada. Estou imitando o tom dela."
                else
                    "Envie um áudio para eu calibrar minha voz para imitá-lo.",
                actionLabel = if (voiceInstalled) "Trocar amostra" else "Enviar áudio",
                onAction = onOpenVoice,
                ok = voiceInstalled,
            )

            SetupCard(
                title = "Controle do telefone (Acessibilidade)",
                description = if (accessibilityOn)
                    "Habilitado — posso abrir apps, tocar em botões, ler a tela."
                else
                    "Desabilitado — sem isto não consigo controlar o aparelho.",
                actionLabel = "Abrir Acessibilidade",
                onAction = onOpenAccessibility,
                ok = accessibilityOn,
            )

            SetupCard(
                title = "Definir como Assistente",
                description = "Permite chamar JARVIS pelo gesto do botão power / atalho do sistema.",
                actionLabel = "Abrir configuração",
                onAction = onOpenAssistantPicker,
                ok = false,
            )

            SetupCard(
                title = "Ignorar otimização de bateria",
                description = "Sem isto, o Android pode matar o serviço em segundo plano.",
                actionLabel = "Abrir bateria",
                onAction = onOpenBatterySettings,
                ok = false,
            )

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Mais configurações") }
    }
}

@Composable
private fun SetupCard(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    ok: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) Color(0xFF0F2A24) else Color(0xFF14213D),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (ok) "✓ " else "○ ") + title,
                style = MaterialTheme.typography.titleMedium,
                color = if (ok) Color(0xFFB6F0C7) else Color(0xFFCDEBFF),
            )
            Text(description, color = Color(0xFFA0BBD9))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}

private fun statusRes(state: JarvisForegroundService.State): Int = when (state) {
    JarvisForegroundService.State.IDLE -> R.string.ui_status_idle
    JarvisForegroundService.State.LISTENING -> R.string.ui_status_listening
    JarvisForegroundService.State.THINKING -> R.string.ui_status_thinking
    JarvisForegroundService.State.SPEAKING -> R.string.ui_status_speaking
    JarvisForegroundService.State.DISCONNECTED -> R.string.ui_status_disconnected
}
