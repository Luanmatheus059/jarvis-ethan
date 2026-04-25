package com.jarvis.assistant.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log

/**
 * Roteador de comandos do JARVIS para o telefone.
 *
 * O servidor JARVIS pode incluir tags em sua resposta (por exemplo:
 *   [PHONE:OPEN_APP] com.whatsapp
 *   [PHONE:CALL] +5511999999999
 *   [PHONE:SET_VOLUME] 80
 * ). O codigo da UI extrai a tag e chama o metodo correspondente aqui.
 *
 * Acoes que precisam de toque/swipe na tela passam pelo
 * JarvisAccessibilityService; intents do sistema sao disparadas direto.
 */
object PhoneActionRouter {

    private const val TAG = "JarvisPhone"

    fun openApp(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(TAG, "Pacote nao instalado: $packageName")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun call(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: SecurityException) {
            // Cai pra discador se nao tivermos CALL_PHONE concedida.
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(dial)
        }
    }

    fun sendSms(number: String, message: String) {
        val sm = SmsManager.getDefault()
        sm.sendTextMessage(number, null, message, null, null)
    }

    fun setVolume(context: Context, percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent / 100).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    fun setBrightness(context: Context, percent: Int) {
        if (!Settings.System.canWrite(context)) {
            // Manda o senhor as configuracoes pra liberar a permissao.
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        val value = (percent * 255 / 100).coerceIn(0, 255)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            value,
        )
    }

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = "JARVIS") {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openCamera(context: Context) {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Acoes que dependem do servico de Acessibilidade
    // ─────────────────────────────────────────────────────────────────────────

    fun goHome() = JarvisAccessibilityService.get()?.goHome() ?: false
    fun goBack() = JarvisAccessibilityService.get()?.goBack() ?: false
    fun openRecents() = JarvisAccessibilityService.get()?.openRecents() ?: false
    fun openNotifications() = JarvisAccessibilityService.get()?.openNotifications() ?: false
    fun lockScreen() = JarvisAccessibilityService.get()?.lockScreen()
    fun takeScreenshot() = JarvisAccessibilityService.get()?.takeScreenshot()
    fun clickByText(text: String) = JarvisAccessibilityService.get()?.clickByText(text) ?: false
    fun describeScreen() = JarvisAccessibilityService.get()?.describeScreen().orEmpty()

    // ─────────────────────────────────────────────────────────────────────────
    // Parser simples: extrai e executa todas as tags [PHONE:...] de um texto
    // ─────────────────────────────────────────────────────────────────────────

    private val tagRegex = Regex("""\[PHONE:(\w+)\]\s*([^\[\n]*)""", RegexOption.IGNORE_CASE)

    fun executeAll(context: Context, response: String) {
        for (match in tagRegex.findAll(response)) {
            val verb = match.groupValues[1].uppercase()
            val arg = match.groupValues[2].trim()
            try {
                when (verb) {
                    "OPEN_APP" -> openApp(context, arg)
                    "OPEN_URL" -> openUrl(context, arg)
                    "CALL" -> call(context, arg)
                    "SMS" -> {
                        val (number, msg) = arg.split("|||", limit = 2).map { it.trim() }
                        sendSms(number, msg)
                    }
                    "VOLUME" -> setVolume(context, arg.toIntOrNull() ?: 50)
                    "BRIGHTNESS" -> setBrightness(context, arg.toIntOrNull() ?: 50)
                    "ALARM" -> {
                        val (hm, label) = arg.split("|||", limit = 2)
                            .let { it.first() to it.getOrElse(1) { "JARVIS" } }
                        val (h, m) = hm.split(":").map { it.trim().toInt() }
                        setAlarm(context, h, m, label)
                    }
                    "CAMERA" -> openCamera(context)
                    "HOME" -> goHome()
                    "BACK" -> goBack()
                    "RECENTS" -> openRecents()
                    "NOTIFICATIONS" -> openNotifications()
                    "LOCK" -> lockScreen()
                    "SCREENSHOT" -> takeScreenshot()
                    "TAP_TEXT" -> clickByText(arg)
                    else -> Log.w(TAG, "Tag desconhecida: $verb")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Falha executando $verb $arg: ${t.message}")
            }
        }
    }
}
