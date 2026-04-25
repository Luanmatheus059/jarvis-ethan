package com.jarvis.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.JarvisSettings
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.LocalTextToSpeech
import com.jarvis.assistant.audio.SpeechToText
import com.jarvis.assistant.learning.NewsRefreshWorker
import com.jarvis.assistant.personality.LocalConversationEngine
import com.jarvis.assistant.ui.MainActivity
import com.jarvis.assistant.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Servico em primeiro plano. Mantem o JARVIS ouvindo o senhor o tempo
 * todo — mesmo com a tela apagada — e processa tudo localmente.
 *
 * Pipeline:
 *   WakeWordDetector ("JARVIS")
 *      -> SpeechToText (pt-BR, Android)
 *         -> LocalConversationEngine (Gemma on-device + RealtimeLearner)
 *            -> LocalTextToSpeech (Android pt-BR)
 *
 * Nada disso depende de servidor: o senhor pode estar offline e ainda
 * conversar com ele. As buscas em tempo real (GitHub/arXiv/Noticias)
 * usam internet quando disponivel.
 */
class JarvisForegroundService : Service() {

    enum class State { IDLE, LISTENING, THINKING, SPEAKING, DISCONNECTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var settings: JarvisSettings
    internal lateinit var tts: LocalTextToSpeech
    private lateinit var engine: LocalConversationEngine
    private var stt: SpeechToText? = null
    private var wake: WakeWordDetector? = null
    private var activeListening = false

    override fun onCreate() {
        super.onCreate()
        settings = JarvisSettings(this)
        tts = LocalTextToSpeech(this).also { it.init() }
        engine = LocalConversationEngine(this, tts).also { it.warmUp() }
        instance = this

        startInForeground()
        acquireWakeLock()
        NewsRefreshWorker.schedule(applicationContext)

        scope.launch { startWakeWord() }
        observeEngineState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_LISTEN -> startActiveListening()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stt?.stop()
        wake?.stop()
        tts.shutdown()
        engine.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline de voz
    // ─────────────────────────────────────────────────────────────────────────

    private fun startInForeground() {
        val notif = buildNotification(getString(R.string.notification_listening_text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, JarvisApplication.CHANNEL_ID_FOREGROUND)
            .setSmallIcon(R.drawable.ic_jarvis_orb)
            .setContentTitle(getString(R.string.notification_listening_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::ListeningLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private suspend fun startWakeWord() {
        val enabled = settings.wakeWordEnabled.first()
        if (!enabled) {
            startActiveListening()
            return
        }
        val w = WakeWordDetector(this) {
            // O senhor disse "JARVIS" — ativamos a sessao ativa.
            scope.launch { startActiveListening() }
        }
        w.start()
        wake = w
    }

    private fun startActiveListening() {
        if (activeListening) return
        activeListening = true
        _state.value = State.LISTENING

        val rec = SpeechToText(
            context = this,
            language = "pt-BR",
            onPartial = { /* nada — estado ja indica listening */ },
            onFinal = { transcript ->
                Log.i(TAG, "Senhor disse: $transcript")
                _state.value = State.THINKING
                engine.handle(transcript)
            },
        )
        rec.startContinuous()
        stt = rec
    }

    private fun observeEngineState() {
        scope.launch {
            engine.state.collect { es ->
                _state.value = when (es) {
                    LocalConversationEngine.State.IDLE -> State.LISTENING
                    LocalConversationEngine.State.THINKING -> State.THINKING
                    LocalConversationEngine.State.SPEAKING -> State.SPEAKING
                }
            }
        }
    }

    companion object {
        private const val TAG = "JarvisFg"
        const val NOTIFICATION_ID = 0xC0DE
        const val ACTION_TRIGGER_LISTEN = "com.jarvis.assistant.LISTEN"
        const val ACTION_STOP = "com.jarvis.assistant.STOP"

        @Volatile private var instance: JarvisForegroundService? = null

        private val _state = MutableStateFlow(State.IDLE)
        val state: StateFlow<State> = _state

        /** True enquanto o servico em primeiro plano esta vivo (escutando). */
        fun isRunning(): Boolean = instance != null

        fun reloadVoiceProfile() {
            instance?.tts?.reloadProfile()
        }

        fun start(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun triggerListen(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java)
                .setAction(ACTION_TRIGGER_LISTEN)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisForegroundService::class.java))
        }
    }
}
