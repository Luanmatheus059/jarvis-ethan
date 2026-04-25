package com.jarvis.assistant.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Sessao iniciada quando o sistema invoca o JARVIS como assistente
 * (gesto do botao power, atalho "Hey Google" redirecionado, etc.).
 *
 * Em vez de mostrar a UI propria, simplesmente acordamos o servico em
 * primeiro plano e disparamos uma sessao de escuta — todo o trabalho
 * acontece la.
 */
class JarvisVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return object : VoiceInteractionSession(this) {
            override fun onShow(args: Bundle?, showFlags: Int) {
                super.onShow(args, showFlags)
                JarvisForegroundService.start(context)
                JarvisForegroundService.triggerListen(context)
                hide()
            }
        }
    }
}
