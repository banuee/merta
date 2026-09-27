package dev.merta.app.data.chat

import dev.merta.app.ui.chat.ChatMessage
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
                ChatMessage(
                    id = o.optLong("id", i.toLong()),
                    role = when (o.optString("role")) {
                        "user" -> ChatMessage.Role.USER
                        "assistant" -> ChatMessage.Role.ASSISTANT
                        else -> ChatMessage.Role.SYSTEM
                    },
                    text = o.optString("text", ""),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(id: String, title: String, messages: List<ChatMessage>) {
        try {
            chatsDir.mkdirs()
            val arr = JSONArray()
            for (m in messages) {
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("role", m.role.name.lowercase())
                        .put("text", m.text),
                )
            }
            val root = JSONObject()
                .put("id", id)
                .put("title", title)
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", arr)
            File(chatsDir, "$id.json").writeText(root.toString())
        } catch (_: Exception) {
            // История — не критичный путь, молча пропускаем.
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
