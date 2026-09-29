package dev.merta.app.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFilesTest {

    @Test
    fun `default prompt has no transport wording`() {
        assertTrue(AgentFiles.DEFAULT_SYSTEM.contains("install_apk"))
        assertFalse(AgentFiles.DEFAULT_SYSTEM.contains("через Shizuku"))
    }

    @Test
    fun `migrate fixes stale lines only`() {
        val stale = "Ты — Merta.\n" +
            "- run_command {\"command\"} — shell (sh -c, 60с). Тоже с подтверждением.\n" +
            "- install_apk {\"path\"} — установить APK через Shizuku (только /sdcard/…). С подтверждением.\n" +
            "- tap_screen — тап/свайп по экрану через Shizuku. С подтверждением.\n" +
            "Мой кастомный текст с правилами."
        val out = AgentFiles.migrateSystemPrompt(stale)
        assertTrue(out.contains("shell телефона с правами ADB (sh -c, 60с)"))
        assertTrue(out.contains("установить APK (только /sdcard/…)"))
        assertTrue(out.contains("тап/свайп по экрану. С подтверждением."))
        assertTrue(out.contains("Мой кастомный текст с правилами."))
        assertFalse(out.contains("через Shizuku"))
    }

    @Test
    fun `migrate leaves fresh text untouched`() {
        assertEquals(AgentFiles.DEFAULT_SYSTEM, AgentFiles.migrateSystemPrompt(AgentFiles.DEFAULT_SYSTEM))
        val custom = "Свой промт без дефолтных строк."
        assertEquals(custom, AgentFiles.migrateSystemPrompt(custom))
    }
}
