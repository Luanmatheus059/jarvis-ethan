package com.jarvis.assistant.learning

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Worker periodico — a cada 30 minutos o JARVIS atualiza um pequeno
 * cache de noticias / repos em alta / arxiv mais recentes para que ele
 * sempre tenha contexto fresco quando o senhor perguntar "alguma
 * novidade?".
 */
class NewsRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val learner = RealtimeLearner(applicationContext)
        val topics = listOf(
            "tecnologia hoje",
            "inteligencia artificial",
            "android novidades",
            "trending repository week",
            "machine learning recente",
        )
        var ok = false
        for (t in topics) {
            try {
                if (learner.research(t).isNotEmpty()) ok = true
            } catch (_: Throwable) {
                // ignora — proximo ciclo tenta de novo
            }
        }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "jarvis_realtime_news"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<NewsRefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}
