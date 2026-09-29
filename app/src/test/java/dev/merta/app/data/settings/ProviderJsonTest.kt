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
            Provider("agy", "Agy", "http://127.0.0.1:18080", "", Provider.Kind.AGY),
        )
        val back = ProviderJson.providersFromJson(ProviderJson.providersToJson(list))
        assertEquals(list, back)
    }

    @Test
    fun `old entries default to openai kind`() {
        val back = ProviderJson.providersFromJson(
            """[{"id":"a","name":"A","baseUrl":"https://x/v1","apiKey":"k"}]""",
        )
        assertEquals(1, back.size)
        assertEquals(Provider.Kind.OPENAI, back[0].kind)
        assertTrue(back[0].hasKey)
    }

    @Test
    fun `agy needs no key`() {
        val agy = Provider("agy", "Agy", "http://127.0.0.1:18080", "", Provider.Kind.AGY)
        assertTrue(agy.hasKey)
        assertTrue(agy.isAgy)
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

    @Test
    fun `agy with token roundtrips correctly`() {
        val agy = Provider("agy", "Agy", "http://127.0.0.1:18080", "secret-token-123", Provider.Kind.AGY)
        val list = listOf(agy)
        val back = ProviderJson.providersFromJson(ProviderJson.providersToJson(list))
        assertEquals(1, back.size)
        assertEquals("secret-token-123", back[0].apiKey)
        assertEquals(Provider.Kind.AGY, back[0].kind)
        assertTrue(back[0].hasKey)
    }

    @Test
    fun `editing provider preserves identity`() {
        val original = Provider("agy", "Agy", "http://127.0.0.1:18080", "", Provider.Kind.AGY)
        val edited = original.copy(name = "Local Agy", baseUrl = "http://127.0.0.1:18081", apiKey = "tok123")
        val back = ProviderJson.providersFromJson(ProviderJson.providersToJson(listOf(edited)))[0]
        assertEquals("agy", back.id)
        assertEquals("Local Agy", back.name)
        assertEquals("http://127.0.0.1:18081", back.baseUrl)
        assertEquals("tok123", back.apiKey)
        assertEquals(Provider.Kind.AGY, back.kind)
    }
}
