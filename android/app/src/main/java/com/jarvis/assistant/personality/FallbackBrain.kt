package com.jarvis.assistant.personality

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import com.jarvis.assistant.learning.RealtimeLearner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Cérebro de regras — funciona SEM LLM instalado.
 *
 * Cobre o conjunto de comandos mais útil pro dia a dia: ver hora/data,
 * abrir apps, ligar para contato pelo nome, mandar SMS, ajustar volume e
 * brilho, criar alarme, "como tá o tempo", "alguma novidade".
 *
 * Quando reconhece, devolve uma resposta de duas frases (estilo JARVIS) e
 * já dispara a ação no telefone via PhoneActionRouter. Quando NÃO
 * reconhece, retorna `null` — aí o fluxo principal pode chamar o LLM ou
 * dizer que não entendeu.
 */
class FallbackBrain(private val context: Context) {

    private val learner = RealtimeLearner(context)

    /**
     * Tenta responder ao texto. Devolve [Reply] com a frase falada e
     * (opcionalmente) acoes a executar; ou null se não conseguiu resolver.
     */
    suspend fun handle(text: String): Reply? {
        val low = text.lowercase().trim()
        if (low.isBlank()) return null

        // ─── Saudações e cortesia ────────────────────────────────────────
        if (matchesAny(low, "bom dia", "bom dia jarvis")) {
            return Reply(greetingForNow() + ", senhor. Como posso servir?")
        }
        if (matchesAny(low, "boa tarde")) {
            return Reply("Boa tarde, senhor.")
        }
        if (matchesAny(low, "boa noite")) {
            return Reply("Boa noite, senhor.")
        }
        if (matchesAny(low, "oi jarvis", "olá jarvis", "ei jarvis")) {
            return Reply("Às ordens, senhor.")
        }
        if (matchesAny(low, "obrigado", "obrigada", "valeu")) {
            return Reply("Sempre, senhor.")
        }
        if (matchesAny(low, "tudo bem", "como vai", "como você está")) {
            return Reply("Em perfeito funcionamento, senhor. Em que posso ajudar?")
        }
        if (matchesAny(low, "quem é você", "quem e voce", "se apresenta")) {
            return Reply("JARVIS — Just A Rather Very Intelligent System. Ao seu dispor.")
        }

        // ─── Hora e data ─────────────────────────────────────────────────
        if (matchesAny(low, "que horas", "que hora", "horas são", "me diz a hora")) {
            val now = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())
            return Reply("São $now, senhor.")
        }
        if (matchesAny(low, "que dia", "que data", "data hoje")) {
            val now = SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy", Locale("pt", "BR")).format(Date())
            return Reply("Hoje é $now, senhor.")
        }

        // ─── Telefone: home, voltar, recentes, lock, screenshot ──────────
        if (matchesAny(low, "tela inicial", "ir pra home", "vai pra home", "página inicial")) {
            return Reply("Sim, senhor.", actions = "[PHONE:HOME]")
        }
        if (matchesAny(low, "volta", "voltar", "tela anterior")) {
            return Reply("Voltando, senhor.", actions = "[PHONE:BACK]")
        }
        if (matchesAny(low, "apps abertos", "recentes", "aplicativos recentes")) {
            return Reply("Aqui estão os recentes, senhor.", actions = "[PHONE:RECENTS]")
        }
        if (matchesAny(low, "trava a tela", "bloqueia a tela", "bloqueia o celular")) {
            return Reply("Travando, senhor.", actions = "[PHONE:LOCK]")
        }
        if (matchesAny(low, "captura de tela", "screenshot", "tira print")) {
            return Reply("Capturando, senhor.", actions = "[PHONE:SCREENSHOT]")
        }
        if (matchesAny(low, "abre a câmera", "abrir câmera", "abre câmera", "abre a camera", "câmera por favor")) {
            return Reply("Câmera, senhor.", actions = "[PHONE:CAMERA]")
        }
        if (matchesAny(low, "notificações", "abre as notificações", "minhas notificações")) {
            return Reply("Notificações, senhor.", actions = "[PHONE:NOTIFICATIONS]")
        }

        // ─── Volume / brilho ─────────────────────────────────────────────
        Regex("""volume (?:em |para |pra )?(\d+)""").find(low)?.let { m ->
            val pct = m.groupValues[1].toInt().coerceIn(0, 100)
            return Reply("Volume em $pct%, senhor.", actions = "[PHONE:VOLUME] $pct")
        }
        Regex("""(?:brilho|luminosidade) (?:em |para |pra )?(\d+)""").find(low)?.let { m ->
            val pct = m.groupValues[1].toInt().coerceIn(0, 100)
            return Reply("Brilho em $pct%, senhor.", actions = "[PHONE:BRIGHTNESS] $pct")
        }

