package com.jarvis.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.service.JarvisForegroundService
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orb reativo inspirado no visualizador Three.js do frontend web.
 *
 * Cores e movimento mudam conforme o estado:
 *  - IDLE: anel calmo azul, pulsando suavemente
 *  - LISTENING: anel azul vivo, particulas orbitando
 *  - THINKING: rotacao mais rapida com gradiente em tons de ouro
 *  - SPEAKING: pulsacao em ritmo de fala
 *  - DISCONNECTED: anel vermelho-pastel
 */
@Composable
fun JarvisOrb(state: JarvisForegroundService.State) {
    val transition = rememberInfiniteTransition(label = "orb")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = stateRotationMs(state), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == JarvisForegroundService.State.SPEAKING) 350 else 1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val (innerColor, outerColor) = colorsFor(state)

    Canvas(modifier = Modifier.size(220.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (size.minDimension / 2f) * pulse

        // Halo externo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(outerColor.copy(alpha = 0.55f), Color.Transparent),
                center = center,
                radius = radius * 1.4f,
            ),
            radius = radius * 1.4f,
            center = center,
        )

        // Esfera interna
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(innerColor, outerColor),
                center = center,
                radius = radius,
            ),
            radius = radius * 0.85f,
            center = center,
        )

        // 12 particulas orbitando
        val particleCount = 12
        for (i in 0 until particleCount) {
            val theta = Math.toRadians((angle + i * (360f / particleCount)).toDouble())
            val px = center.x + cos(theta).toFloat() * radius * 1.05f
            val py = center.y + sin(theta).toFloat() * radius * 1.05f
            drawCircle(
                color = innerColor.copy(alpha = 0.85f),
                radius = 4f,
                center = Offset(px, py),
            )
        }
    }
}

private fun stateRotationMs(state: JarvisForegroundService.State): Int = when (state) {
    JarvisForegroundService.State.THINKING -> 2200
    JarvisForegroundService.State.SPEAKING -> 3000
    JarvisForegroundService.State.LISTENING -> 5000
    else -> 9000
}

private fun colorsFor(state: JarvisForegroundService.State): Pair<Color, Color> = when (state) {
    JarvisForegroundService.State.IDLE -> Color(0xFF66E0FF) to Color(0xFF003F66)
    JarvisForegroundService.State.LISTENING -> Color(0xFF00C8FF) to Color(0xFF0066AA)
    JarvisForegroundService.State.THINKING -> Color(0xFFFFD66E) to Color(0xFF8A5A00)
    JarvisForegroundService.State.SPEAKING -> Color(0xFFB6F1FF) to Color(0xFF00B7FF)
    JarvisForegroundService.State.DISCONNECTED -> Color(0xFFFFB0B0) to Color(0xFF7A2222)
}
