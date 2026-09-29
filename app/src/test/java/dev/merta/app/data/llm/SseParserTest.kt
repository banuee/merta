package dev.merta.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun `extracts simple delta`() {
        val chunk = """{"id":"1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"}}]}"""
        assertEquals("Hello", SseParser.extractDelta(chunk))
    }

    @Test
    fun `ignores role-only chunk`() {
        val chunk = """{"choices":[{"delta":{"role":"assistant"}}]}"""
        assertNull(SseParser.extractDelta(chunk))
    }

    @Test
    fun `ignores usage chunk without delta`() {
        val chunk = """{"choices":[],"usage":{"prompt_tokens":5}}"""
        assertNull(SseParser.extractDelta(chunk))
    }

    @Test
    fun `extracts usage tokens`() {
        val chunk = """{"choices":[],"usage":{"prompt_tokens":12242,"completion_tokens":131,"total_tokens":12373}}"""
        assertEquals(12242L to 131L, SseParser.extractUsage(chunk))
        assertEquals(null, SseParser.extractUsage("""{"choices":[{"delta":{"content":"hi"}}]}"""))
    }

    @Test
    fun `unescapes quotes newlines and unicode`() {
        val chunk = "{\"choices\":[{\"delta\":{\"content\":\"a\\\"b\\nc\\u0041\"}}]}"
        assertEquals("a\"b\ncA", SseParser.extractDelta(chunk))
    }

    @Test
    fun `returns null for truncated string`() {
        assertNull(SseParser.extractDelta("{\"choices\":[{\"delta\":{\"content\":\"abc}}]}"))
    }

    @Test
    fun `detects DONE with whitespace`() {
        assertTrue(SseParser.isDone("[DONE]"))
        assertTrue(SseParser.isDone("  [DONE]  "))
        assertFalse(SseParser.isDone("[DONE] extra"))
        assertFalse(SseParser.isDone("{}"))
    }

    @Test
    fun `extracts error message`() {
        val err = """{"error":{"message":"Invalid API key","code":401}}"""
        assertEquals("Invalid API key", SseParser.extractErrorMessage(err))
    }

    @Test
    fun `jsonEscape roundtrips through extractor`() {
        val raw = "say \"hi\"\nnew line\ttab\\slash"
        val json = "{\"content\":\"" + SseParser.jsonEscape(raw) + "\"}"
        assertEquals(raw, SseParser.extractStringAfterKey(json, "content", 0))
    }

    @Test
    fun `extracts direct reasoning string`() {
        val chunk = """{"choices":[{"delta":{"reasoning":"Let me think","content":"Hi"}}]}"""
        assertEquals("Let me think", SseParser.extractReasoning(chunk))
    }

    @Test
    fun `extracts reasoning_details text and summary`() {
        val chunk = """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"step one"},{"type":"reasoning.summary","summary":"sum"}]}}]}"""
        assertEquals("step onesum", SseParser.extractReasoning(chunk))
    }

    @Test
    fun `dedupes openrouter double reasoning`() {
        // OpenRouter шлёт один текст и в reasoning, и в reasoning_details.
        val chunk = """{"choices":[{"delta":{"reasoning":"think","reasoning_details":[{"type":"reasoning.text","text":"think"}]}}]}"""
        assertEquals("think", SseParser.extractReasoning(chunk))
    }

    @Test
    fun `extracts reasoning_content style`() {
        val chunk = """{"choices":[{"delta":{"reasoning_content":"deep"}}]}"""
        assertEquals("deep", SseParser.extractReasoning(chunk))
    }

    @Test
    fun `keeps distinct details in document order`() {
        val chunk = """{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.summary","summary":"S"},{"type":"reasoning.text","text":"T"}]}}]}"""
        assertEquals("ST", SseParser.extractReasoning(chunk))
    }

    @Test
    fun `returns null when no reasoning`() {
        assertNull(SseParser.extractReasoning("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertNull(SseParser.extractReasoning("""{"choices":[]}"""))
    }
}
