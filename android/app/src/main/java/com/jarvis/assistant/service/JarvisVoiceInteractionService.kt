package com.jarvis.assistant.service

import android.service.voice.VoiceInteractionService

/**
 * Permite que o JARVIS seja registrado como "Assistente Digital" do
 * sistema. Apos o senhor escolher JARVIS em Configuracoes →
 * Aplicativos padrao → Assistente, o gesto do botao power, o "Hey
 * JARVIS" pelo Google e o atalho da tela de bloqueio chamam este
 * servico.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Inicia o servico em primeiro plano assim que o sistema nos
        // promover a assistente digital.
        JarvisForegroundService.start(this)
    }
}
