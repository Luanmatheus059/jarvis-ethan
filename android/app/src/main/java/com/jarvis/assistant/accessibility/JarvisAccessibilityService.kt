package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Servico de Acessibilidade — e por aqui que o JARVIS realmente toma
 * controle do telefone do senhor:
 *
 *  - performGlobalAction(): abrir notificacoes, voltar, home,
 *    apps recentes, screenshot, lock screen.
 *  - dispatchGesture(): toques e swipes em qualquer ponto da tela.
 *  - rootInActiveWindow: ler a hierarquia da tela, achar botoes por
 *    texto/conteudo e clicar neles.
 *
 * Quando o servidor JARVIS responder com uma instrucao do tipo
 * [ACTION:PHONE] ..., a UI pode invocar `JarvisActionRouter` que
 * delega para os metodos abaixo.
 *
 * O senhor precisa habilitar este servico em Configuracoes →
 * Acessibilidade → JARVIS — Controle do Telefone.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected — controle total disponivel.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Em uma versao avancada poderiamos enviar o conteudo da tela ao
        // servidor JARVIS para que ele tenha contexto visual em tempo real.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API publica usada pelo router
    // ─────────────────────────────────────────────────────────────────────────

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
    fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    /** Toca em (x, y) na tela com um clique rapido. */
    fun tap(x: Float, y: Float, durationMs: Long = 50L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Faz um swipe entre dois pontos. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Procura na hierarquia da janela ativa por um node cujo texto contenha
     * [query] (case-insensitive). Util para clicar em botoes por nome —
     * "OK", "Salvar", "Continuar", etc.
     */
    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val needle = query.lowercase()
        val candidate = findClickableContaining(root, needle) ?: return false
        return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findClickableContaining(
        node: AccessibilityNodeInfo,
        needle: String,
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if ((text.contains(needle) || desc.contains(needle)) && node.isClickable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = findClickableContaining(child, needle)
            if (match != null) return match
        }
        return null
    }

    /** Transcricao textual da tela atual — fica util para o servidor JARVIS. */
    fun describeScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        traverse(root, 0, sb)
        return sb.toString()
    }

    private fun traverse(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        val pad = "  ".repeat(depth)
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (text.isNotBlank() || desc.isNotBlank()) {
            sb.append(pad).append(text.ifBlank { desc }).append("\n")
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            traverse(c, depth + 1, sb)
        }
    }

    companion object {
        private const val TAG = "JarvisAcc"

        @Volatile
        private var instance: JarvisAccessibilityService? = null

        fun get(): JarvisAccessibilityService? = instance
        fun isEnabled(): Boolean = instance != null
    }
}
