package dev.merta.app.data.chat

import dev.merta.app.ui.chat.Attachment
import dev.merta.app.ui.chat.ChatMessage
import dev.merta.app.ui.chat.ThoughtData
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * История чатов: по файлу на сессию в `merta/chats/<id>.json`.
 * Заголовок — локальная эвристика без LLM (как в десктопной merta).
 */
class SessionStore(private val chatsDir: File) {

    data class SessionMeta(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messageCount: Int,
    )

    fun list(): List<SessionMeta> {
        val files = try {
            chatsDir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
        } catch (_: Exception) {
            return emptyList()
        }
        return files.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                SessionMeta(
                    id = o.getString("id"),
                    title = o.optString("title", "Без названия"),
                    updatedAt = o.optLong("updatedAt", f.lastModified()),
                    messageCount = o.optJSONArray("messages")?.length() ?: 0,
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun load(id: String): List<ChatMessage> {
        val f = File(chatsDir, "$id.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("messages") ?: return emptyList()
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val role = when (o.optString("role")) {
                    "user" -> ChatMessage.Role.USER
                    "assistant" -> ChatMessage.Role.ASSISTANT
                    "thinking" -> ChatMessage.Role.THINKING
                    else -> ChatMessage.Role.SYSTEM
                }
                val thought = if (role == ChatMessage.Role.THINKING) {
                    ThoughtData(
                        active = false,
                        startedMs = 0L,
                        steps = o.optString("text", "").lines().filter { it.isNotBlank() },
                        reasoning = o.optString("reasoning", ""),
                        lastMs = o.optLong("thinkMs", -1L).takeIf { it >= 0 },
                    )
                } else {
                    null
                }
                val attArr = o.optJSONArray("attachments")
                val attachments = if (attArr != null && attArr.length() > 0) {
                    List(attArr.length()) { j ->
                        val ao = attArr.getJSONObject(j)
                        Attachment(
                            uri = ao.optString("uri", ""),
                            name = ao.optString("name", ""),
                            mimeType = ao.optString("mimeType", ""),
                            sizeBytes = ao.optLong("sizeBytes", 0L),
                            base64Data = ao.optString("base64Data", "").ifBlank { null },
                            localPath = ao.optString("localPath", "").ifBlank { null },
                        )
                    }
                } else {
                    emptyList()
                }
                ChatMessage(
                    id = o.optLong("id", i.toLong()),
                    role = role,
                    text = o.optString("text", ""),
                    thought = thought,
                    attachments = attachments,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun loadConversationId(id: String): String? {
        val f = File(chatsDir, "$id.json")
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText()).optString("conversationId", "").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun loadUsage(id: String): SessionUsage? {
        val f = File(chatsDir, "$id.json")
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val u = root.optJSONObject("usage") ?: return null
            SessionUsage(
                input = u.optLong("input", 0L),
                output = u.optLong("output", 0L),
                thinking = u.optLong("thinking", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(
        id: String,
        title: String,
        messages: List<ChatMessage>,
        conversationId: String? = null,
        usage: SessionUsage? = null,
    ) {
        try {
            chatsDir.mkdirs()
            val arr = JSONArray()
            for (m in messages) {
                val o = JSONObject()
                    .put("id", m.id)
                    .put("role", m.role.name.lowercase())
                    .put("text", if (m.role == ChatMessage.Role.THINKING) m.thought?.steps?.joinToString("\n") ?: "" else m.text)
                if (m.role == ChatMessage.Role.THINKING) {
                    o.put("thinkMs", m.thought?.lastMs ?: -1L)
                    o.put("reasoning", m.thought?.reasoning ?: "")
                }
                if (m.attachments.isNotEmpty()) {
                    val attArr = JSONArray()
                    for (att in m.attachments) {
                        val ao = JSONObject()
                            .put("uri", att.uri)
                            .put("name", att.name)
                            .put("mimeType", att.mimeType)
                            .put("sizeBytes", att.sizeBytes)
                        if (!att.localPath.isNullOrBlank()) ao.put("localPath", att.localPath)
                        if (!att.base64Data.isNullOrBlank() && att.base64Data.length < 500_000) {
                            ao.put("base64Data", att.base64Data)
                        }
                        attArr.put(ao)
                    }
                    o.put("attachments", attArr)
                }
                arr.put(o)
            }
            val root = JSONObject()
                .put("id", id)
                .put("title", title)
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", arr)
            if (!conversationId.isNullOrBlank()) {
                root.put("conversationId", conversationId)
            }
            val u = usage ?: SessionUsage.estimateFromMessages(messages)
            if (u.total() > 0) {
                val uObj = JSONObject()
                    .put("input", u.input)
                    .put("output", u.output)
                    .put("thinking", u.thinking)
                root.put("usage", uObj)
            }
            val target = File(chatsDir, "$id.json")
            val tmp = File(chatsDir, "$id.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            try {
                android.util.Log.w("SessionStore", "Не удалось сохранить сессию $id: ${e.message}")
            } catch (_: Throwable) {
                // JVM-тесты без Android mocks
            }
        }
    }

    fun delete(id: String) {
        try {
            File(chatsDir, "$id.json").delete()
        } catch (_: Exception) {
        }
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    companion object {
        private val GREETINGS = setOf(
            "привет", "здравствуй", "здравствуйте", "hello", "hi", "hey",
            "пожалуйста", "подскажи", "скажи", "расскажи",
        )

        /** Заголовок из первого сообщения пользователя: чистка мусора + ~6 слов. */
        fun titleFromPrompt(prompt: String): String {
            val words = prompt
                .replace(Regex("[\"«»„“'(){}\\[\\]№#*`>_]"), " ")
                .split(Regex("\\s+"))
                .map { it.trim(' ', ',', '.', '!', '?', ':', ';', '…') }
                .filter { it.isNotBlank() }
                .filterIndexed { i, w -> i > 1 || w.lowercase() !in GREETINGS }
            if (words.isEmpty()) return "Новый чат"
            val head = words.take(6).joinToString(" ")
            return if (head.length > 48) head.take(47) + "…" else head
        }
    }
}
