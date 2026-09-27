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
