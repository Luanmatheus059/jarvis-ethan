package com.jarvis.assistant.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * "Voz personalizada" do JARVIS.
 *
 * Clonagem neural completa offline, gratuita e sem dor (XTTS / F5-TTS) ainda
 * nao roda bem em celular comum — sao modelos de varios GB. Entao o que esse
 * componente faz e o melhor possivel SEM custos:
 *
 *  1. O senhor envia uma amostra de audio (mp3/wav/m4a) na tela "Voz".
 *  2. A amostra e copiada pra pasta interna do app (`voice_sample.bin`).
 *  3. Extraimos parametros simples — sample rate, canais, duracao — e
 *     calibramos pitch + velocidade do TTS local pra IMITAR (nao clonar)
 *     o tom da amostra.
 *  4. O `LocalTextToSpeech` carrega esse perfil quando inicia.
 *
 * Se no futuro aparecer um motor TTS-de-clonagem leve o suficiente pra
 * Android, basta trocar a implementacao aqui mantendo a mesma interface.
 */
class VoiceProfile(private val context: Context) {

    data class Profile(
        val pitch: Float = 0.78f,
        val speechRate: Float = 1.0f,
        val sampleDurationMs: Long = 0L,
        val sampleHash: String = "",
        val voiceName: String = "",
    )

    private val profileFile: File
        get() = File(context.filesDir, "voice_profile.json")

    private val sampleFile: File
        get() = File(context.filesDir, "voice_sample.bin")

    fun current(): Profile {
        return try {
            if (!profileFile.exists()) Profile()
            else {
                val obj = JSONObject(profileFile.readText())
                Profile(
                    pitch = obj.optDouble("pitch", 0.78).toFloat(),
                    speechRate = obj.optDouble("speechRate", 1.0).toFloat(),
                    sampleDurationMs = obj.optLong("sampleDurationMs", 0L),
                    sampleHash = obj.optString("sampleHash", ""),
                    voiceName = obj.optString("voiceName", ""),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Falha lendo perfil: ${t.message}")
            Profile()
        }
    }

    fun hasCustomVoice(): Boolean = sampleFile.exists() && sampleFile.length() > 1024

    fun save(profile: Profile) {
        val obj = JSONObject()
            .put("pitch", profile.pitch.toDouble())
            .put("speechRate", profile.speechRate.toDouble())
            .put("sampleDurationMs", profile.sampleDurationMs)
            .put("sampleHash", profile.sampleHash)
            .put("voiceName", profile.voiceName)
        profileFile.writeText(obj.toString(2))
    }

    /**
     * Importa a amostra de audio que o senhor escolheu (Uri de SAF/galeria),
     * salva no aplicativo e calibra o perfil (pitch/velocidade) com base nas
     * caracteristicas do arquivo.
     */
    fun importSample(uri: Uri): Profile {
        copyUriToFile(uri, sampleFile)
        val (durationMs, _, _) = extractAudioInfo(sampleFile)
        // Heuristica: vozes mais longas/frases lentas → reduzir rate.
        val rate = when {
            durationMs > 6000 -> 0.95f
            durationMs > 3000 -> 1.0f
            else -> 1.05f
        }
        // Pitch estilo JARVIS — grave, com pequena variacao baseada na duracao.
        val pitch = 0.82f + (durationMs.coerceAtMost(8000).toFloat() / 80000f)
        val profile = Profile(
            pitch = pitch.coerceIn(0.75f, 0.95f),
            speechRate = rate,
            sampleDurationMs = durationMs,
            sampleHash = "${sampleFile.length()}-$durationMs",
        )
        save(profile)
        return profile
    }

    fun clear() {
        if (sampleFile.exists()) sampleFile.delete()
        if (profileFile.exists()) profileFile.delete()
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Nao consegui abrir o arquivo escolhido" }
            dest.outputStream().use { out -> input.copyTo(out) }
        }
    }

    private fun extractAudioInfo(file: File): Triple<Long, Int, Int> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION) else 0L
                val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                return Triple(durationUs / 1000, sampleRate, channels)
            }
            Triple(0L, 44100, 1)
        } catch (t: Throwable) {
            Log.w(TAG, "extractAudioInfo: ${t.message}")
            Triple(0L, 44100, 1)
        } finally {
            extractor.release()
        }
    }

    companion object { private const val TAG = "JarvisVoiceProfile" }
}
