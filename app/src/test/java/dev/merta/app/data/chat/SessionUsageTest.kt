package dev.merta.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUsageTest {

    @Test
    fun `accumulates turns`() {
        val u = SessionUsage().add(1000, 200, 50).add(500, 100)
        assertEquals(1500L, u.input)
        assertEquals(300L, u.output)
        assertEquals(50L, u.thinking)
        assertEquals(1850L, u.total())
    }

    @Test
    fun `negative usage ignored`() {
        val u = SessionUsage().add(-5, -1, -2)
        assertEquals(SessionUsage(), u)
    }

    @Test
    fun `cost by catalog pricing`() {
        // $3/1M in, $15/1M out: 1000 in + 200 out + 50 thinking.
        val u = SessionUsage().add(1000, 200, 50)
        assertEquals((1000 * 3.0 + 250 * 15.0) / 1_000_000, u.cost(3.0, 15.0), 1e-9)
        assertEquals(0.0, u.cost(0.0, 0.0), 0.0)
    }

    @Test
    fun `formatTokens compacts`() {
        assertEquals("999", SessionUsage.formatTokens(999))
        assertEquals("1k", SessionUsage.formatTokens(1000))
        assertEquals("12.3k", SessionUsage.formatTokens(12345))
        assertEquals("120k", SessionUsage.formatTokens(120000))
        assertEquals("1.5M", SessionUsage.formatTokens(1_500_000))
    }

    @Test
    fun `formatCost rounds`() {
        assertEquals("$0.031", SessionUsage.formatCost(0.0312))
        assertTrue(SessionUsage.formatCost(1.5).startsWith("$1.5"))
    }

    @Test
    fun `estimateTokens handles cyrillic and ascii`() {
        val eng = SessionUsage.estimateTokens("Hello world! This is a test.")
        assertTrue("English estimation should be positive: $eng", eng in 6L..10L)
        val rus = SessionUsage.estimateTokens("Привет, как дела? Напиши код на Kotlin")
        assertTrue("Russian estimation should be positive: $rus", rus in 12L..20L)
    }

    @Test
    fun `estimateFromMessages accounts for roles`() {
        val msgs = listOf(
            dev.merta.app.ui.chat.ChatMessage(1, dev.merta.app.ui.chat.ChatMessage.Role.USER, "Привет, мир!"),
            dev.merta.app.ui.chat.ChatMessage(2, dev.merta.app.ui.chat.ChatMessage.Role.ASSISTANT, "Привет! Чем могу помочь?"),
        )
        val est = SessionUsage.estimateFromMessages(msgs)
        assertTrue(est.input > 0)
        assertTrue(est.output > 0)
        assertEquals(0L, est.thinking)
        assertTrue(est.total() > 0)
        assertTrue(est.detailString().contains("вх"))
        assertTrue(est.detailString().contains("вых"))
    }
}
