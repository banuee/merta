package dev.merta.app.data.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import dev.merta.app.data.tools.PendingApproval
import dev.merta.app.data.tools.ToolCall
import dev.merta.app.data.tools.ToolDefs
import dev.merta.app.data.tools.ToolRegistry

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

    /** Колбэки агентного цикла (UI решает approve, показывает прогресс). */
    interface AgentCallbacks {
        fun onDelta(text: String)

        /** Кусок размышлений модели (reasoning-стрим) — UI копит в Thought. */
        fun onReasoning(text: String)

        /** Начало нового хода (промежуточный ответ/мышление — отдельными блоками). */
        fun onTurnStart()

        fun onToolStart(name: String, summary: String)
        suspend fun onApproval(approval: PendingApproval): Boolean
    }

    /**
     * Агентный цикл: ходы с tools, сборка tool_calls из SSE-фрагментов,
     * выполнение через [registry] (approve — через колбэк), возврат финального текста.
     * Текстовые дельты всех ходов стримятся через onDelta, размышления — через onReasoning.
     *
     * Устойчивость к капризным провайдерам: если endpoint отвечает 400 на effort
     * (модель без reasoning) или на tools (модель без function calling) — ход
     * повторяется без спорного параметра вместо ошибки в лицо.
     */
    suspend fun runAgent(
        model: String,
        transcript: MutableList<TurnMessage>,
        effort: String?,
        registry: ToolRegistry,
        maxTurns: Int = 8,
        cb: AgentCallbacks,
    ): String {
        val totalText = StringBuilder()
        var effortCur = effort
        var toolsCur = true
        var turn = 0
        while (turn < maxTurns) {
            cb.onTurnStart()
            // Повторы без effort/tools — тот же ход, новый onTurnStart не нужен.
            var t: AgentTurn
            while (true) {
                try {
                    t = postAgentTurn(model, transcript, effortCur, toolsCur, cb::onDelta, cb::onReasoning)
                    break
                } catch (e: LlmException) {
                    val msg = e.message ?: ""
                    if (e.status == 400 && effortCur != null && mentionsReasoning(msg)) {
                        effortCur = null
                        continue
                    }
                    if (e.status == 400 && toolsCur && mentionsTools(msg)) {
                        toolsCur = false
                        continue
                    }
                    throw e
                }
            }
            if (t.text.isNotBlank()) {
                totalText.append(t.text)
            }
            if (t.toolCalls.isEmpty() || !toolsCur) {
                if (t.text.isNotBlank()) {
                    transcript.add(TurnMessage("assistant", t.text))
                }
                return totalText.toString()
            }
            transcript.add(TurnMessage("assistant", t.text, t.toolCalls))
            for (tc in t.toolCalls) {
                val def = ToolDefs.byName(tc.name)
                val args = parseArgs(tc.argumentsJson, def?.params?.map { it.first } ?: emptyList())
                cb.onToolStart(tc.name, ToolRegistry.summary(dev.merta.app.data.tools.ToolCall(tc.id, tc.name, args)))
                val output = registry.execute(
                    ToolCall(tc.id, tc.name, args),
                ) { approval -> cb.onApproval(approval) }
                transcript.add(TurnMessage("tool", output, toolCallId = tc.id))
            }
            turn++
        }
        return totalText.toString()
    }

    private data class AgentTurn(val text: String, val toolCalls: List<OutToolCall>)

    private fun postAgentTurn(
        model: String,
        transcript: List<TurnMessage>,
        effort: String?,
        includeTools: Boolean,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): AgentTurn {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = buildAgentBody(model, transcript, effort, includeTools)
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
                    val msg = SseParser.extractErrorMessage(errBody)
                        ?: errBody.take(300).ifBlank { resp.message }
                    throw LlmException(resp.code, msg)
                }
                val reader = resp.body?.charStream()?.buffered()
                    ?: throw LlmException(-1, "пустое тело ответа")
                val full = StringBuilder()
                val frags = mutableMapOf<Int, FragAcc>()
                fun handlePayload(payload: String) {
                    val delta = SseParser.extractDelta(payload)
                    if (!delta.isNullOrEmpty()) {
                        full.append(delta)
                        onDelta(delta)
                    }
                    val reasoning = SseParser.extractReasoning(payload)
                    if (!reasoning.isNullOrEmpty()) {
                        onReasoning(reasoning)
                    }
                    for (frag in ToolCallsJson.extractFrags(payload)) {
                        val acc = frags.getOrPut(frag.index) { FragAcc() }
                        if (frag.id != null) acc.id = frag.id
                        if (frag.name != null) acc.name = frag.name
                        if (frag.argsFrag != null) acc.args.append(frag.argsFrag)
                    }
                }
                // По SSE чанк может ехать несколькими data:-строками — склеиваем,
                // как в postStream, иначе часть провайдеров молча теряет куски.
                var dataBlock = StringBuilder()
                reader.forEachLine { line ->
                    when {
                        line.startsWith("data:") -> {
                            val payload = line.removePrefix("data:").trimStart()
                            if (SseParser.isDone(payload)) {
                                if (dataBlock.isNotEmpty()) {
                                    handlePayload(dataBlock.toString())
                                    dataBlock = StringBuilder()
                                }
                                return@forEachLine
                            }
                            dataBlock.append(payload)
                            handlePayload(dataBlock.toString())
                            dataBlock = StringBuilder()
                        }
                        line.isBlank() -> {
                            if (dataBlock.isNotEmpty()) {
                                handlePayload(dataBlock.toString())
                                dataBlock = StringBuilder()
                            }
                        }
                    }
                }
                if (dataBlock.isNotEmpty()) handlePayload(dataBlock.toString())
                val calls = frags.entries.sortedBy { it.key }.mapNotNull { (idx, acc) ->
                    if (acc.name.isNullOrBlank()) return@mapNotNull null
                    OutToolCall(acc.id ?: "call-$idx", acc.name!!, acc.args.toString())
                }
                return AgentTurn(full.toString(), calls)
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw LlmException(-2, "отменено")
            throw LlmException(-1, "сеть: ${e.message}")
        } finally {
            if (currentCall === call) currentCall = null
        }
    }

    private data class FragAcc(
        var id: String? = null,
        var name: String? = null,
        val args: StringBuilder = StringBuilder(),
    )

    private fun parseArgs(argsJson: String, keys: List<String>): Map<String, String> {
        if (argsJson.isBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (k in keys) {
            SseParser.extractStringAfterKey(argsJson, k, 0)?.let { out[k] = it }
        }
        return out
    }

    private fun effortFields(effort: String?): String =
        effortFields(effort, isOpenRouter())

    private fun isOpenRouter(): Boolean = baseUrl.lowercase().contains("openrouter")

    private fun buildAgentBody(
        model: String,
        transcript: List<TurnMessage>,
        effort: String?,
        includeTools: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"model\":\"").append(SseParser.jsonEscape(model)).append('"')
        sb.append(",\"stream\":true")
        sb.append(effortFields(effort))
        if (includeTools) {
            sb.append(",\"tools\":").append(ToolDefs.toolsJson())
        }
        sb.append(",\"messages\":[")
        transcript.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append("{\"role\":\"").append(m.role).append('"')
            if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                sb.append(",\"content\":")
                if (m.content.isBlank()) sb.append("null") else sb.append('"').append(SseParser.jsonEscape(m.content)).append('"')
                sb.append(",\"tool_calls\":[")
                m.toolCalls.forEachIndexed { j, tc ->
                    if (j > 0) sb.append(',')
                    sb.append("{\"id\":\"").append(SseParser.jsonEscape(tc.id)).append('"')
                        .append(",\"type\":\"function\",\"function\":{\"name\":\"")
                        .append(SseParser.jsonEscape(tc.name)).append("\",\"arguments\":\"")
                        .append(SseParser.jsonEscape(tc.argumentsJson)).append("\"}}")
                }
                sb.append(']')
            } else {
                sb.append(",\"content\":\"").append(SseParser.jsonEscape(m.content)).append('"')
                if (m.role == "tool" && m.toolCallId != null) {
                    sb.append(",\"tool_call_id\":\"").append(SseParser.jsonEscape(m.toolCallId)).append('"')
                }
            }
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
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
        sb.append(effortFields(request.effort))
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

    private fun mentionsTools(errBody: String): Boolean =
        // 400 с упоминанием tools при отправленных tools — почти всегда
        // «модель не умеет function calling»: повторяем ход без tools.
        errBody.lowercase().contains("tool")

    private fun mentionsReasoning(errBody: String): Boolean {
        val lower = errBody.lowercase()
        return lower.contains("reasoning") || lower.contains("effort")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Поля уровня рассуждений для тела запроса.
         * OpenRouter понимает вложенный `reasoning.effort` (exclude:false — чтобы
         * reasoning-токены возвращались и стримились в Thought); остальные
         * OpenAI-совместимые (Zen и кастом) — плоский `reasoning_effort` из
         * нативного Chat Completions API. Пусто — effort выключен.
         */
        internal fun effortFields(effort: String?, openRouterStyle: Boolean): String {
            if (effort.isNullOrBlank()) return ""
            val e = SseParser.jsonEscape(effort)
            return if (openRouterStyle) {
                ",\"reasoning\":{\"effort\":\"$e\",\"exclude\":false}"
            } else {
                ",\"reasoning_effort\":\"$e\""
            }
        }

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
