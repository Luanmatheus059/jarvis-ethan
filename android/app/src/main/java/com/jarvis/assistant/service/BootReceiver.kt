package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reinicia o servico do JARVIS quando o aparelho liga, para que o senhor
 * tenha o assistente disponivel desde o boot sem precisar abrir o app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                JarvisForegroundService.start(context)
            }
        }
    }
}
