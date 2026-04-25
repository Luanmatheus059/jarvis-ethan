package com.jarvis.assistant.learning

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Aprendizado em tempo real — direto do aparelho, sem precisar do servidor.
 *
 * Tres fontes:
 *  - GitHub Search API (repos relevantes pra consulta)
 *  - arXiv (papers recentes)
 *  - Google News RSS (noticias do dia)
 *
 * O resultado vai cacheado em arquivo simples (JSONL) na pasta interna
 * do app, e tambem alimenta o contexto que o JARVIS usa pra responder o
 * senhor — assim ele sempre tem contexto fresco do que aconteceu na
 * internet sem depender de servidor.
 */
class RealtimeLearner(private val context: Context) {

    data class Snippet(
        val source: String,
        val title: String,
        val url: String,
        val summary: String,
    )

    private val http = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File by lazy {
        File(context.filesDir, "realtime_knowledge.jsonl").apply { parentFile?.mkdirs() }
    }

    suspend fun research(query: String): List<Snippet> = coroutineScope {
        val tasks = listOf(
            async(Dispatchers.IO) { searchGithub(query) },
            async(Dispatchers.IO) { searchArxiv(query) },
            async(Dispatchers.IO) { searchNews(query) },
        )
        val results = tasks.awaitAll().flatten()
        persist(query, results)
        results
    }

    /** Resumo curto para inserir no contexto da resposta do JARVIS. */
    fun summarizeForPrompt(snippets: List<Snippet>): String {
        if (snippets.isEmpty()) return ""
        val sb = StringBuilder("CONTEXTO RECENTE DA INTERNET:\n")
        for (s in snippets.take(8)) {
            val short = s.summary.take(220).replace('\n', ' ')
            sb.append("- [").append(s.source).append("] ").append(s.title)
                .append(" — ").append(short).append('\n')
        }
        return sb.toString()
    }

    // ─── Fontes ──────────────────────────────────────────────────────────────

    private fun searchGithub(query: String): List<Snippet> {
        val url = "https://api.github.com/search/repositories?q=" +
                java.net.URLEncoder.encode(query, "UTF-8") +
                "&sort=stars&order=desc&per_page=4"
        return runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string().orEmpty()
                    parseGithub(body)
                }
        }.getOrElse { emptyList() }
    }

    private fun parseGithub(json: String): List<Snippet> {
        val out = mutableListOf<Snippet>()
        val regex = Regex(
            """"full_name":\s*"([^"]+)".*?"html_url":\s*"([^"]+)".*?"description":\s*("[^"]*"|null)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        for (m in regex.findAll(json).take(4)) {
            val name = m.groupValues[1]
            val link = m.groupValues[2]
            val desc = m.groupValues[3].trim('"').takeIf { it != "null" }.orEmpty()
            out += Snippet("GitHub", name, link, desc)
        }
        return out
    }

    private fun searchArxiv(query: String): List<Snippet> {
        val url = "http://export.arxiv.org/api/query?search_query=all:" +
                java.net.URLEncoder.encode(query, "UTF-8") +
                "&start=0&max_results=4&sortBy=submittedDate&sortOrder=descending"
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(body, "", Parser.xmlParser())
                doc.select("entry").take(4).map { entry ->
                    Snippet(
                        source = "arXiv",
                        title = entry.selectFirst("title")?.text()?.trim().orEmpty(),
                        url = entry.selectFirst("id")?.text()?.trim().orEmpty(),
                        summary = entry.selectFirst("summary")?.text()?.trim()?.take(400).orEmpty(),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun searchNews(query: String): List<Snippet> {
        val rss = "https://news.google.com/rss/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8") +
                "&hl=pt-BR&gl=BR&ceid=BR:pt-419"
        return runCatching {
            http.newCall(Request.Builder().url(rss).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                val doc = Jsoup.parse(body, "", Parser.xmlParser())
                doc.select("item").take(4).map { item ->
                    Snippet(
                        source = "Noticias",
                        title = item.selectFirst("title")?.text()?.trim().orEmpty(),
                        url = item.selectFirst("link")?.text()?.trim().orEmpty(),
                        summary = item.selectFirst("description")?.text()?.let {
                            Jsoup.parse(it).text()
                        }?.take(400).orEmpty(),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    // ─── Cache ───────────────────────────────────────────────────────────────

    private suspend fun persist(query: String, snippets: List<Snippet>) = withContext(Dispatchers.IO) {
        runCatching {
            cacheFile.appendText(buildString {
                val ts = System.currentTimeMillis()
                for (s in snippets) {
                    append("{\"ts\":").append(ts)
                        .append(",\"q\":\"").append(escape(query)).append('\"')
                        .append(",\"source\":\"").append(s.source).append('\"')
                        .append(",\"title\":\"").append(escape(s.title)).append('\"')
                        .append(",\"url\":\"").append(escape(s.url)).append('\"')
                        .append(",\"summary\":\"").append(escape(s.summary)).append('\"')
                        .append("}\n")
                }
            })
        }.onFailure { Log.w(TAG, "Cache fail: ${it.message}") }
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    companion object { private const val TAG = "JarvisLearn" }
}