        // ─── Abrir app ───────────────────────────────────────────────────
        Regex("""(?:abre|abrir|inicia|iniciar) (?:o |a |meu |minha )?(.+)""").find(low)?.let { m ->
            val name = m.groupValues[1].trim()
            val pkg = resolvePackageByName(name)
            if (pkg != null) {
                return Reply("Abrindo $name, senhor.", actions = "[PHONE:OPEN_APP] $pkg")
            }
            // se não achou pacote, manda o sistema lidar — abre via search
            return Reply(
                "Não localizei o app \"$name\" no aparelho, senhor. Pode dizer outro nome?",
            )
        }

        // ─── Ligar pra contato ───────────────────────────────────────────
        Regex("""(?:liga|ligar|chama|chamar|telefonar) (?:para |pra |pro |pra o )?(.+)""")
            .find(low)?.let { m ->
                val name = m.groupValues[1].trim()
                val number = lookupContactPhone(name)
                if (number != null) {
                    return Reply("Ligando para $name, senhor.", actions = "[PHONE:CALL] $number")
                }
                return Reply("Não achei \"$name\" na sua agenda, senhor.")
            }

        // ─── Mandar mensagem (SMS) ───────────────────────────────────────
        Regex("""(?:mandar|manda|envia|enviar) (?:mensagem |sms )(?:para |pra |pro )?(.+?) dizendo (.+)""")
            .find(low)?.let { m ->
                val name = m.groupValues[1].trim()
                val msg = m.groupValues[2].trim()
                val number = lookupContactPhone(name)
                if (number != null) {
                    return Reply(
                        "Mensagem enviada para $name, senhor.",
                        actions = "[PHONE:SMS] $number ||| $msg",
                    )
                }
                return Reply("Não achei \"$name\" na sua agenda, senhor.")
            }

        // ─── Alarme ──────────────────────────────────────────────────────
        Regex("""(?:alarme|despertador) (?:para |pra )?(\d{1,2})[: hH](\d{2})""")
            .find(low)?.let { m ->
                val h = m.groupValues[1].toInt().coerceIn(0, 23)
                val mi = m.groupValues[2].toInt().coerceIn(0, 59)
                return Reply(
                    "Alarme para %02d:%02d configurado, senhor.".format(h, mi),
                    actions = "[PHONE:ALARM] %02d:%02d ||| Despertar".format(h, mi),
                )
            }

        // ─── Notícias / pesquisa em tempo real ───────────────────────────
        if (matchesAny(low, "alguma novidade", "novidades", "alguma notícia", "alguma noticia",
                "me atualiza", "o que tá acontecendo", "que tá rolando")) {
            val snippets = learner.research("notícias do dia")
            if (snippets.isEmpty()) return Reply("Nada relevante apareceu ainda, senhor.")
            val first = snippets.first()
            return Reply("${first.title}. ${first.summary.take(160)}")
        }

        Regex("""(?:pesquisa|pesquisar|me fala sobre|busca|me conta sobre|notícias sobre|noticias sobre) (.+)""")
            .find(low)?.let { m ->
                val q = m.groupValues[1].trim()
                val snippets = learner.research(q)
                if (snippets.isEmpty()) return Reply("Não encontrei nada sobre \"$q\", senhor.")
                val first = snippets.first()
                return Reply("Sobre $q, senhor: ${first.title}. ${first.summary.take(160)}")
            }

        // ─── Caso padrão: não entendi ────────────────────────────────────
        return null
    }

    private fun greetingForNow(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 12 -> "Bom dia"
            h < 18 -> "Boa tarde"
            else -> "Boa noite"
        }
    }

    private fun matchesAny(low: String, vararg patterns: String): Boolean =
        patterns.any { low.contains(it) }

    /** Tenta achar o package name do app pelo nome falado. */
    private fun resolvePackageByName(name: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, 0)
        val needle = name.lowercase().trim()
        // Match exato por label
        for (info in activities) {
            val label = info.loadLabel(pm).toString().lowercase()
            if (label == needle) return info.activityInfo.packageName
        }
        // Match parcial
        for (info in activities) {
            val label = info.loadLabel(pm).toString().lowercase()
            if (label.contains(needle) || needle.contains(label)) {
                return info.activityInfo.packageName
            }
        }
        // Match conhecido (pacotes populares)
        val known = mapOf(
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger",
            "chrome" to "com.android.chrome",
            "navegador" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "mapas" to "com.google.android.apps.maps",
            "câmera" to "com.android.camera",
            "camera" to "com.android.camera",
            "calculadora" to "com.google.android.calculator",
            "relógio" to "com.google.android.deskclock",
            "relogio" to "com.google.android.deskclock",
            "calendário" to "com.google.android.calendar",
            "calendario" to "com.google.android.calendar",
            "configurações" to "com.android.settings",
            "configuracoes" to "com.android.settings",
        )
        return known[needle]
    }

    /** Procura na agenda do telefone um número pelo nome. */
    private fun lookupContactPhone(name: String): String? {
        return try {
            val needle = name.lowercase()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val display = cursor.getString(nameIdx)?.lowercase().orEmpty()
                    if (display.contains(needle) || needle.contains(display)) {
                        return@use cursor.getString(numIdx)
                    }
                }
                null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    data class Reply(val spoken: String, val actions: String = "")
}
