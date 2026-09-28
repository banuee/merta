package dev.merta.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `splits code fence with lang`() {
        val blocks = parseMarkdownBlocks("hi\n```kotlin\nval x = 1\n```\nbye")
        assertEquals(3, blocks.size)
        val code = blocks[1] as MdBlock.Code
        assertEquals("kotlin", code.lang)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun `unclosed fence is code too`() {
        val blocks = parseMarkdownBlocks("text\n```\ncode here")
        assertEquals(2, blocks.size)
        assertTrue(blocks[1] is MdBlock.Code)
    }

    @Test
    fun `parses bold italic code spans`() {
        val spans = parseInlineSpans("a **b** c *d* e `f`")
        assertEquals("a ", spans[0].text)
        assertFalse(spans[0].bold)
        val b = spans.first { it.text == "b" }
        assertTrue(b.bold)
        assertFalse(b.italic)
        val d = spans.first { it.text == "d" }
        assertTrue(d.italic)
        val f = spans.first { it.text == "f" }
        assertTrue(f.code)
    }

    @Test
    fun `unclosed markers stay literal`() {
        val spans = parseInlineSpans("a **b and *c")
        assertEquals(1, spans.size)
        assertEquals("a **b and *c", spans[0].text)
        assertFalse(spans[0].bold)
        assertFalse(spans[0].italic)
    }
}
