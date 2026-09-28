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
        /** null = выкл, иначе low/medium/high (OpenRouter: reasoning.effort, остальные: reasoning_effort). */
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

    // ---------- провайдеры ----------

    /**
     * Список провайдеров. Первый запуск: миграция со старого одиночного конфига
     * (endpoint/ключ) либо сиды OpenRouter + Zen с пустыми ключами.
     */
    fun loadProviders(): List<Provider> {
        val raw = prefs.getString(KEY_PROVIDERS, null)
        if (raw != null) return ProviderJson.providersFromJson(raw)
        val legacyUrl = prefs.getString(KEY_BASE_URL, "") ?: ""
        val legacyKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val seeded = when {
            legacyUrl.isNotBlank() -> listOf(
                Provider(
                    id = slugFor(legacyUrl),
                    name = nameFor(legacyUrl),
                    baseUrl = legacyUrl.trim().trimEnd('/'),
                    apiKey = legacyKey,
                ),
            )
            else -> listOf(
                Provider("openrouter", "OpenRouter", Presets.OPENROUTER, ""),
                Provider("zen", "Zen", Presets.ZEN, ""),
            )
        }
        saveProviders(seeded)
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, seeded.first().id).apply()
        return seeded
    }

    fun saveProviders(providers: List<Provider>) {
        prefs.edit().putString(KEY_PROVIDERS, ProviderJson.providersToJson(providers)).apply()
        val active = activeProviderId()
        if (providers.none { it.id == active } && providers.isNotEmpty()) {
            setActiveProvider(providers.first().id)
        }
    }

    fun activeProviderId(): String {
        val stored = prefs.getString(KEY_ACTIVE_PROVIDER, null)
        val list = loadProviders()
        return if (list.any { it.id == stored }) stored!! else list.firstOrNull()?.id ?: "openrouter"
    }

    fun setActiveProvider(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, id).apply()
    }

    fun activeProvider(): Provider? {
        val id = activeProviderId()
        return loadProviders().find { it.id == id }
    }

    /** Выбранная модель провайдера (фолбэк — старый одиночный model / дефолт пресетов). */
    fun selectedModel(providerId: String): String {
        val sel = ProviderJson.modelsCacheFromJson(prefs.getString(KEY_SELECTED, "{}") ?: "{}")
        sel[providerId]?.keys?.firstOrNull()?.let { return it }
        val legacy = prefs.getString(KEY_MODEL, "") ?: ""
        if (legacy.isNotBlank()) return legacy
        val provider = loadProviders().find { it.id == providerId }
        return Presets.defaultModelFor(provider?.baseUrl ?: "")
    }

    fun setSelectedModel(providerId: String, modelId: String) {
        val sel = ProviderJson.modelsCacheFromJson(prefs.getString(KEY_SELECTED, "{}") ?: "{}").toMutableMap()
        sel[providerId] = mapOf(modelId to "")
        prefs.edit().putString(KEY_SELECTED, ProviderJson.modelsCacheToJson(sel)).apply()
    }

    /** Кэш имён моделей (providerId -> modelId -> displayName) для подписей без сети. */
    fun modelsNamesCache(): Map<String, Map<String, String>> =
        ProviderJson.modelsCacheFromJson(prefs.getString(KEY_MODELS, "{}") ?: "{}")

    fun saveModelsNamesCache(cache: Map<String, Map<String, String>>) {
        prefs.edit().putString(KEY_MODELS, ProviderJson.modelsCacheToJson(cache)).apply()
    }

    fun displayNameFor(providerId: String, modelId: String): String {
        val name = modelsNamesCache()[providerId]?.get(modelId)
        return if (name.isNullOrBlank()) modelId else name
    }

    /** Авто-разрешение write_file/run_command без диалога (по умолчанию выкл). */
    fun loadAutoApprove(): Boolean = prefs.getBoolean(KEY_AUTO_APPROVE, false)

    fun saveAutoApprove(on: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_APPROVE, on).apply()
    }

    companion object {
        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private const val KEY_EFFORT = "llm_effort"
        private const val KEY_UPDATE_INTERVAL = "update_interval_minutes"
        private const val KEY_LAST_NOTIFIED = "update_last_notified"
        private const val KEY_PROVIDERS = "llm_providers"
        private const val KEY_ACTIVE_PROVIDER = "llm_active_provider"
        private const val KEY_MODELS = "llm_models_cache"
        private const val KEY_SELECTED = "llm_selected_models"
        private const val KEY_AUTO_APPROVE = "agent_auto_approve"

        private fun slugFor(baseUrl: String): String {
            val u = baseUrl.trimEnd('/').lowercase()
            return when {
                "openrouter" in u -> "openrouter"
                "opencode" in u || "/zen" in u -> "zen"
                "127.0.0.1" in u || "localhost" in u -> "local"
                else -> "custom"
            }
        }

        private fun nameFor(baseUrl: String): String = when (slugFor(baseUrl)) {
            "openrouter" -> "OpenRouter"
            "zen" -> "Zen"
            "local" -> "Local"
            else -> "Custom"
        }
    }
}
