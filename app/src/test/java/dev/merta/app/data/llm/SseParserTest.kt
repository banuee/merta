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
}
