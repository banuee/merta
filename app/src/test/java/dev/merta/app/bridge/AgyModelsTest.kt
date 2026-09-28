package dev.merta.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyModelsTest {

    @Test
    fun `parses cli listing and groups variants`() {
        val raw = "Fetching available models...\n" +
            "gemini-3.8-flash-high Gemini 3.8 Flash (High)\n" +
            "gemini-3.8-flash-medium Gemini 3.8 Flash (Medium)\n" +
            "gemini-3.8-flash Gemini 3.8 Flash\n" +
            "claude-sonnet-4-6 Claude Sonnet 4.6\n"
        val models = AgyModels.parse(raw)
        assertEquals(2, models.size)
        assertEquals("gemini-3.8-flash", models[0].id)
        assertEquals("Gemini 3.8 Flash", models[0].displayName)
        assertEquals("claude-sonnet-4-6", models[1].id)
    }

    @Test
    fun `ignores garbage lines`() {
        val models = AgyModels.parse("Error: Please sign in\n\n")
        assertTrue(models.isEmpty())
    }

    @Test
    fun `fallback is not empty`() {
        assertTrue(AgyModels.FALLBACK.size >= 5)
        assertTrue(AgyModels.FALLBACK.any { it.id == "gemini-3.8-flash" })
    }
}
