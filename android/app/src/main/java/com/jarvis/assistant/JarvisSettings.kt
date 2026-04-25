package com.jarvis.assistant

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("jarvis_settings")

/**
 * Wrapper sobre DataStore — guarda idioma de reconhecimento, palavra de
 * ativacao e operacao em segundo plano.
 *
 * Tudo aqui e usado pelo JARVIS em modo 100% local (sem servidor).
 */
class JarvisSettings(private val context: Context) {

    val recognitionLanguage: Flow<String> = context.dataStore.data.map { it[KEY_LANG] ?: "pt-BR" }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE] ?: true }
    val backgroundEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_BG] ?: true }

    suspend fun setRecognitionLanguage(lang: String) {
        context.dataStore.edit { it[KEY_LANG] = lang }
    }

    suspend fun setWakeWord(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WAKE] = enabled }
    }

    suspend fun setBackground(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BG] = enabled }
    }

    companion object {
        private val KEY_LANG = stringPreferencesKey("recognition_language")
        private val KEY_WAKE = booleanPreferencesKey("wake_word_enabled")
        private val KEY_BG = booleanPreferencesKey("background_enabled")
    }
}
