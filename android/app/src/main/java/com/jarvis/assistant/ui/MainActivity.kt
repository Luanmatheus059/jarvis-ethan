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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.service.JarvisForegroundService

class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            startServiceIfReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                JarvisRoot(
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenAssistantPicker = {
                        startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    },
                )
            }
        }

        ensurePermissions()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startServiceIfReady()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun startServiceIfReady() {
        JarvisForegroundService.start(this)
    }
}

@Composable
private fun JarvisRoot(
    onOpenSettings: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAssistantPicker: () -> Unit,
) {
    val state by JarvisForegroundService.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000816)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            JarvisOrb(state)

            val statusRes = when (state) {
                JarvisForegroundService.State.IDLE -> R.string.ui_status_idle
                JarvisForegroundService.State.LISTENING -> R.string.ui_status_listening
                JarvisForegroundService.State.THINKING -> R.string.ui_status_thinking
                JarvisForegroundService.State.SPEAKING -> R.string.ui_status_speaking
                JarvisForegroundService.State.DISCONNECTED -> R.string.ui_status_disconnected
            }
            Text(
                text = androidx.compose.ui.res.stringResource(statusRes),
                color = Color(0xFFCDEBFF),
                style = MaterialTheme.typography.titleMedium,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 32.dp),
            ) {
                Button(onClick = onOpenSettings) { Text("Configurações") }
                Button(onClick = onOpenAccessibility) { Text("Habilitar controle do telefone") }
                Button(onClick = onOpenAssistantPicker) { Text("Definir como Assistente") }
            }
        }
    }
}
