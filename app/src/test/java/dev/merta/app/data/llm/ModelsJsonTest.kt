package dev.merta.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsJsonTest {

    @Test
    fun `parses openrouter-style list`() {
        val json = """{"data":[
            {"id":"deepseek/deepseek-chat","name":"DeepSeek Chat"},
            {"id":"x-free","name":"X Free","pricing":{"prompt":"0"}}
        ]}"""
        val models = ModelsJson.parseModelsList(json)
        assertEquals(2, models.size)
        assertEquals("deepseek/deepseek-chat", models[0].id)
        assertEquals("DeepSeek Chat", models[0].displayName)
    }

    @Test
    fun `skips entries without id and tolerates braces in strings`() {
        val json = """{"data":[{"name":"noname"},{"id":"a","description":"curly { brace"}]}"""
        val models = ModelsJson.parseModelsList(json)
        assertEquals(1, models.size)
        assertEquals("a", models[0].id)
    }

    @Test
    fun `empty and garbage`() {
        assertTrue(ModelsJson.parseModelsList("{}").isEmpty())
        assertTrue(ModelsJson.parseModelsList("not json").isEmpty())
        assertTrue(ModelsJson.parseModelsList("""{"data":[]}""").isEmpty())
    }
}
