package dev.merta.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaJsonTest {

    // Урезанный живой вывод `agy -p /usage --output-format json` (1.2.13).
    private val sample = """{"conversation_id":"","status":"SUCCESS","response":"Gemini Models\tWeekly Limit Remaining\t67%","command":{"name":"usage","data":{"description":"d","groups":[{"name":"Gemini Models","description":"g","buckets":[{"id":"gemini-weekly","name":"Weekly Limit Remaining","description":"x","window":"weekly","remaining_fraction":0.6651902794837952,"reset_time":"2026-10-05T11:20:04Z"},{"id":"gemini-5h","name":"Five Hour Limit Remaining","window":"5h","remaining_fraction":0.673670709313482,"reset_time":"2026-09-29T15:57:34Z"}]},{"name":"Claude and GPT models","buckets":[{"id":"3p-weekly","name":"Weekly Limit Remaining","window":"weekly","remaining_fraction":1,"reset_time":"2026-10-06T14:46:18Z"}]}]}}}"""

    @Test
    fun `parses groups and buckets`() {
        val groups = QuotaJson.parse(sample)
        assertEquals(2, groups.size)
        assertEquals("Gemini Models", groups[0].name)
        assertEquals(2, groups[0].buckets.size)
        val weekly = groups[0].buckets[0]
        assertEquals("gemini-weekly", weekly.id)
        assertEquals("weekly", weekly.window)
        assertEquals(0.6651902794837952, weekly.remaining, 1e-12)
        assertEquals("2026-10-05T11:20:04Z", weekly.resetTime)
        assertEquals(1.0, groups[1].buckets[0].remaining, 1e-12)
    }

    @Test
    fun `garbage gives empty`() {
        assertTrue(QuotaJson.parse("").isEmpty())
        assertTrue(QuotaJson.parse("""{"status":"ERROR"}""").isEmpty())
    }

    @Test
    fun `resetIn formats countdown`() {
        // reset 2026-10-05T11:20:04Z, now — на 5д 20ч раньше.
        val reset = QuotaJson.parseIsoUtc("2026-10-05T11:20:04Z")!!
        assertEquals("через 5д 20ч", QuotaJson.resetIn("2026-10-05T11:20:04Z", reset - (5 * 24 * 3600 + 20 * 3600) * 1000L))
        assertEquals("через 1ч 11м", QuotaJson.resetIn("2026-09-29T15:57:34Z", QuotaJson.parseIsoUtc("2026-09-29T15:57:34Z")!! - (71 * 60 * 1000L)))
        assertEquals("скоро", QuotaJson.resetIn("2026-09-29T15:57:34Z", reset))
        assertEquals("", QuotaJson.resetIn("мусор", 0L))
    }
}
