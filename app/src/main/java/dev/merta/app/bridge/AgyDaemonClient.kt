package dev.merta.app.bridge

import dev.merta.app.bridge.AgyStreamJson.AgyEvent
import dev.merta.app.data.llm.LlmException
import dev.merta.app.data.llm.SseParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP-клиент демона merta-agy (127.0.0.1:18080 в proot).
 * Демона нет — LlmException(-1); agy занят — LlmException(409).
 */
class AgyDaemonClient(
    private val baseUrl: String,
    private val http: OkHttpClient = defaultHttp(),
) {

    data class DaemonStatus(
        val alive: Boolean,
        val agyVersion: String = "",
        /** Код agy-autopatch check: 0 ок, 1 нужен патч, 2 agy нет, 3 гейты. -1 — демон не ответил. */
        val patchCode: Int = -1,
        val patchOutput: String = "",
        val patcherBin: String = "",
        val rishBin: String = "",
        val rishOk: Boolean = false,
    )

    data class AgyRun(
        val prompt: String,
        val conversationId: String? = null,
        val model: String? = null,
        val effort: String? = null,
        val yolo: Boolean = false,
        val dirs: List<String> = emptyList(),
        val timeoutS: Int = 0,
    )

    suspend fun status(): DaemonStatus {
        val call = http.newCall(Request.Builder().url(base() + "/status").get().build())
        try {
            call.execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return DaemonStatus(alive = true)
                return DaemonStatus(
                    alive = true,
                    agyVersion = SseParser.extractStringAfterKey(body, "agy_version", 0) ?: "",
                    patchCode = extractInt(body, "code") ?: -1,
                    patchOutput = SseParser.extractStringAfterKey(body, "output", 0) ?: "",
                    patcherBin = SseParser.extractStringAfterKey(body, "patcher_bin", 0) ?: "",
                    rishBin = SseParser.extractStringAfterKey(body, "rish_bin", 0) ?: "",
                    rishOk = body.contains("\"rish_ok\": true"),
                )
            }
        } catch (e: IOException) {
            return DaemonStatus(alive = false)
        }
    }

    /**
     * Каталог моделей: демон отдаёт сырой вывод `agy models`, парсим здесь.
     * Пусто — демон недоступен или CLI без авторизации (тогда UI покажет фолбэк).
     */
    suspend fun models(): List<dev.merta.app.data.llm.LlmModel> {
        val call = http.newCall(Request.Builder().url(base() + "/models").get().build())
        try {
            call.execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return emptyList()
                val out = SseParser.extractStringAfterKey(body, "output", 0) ?: ""
                val code = extractInt(body, "code") ?: -1
                if (code != 0) return emptyList()
                return AgyModels.parse(out)
            }
        } catch (e: IOException) {
            return emptyList()
        }
    }

    suspend fun patch(): Pair<Int, String> {        val call = http.newCall(
            Request.Builder().url(base() + "/patch").post(ByteArray(0).toRequestBody(JSON)).build(),
        )
        try {
            call.execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val code = extractInt(body, "code") ?: -1
                val out = SseParser.extractStringAfterKey(body, "output", 0) ?: ""
                if (!resp.isSuccessful) throw LlmException(resp.code, out.ifBlank { resp.message })
                return code to out
            }
        } catch (e: IOException) {
            throw LlmException(-1, "демон merta-agy недоступен: ${e.message}")
        }
    }

    /**
     * Команда в Shizuku-shell телефона (POST /shell через rish).
     * IOException — демон недоступен (LlmException(-1)).
     */
    data class ShellResult(val code: Int, val output: String)

    suspend fun shell(command: String, timeoutS: Int = 60): ShellResult {
        val payload = "{\"command\":\"" + SseParser.jsonEscape(command) +
            "\",\"timeout_s\":" + timeoutS.coerceIn(5, 300) + "}"
        val call = http.newCall(
            Request.Builder().url(base() + "/shell").post(payload.toRequestBody(JSON)).build(),
        )
        try {
            call.execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val out = SseParser.extractStringAfterKey(body, "output", 0) ?: ""
                val code = extractInt(body, "code") ?: -1
                if (!resp.isSuccessful) {
                    val msg = SseParser.extractStringAfterKey(body, "error", 0)
                        ?: out.ifBlank { resp.message }
                    throw LlmException(resp.code, msg)
                }
                return ShellResult(code, out)
            }
        } catch (e: IOException) {
            throw LlmException(-1, "демон merta-agy недоступен: ${e.message}")
        }
    }

    /**
     * Стримит NDJSON прогона. Финал — событие Done (текст) или Error.
     * Ошибку демона (busy/пустой prompt) бросает исключением сразу.
     */
    suspend fun runStream(req: AgyRun, onEvent: (AgyEvent) -> Unit) {
        val payload = buildRunBody(req)
        val call = http.newCall(
            Request.Builder().url(base() + "/run").post(payload.toRequestBody(JSON)).build(),
        )
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = try {
                        resp.body?.string()
                    } catch (_: IOException) {
                        ""
                    } ?: ""
                    val msg = SseParser.extractStringAfterKey(errBody, "error", 0)
                        ?: errBody.take(200).ifBlank { resp.message }
                    throw LlmException(resp.code, msg)
                }
                val reader = resp.body?.charStream()?.buffered()
                    ?: throw LlmException(-1, "пустое тело ответа")
                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    for (ev in AgyStreamJson.parseLine(line)) onEvent(ev)
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw LlmException(-2, "отменено")
            throw LlmException(-1, "демон merta-agy недоступен: ${e.message}")
        }
    }

    private fun base(): String = baseUrl.trimEnd('/')

    private fun buildRunBody(req: AgyRun): String {
        val sb = StringBuilder("{\"prompt\":\"").append(SseParser.jsonEscape(req.prompt)).append('"')
        if (!req.conversationId.isNullOrBlank()) {
            sb.append(",\"conversation_id\":\"").append(SseParser.jsonEscape(req.conversationId)).append('"')
        }
        if (!req.model.isNullOrBlank()) {
            sb.append(",\"model\":\"").append(SseParser.jsonEscape(req.model)).append('"')
        }
        if (!req.effort.isNullOrBlank()) {
            sb.append(",\"effort\":\"").append(SseParser.jsonEscape(req.effort)).append('"')
        }
        if (req.yolo) sb.append(",\"yolo\":true")
        if (req.dirs.isNotEmpty()) {
            sb.append(",\"dirs\":[")
            req.dirs.forEachIndexed { i, d ->
                if (i > 0) sb.append(',')
                sb.append('"').append(SseParser.jsonEscape(d)).append('"')
            }
            sb.append(']')
        }
        if (req.timeoutS > 0) sb.append(",\"timeout_s\":").append(req.timeoutS)
        return sb.append('}').toString()
    }

    private fun extractInt(json: String, key: String): Int? {
        var i = json.indexOf("\"$key\"")
        if (i < 0) return null
        i += key.length + 2
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        var j = i
        if (j < json.length && json[j] == '-') j++
        while (j < json.length && json[j].isDigit()) j++
        if (j == i) return null
        return json.substring(i, j).toIntOrNull()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
