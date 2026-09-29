package dev.merta.app.data.chat

import dev.merta.app.data.agent.AgentFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {

    @Test
    fun `strips greetings and trims to six words`() {
        assertEquals(
            "как собрать релизный apk",
            SessionStore.titleFromPrompt("Привет, подскажи как собрать релизный apk?"),
        )
    }

    @Test
    fun `long prompt gets ellipsis`() {
        val t = SessionStore.titleFromPrompt("а".repeat(60) + " хвост который не влезет")
        assertEquals(48, t.length)
    }

    @Test
    fun `empty prompt`() {
        assertEquals("Новый чат", SessionStore.titleFromPrompt("  ...  "))
    }

    @Test
    fun `saves and loads conversationId`() {
        val dir = java.nio.file.Files.createTempDirectory("session-test").toFile()
        try {
            val store = SessionStore(dir)
            val msg = listOf(
                dev.merta.app.ui.chat.ChatMessage(1, dev.merta.app.ui.chat.ChatMessage.Role.USER, "Привет"),
            )
            store.save("s1", "Заголовок", msg, conversationId = "conv-12345")
            assertEquals("conv-12345", store.loadConversationId("s1"))
            val loaded = store.load("s1")
            assertEquals(1, loaded.size)
            assertEquals("Привет", loaded[0].text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `saves and loads usage`() {
        val dir = java.nio.file.Files.createTempDirectory("session-test").toFile()
        try {
            val store = SessionStore(dir)
            val msg = listOf(
                dev.merta.app.ui.chat.ChatMessage(1, dev.merta.app.ui.chat.ChatMessage.Role.USER, "Привет"),
            )
            val u = SessionUsage(120, 45, 10)
            store.save("s2", "Заголовок", msg, usage = u)
            val loadedU = store.loadUsage("s2")
            assertEquals(120L, loadedU?.input)
            assertEquals(45L, loadedU?.output)
            assertEquals(10L, loadedU?.thinking)
        } finally {
            dir.deleteRecursively()
        }
    }
}

class FrontmatterTest {

    @Test
    fun `parses name and description`() {
        val md = "---\nname: review\ndescription: \"Ревью диффа\"\n---\n# тело"
        assertEquals("review" to "Ревью диффа", AgentFiles.parseFrontmatter(md))
    }

    @Test
    fun `no frontmatter`() {
        assertEquals("" to "", AgentFiles.parseFrontmatter("# просто текст"))
    }
}

class ThinkFormatTest {

    @Test
    fun formats() {
        assertEquals("0с", dev.merta.app.ui.chat.formatThinkMs(200))
        assertEquals("12с", dev.merta.app.ui.chat.formatThinkMs(12345))
        assertEquals("1м 05с", dev.merta.app.ui.chat.formatThinkMs(65000))
    }
}
