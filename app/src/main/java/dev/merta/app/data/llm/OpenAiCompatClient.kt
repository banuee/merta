package dev.merta.app.data.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-совместимый клиент: OpenRouter (`https://openrouter.ai/api/v1`),
 * OpenCode Zen (`https://opencode.ai/zen/v1`), любой custom endpoint.
 * Стриминг через `POST {baseUrl}/chat/completions` с `"stream": true`,
 * разбор — построчно через [SseParser] (без okhttp-sse, чтобы честно
 * контролировать отмену и таймауты).
 *
 * Безопасность: ключ только в Authorization-заголовке, в исключения и логи
 * попадает лишь статус + текст ошибки API (секреты вычищены).
 */
class OpenAiCompatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val http: OkHttpClient = defaultHttp(),
) : LlmStreamingProvider {

    override val id: String = "openai-compat"

    @Volatile
    private var currentCall: okhttp3.Call? = null

    override suspend fun streamChat(request: LlmRequest, onDelta: (String) -> Unit): String {
        // Фаза 1: инструменты не шлём вообще (все бесплатные модели работают).
        // Фаза 2 (tools): при 400 с упоминанием tools — повтор без блока tools.
        return postStream(request, includeTools = false, onDelta = onDelta)
    }

    /**
     * Каталог моделей. OpenRouter и Zen отдают OpenAI-совместимый `GET /models`.
     * Ошибка сети здесь — обычным LlmException, UI показывает текст.
     */
    override suspend fun listModels(): List<LlmModel> {
        val url = baseUrl.trimEnd('/') + "/models"
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build(),
        )
        try {
            call.execute().use { resp ->
                val body = try {
                    resp.body?.string()
                } catch (_: IOException) {
                    null
                } ?: ""
                if (!resp.isSuccessful) {
                    val msg = SseParser.extractErrorMessage(body)
                        ?: body.take(300).ifBlank { resp.message }
                    throw LlmException(resp.code, msg)
                }
                return ModelsJson.parseModelsList(body)
            }
        } catch (e: IOException) {
            throw LlmException(-1, "сеть: ${e.message}")
        }
    }

    /** Отмена активного стрима (новый вопрос, выход). */
    fun cancel() {
        currentCall?.cancel()
    }

    private fun postStream(
        request: LlmRequest,
        includeTools: Boolean,
        onDelta: (String) -> Unit,
    ): String {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = buildBody(request)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        for ((k, v) in extraHeaders) reqBuilder.header(k, v)
        val call = http.newCall(reqBuilder.post(body.toRequestBody(JSON)).build())
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = try {
                        resp.body?.string()
                    } catch (_: IOException) {
                        null
                    } ?: ""
                    if (resp.code == 400 && includeTools && mentionsTools(errBody)) {
                        return postStream(request, includeTools = false, onDelta = onDelta)
                    }
                    val msg = SseParser.extractErrorMessage(errBody)
                        ?: errBody.take(300).ifBlank { resp.message }
                    throw LlmException(resp.code, msg)
                }
                val reader = resp.body?.charStream()?.buffered()
                    ?: throw LlmException(-1, "пустое тело ответа")
                val full = StringBuilder()
                var dataBlock = StringBuilder()
                reader.forEachLine { line ->
                    when {
                        line.startsWith("data:") -> {
                            val payload = line.removePrefix("data:").trimStart()
                            if (SseParser.isDone(payload)) {
                                flushData(dataBlock, full, onDelta)
                                dataBlock = StringBuilder()
                                return@forEachLine
                            }
                            // По SSE несколько data:-строк склеиваются \n — чанки приходят одной строкой.
                            dataBlock.append(payload)
                            flushData(dataBlock, full, onDelta)
                            dataBlock = StringBuilder()
                        }
                        line.isBlank() -> {
                            flushData(dataBlock, full, onDelta)
                            dataBlock = StringBuilder()
                        }
                        // comment / event: / id: — игнорируем.
                    }
                }
                flushData(dataBlock, full, onDelta)
                return full.toString()
            }
        } catch (e: IOException) {
            // call.cancel() тоже приходит как IOException — различаем для UI.
            if (call.isCanceled()) throw LlmException(-2, "отменено")
            throw LlmException(-1, "сеть: ${e.message}")
        } finally {
            if (currentCall === call) currentCall = null
        }
    }

    private fun flushData(
        block: StringBuilder,
        full: StringBuilder,
        onDelta: (String) -> Unit,
    ) {
        if (block.isEmpty()) return
        val delta = SseParser.extractDelta(block.toString())
        if (!delta.isNullOrEmpty()) {
            full.append(delta)
            onDelta(delta)
        }
        block.clear()
    }

    private fun buildBody(request: LlmRequest): String {
        val sb = StringBuilder()
        sb.append("{\"model\":\"").append(SseParser.jsonEscape(request.model)).append('"')
        sb.append(",\"stream\":true")
        // Уровень рассуждений (OpenRouter `reasoning.effort`, low/medium/high).
        if (!request.effort.isNullOrBlank()) {
            sb.append(",\"reasoning\":{\"effort\":\"").append(SseParser.jsonEscape(request.effort)).append("\"}")
        }
        // tools добавит фаза 2; includeTools зарезервирован под retry-ветку.
        sb.append(",\"messages\":[")
        request.messages.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"role\":\"").append(SseParser.jsonEscape(m.role))
                .append("\",\"content\":\"").append(SseParser.jsonEscape(m.content)).append("\"}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun mentionsTools(errBody: String): Boolean {
        val lower = errBody.lowercase()
        return lower.contains("tool") && (
            lower.contains("not support") ||
                lower.contains("unsupported") ||
                lower.contains("no endpoints") ||
                lower.contains("400")
            )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Заголовки для OpenRouter (ранжирование + политика бесплатных моделей). */
        fun openRouterHeaders(): Map<String, String> = mapOf(
            "HTTP-Referer" to "https://github.com/merta-app",
            "X-Title" to "Merta",
        )

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
