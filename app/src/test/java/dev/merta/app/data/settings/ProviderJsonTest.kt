package dev.merta.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderJsonTest {

    @Test
    fun `roundtrips providers`() {
        val list = listOf(
            Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "sk-x"),
            Provider("my", "My \"LLM\" \\box", "https://x/v1", ""),
        )
        val back = ProviderJson.providersFromJson(ProviderJson.providersToJson(list))
        assertEquals(list, back)
    }

    @Test
    fun `garbage gives empty`() {
        assertTrue(ProviderJson.providersFromJson("nope").isEmpty())
        assertTrue(ProviderJson.providersFromJson("[]").isEmpty())
    }

    @Test
    fun `roundtrips models cache`() {
        val cache = mapOf("or" to mapOf("a/b" to "B Display", "c" to ""))
        val back = ProviderJson.modelsCacheFromJson(ProviderJson.modelsCacheToJson(cache))
        assertEquals(cache, back)
    }
}
