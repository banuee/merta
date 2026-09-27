package dev.merta.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Настройки Direct API. Ключ и всё рядом — только в EncryptedSharedPreferences
 * (AES256), никогда в plain-DataStore и никогда в логи (см. AGENTS.md).
 */
class MertaSettings(context: Context) {

    data class LlmConfig(
        val baseUrl: String = Presets.OPENROUTER,
        val apiKey: String = "",
        val model: String = "",
        /** null = выкл, иначе low/medium/high (OpenRouter reasoning.effort). */
        val effort: String? = null,
    ) {
        val isConfigured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()
    }

    object Efforts {
        const val LOW = "low"
        const val MEDIUM = "medium"
        const val HIGH = "high"
        val ALL = listOf(LOW, MEDIUM, HIGH)
    }

    object Presets {
        const val OPENROUTER = "https://openrouter.ai/api/v1"
        const val ZEN = "https://opencode.ai/zen/v1"
        const val OLLAMA = "http://127.0.0.1:11434/v1"

        /** Дефолтные модели-подсказки для пресетов. */
        fun defaultModelFor(baseUrl: String): String = when (baseUrl.trimEnd('/')) {
            ZEN -> "mimo-v2.5-free"
            OLLAMA -> "llama3"
            else -> ""
        }
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "merta_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, Presets.OPENROUTER) ?: Presets.OPENROUTER,
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        model = prefs.getString(KEY_MODEL, "") ?: "",
        effort = prefs.getString(KEY_EFFORT, null)?.ifBlank { null },
    )

    fun save(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_EFFORT, config.effort ?: "")
            .apply()
    }

    /** Настройки OTA-обновлений (не секреты, но живут рядом — так же, как в лаунчере). */
    data class UpdateConfig(
        /** 0 = никогда, иначе минуты: 10/30/60/180/360/720/1440. */
        val intervalMinutes: Int = 0,
        val lastNotifiedVersion: String = "",
    )

    fun loadUpdate(): UpdateConfig = UpdateConfig(
        intervalMinutes = prefs.getInt(KEY_UPDATE_INTERVAL, 0),
        lastNotifiedVersion = prefs.getString(KEY_LAST_NOTIFIED, "") ?: "",
    )

    fun saveUpdate(config: UpdateConfig) {
        prefs.edit()
            .putInt(KEY_UPDATE_INTERVAL, config.intervalMinutes)
            .putString(KEY_LAST_NOTIFIED, config.lastNotifiedVersion)
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private const val KEY_EFFORT = "llm_effort"
        private const val KEY_UPDATE_INTERVAL = "update_interval_minutes"
        private const val KEY_LAST_NOTIFIED = "update_last_notified"
    }
}
