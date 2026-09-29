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

    @Test
    fun `parses reasoning support`() {
        val json = """{"data":[
            {"id":"a","supported_parameters":["tools","reasoning"]},
            {"id":"b","supported_parameters":["tools","temperature"]},
            {"id":"c"}
        ]}"""
        val models = ModelsJson.parseModelsList(json)
        assertEquals(3, models.size)
        assertTrue(models[0].reasoningSupported)
        assertTrue(!models[1].reasoningSupported)
        assertTrue(models[2].reasoningSupported)
    }

    @Test
    fun `parses openrouter pricing per 1M`() {
        val json = """{"data":[
            {"id":"x/y","name":"Y","pricing":{"prompt":"0.000001","completion":"0.0000025","request":"0"}},
            {"id":"free","name":"F","pricing":{"prompt":"0","completion":"0"}},
            {"id":"noprice","name":"N"}
        ]}"""
        val models = ModelsJson.parseModelsList(json)
        assertEquals(3, models.size)
        assertEquals(1.0, models[0].promptPer1M, 1e-9)
        assertEquals(2.5, models[0].completionPer1M, 1e-9)
        assertEquals(0.0, models[1].promptPer1M, 0.0)
        assertEquals(0.0, models[2].completionPer1M, 0.0)
    }
}
