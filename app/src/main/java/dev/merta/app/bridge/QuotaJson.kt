package dev.merta.app.bridge

import dev.merta.app.data.llm.SseParser

/**
 * Лимиты agy (`agy -p /usage --output-format json`, сырой вывод — в `output`
 * демона). Группы (`Gemini Models`, `Claude and GPT models`), в каждой
 * бакеты (weekly/5h) с `remaining_fraction` и `reset_time` (ISO UTC).
 * Чистый — покрыт JVM-тестами.
 */
data class QuotaBucket(
    val id: String,
    val name: String,
    val window: String,
    /** Остаток 0..1. */
    val remaining: Double,
    val resetTime: String,
)

data class QuotaGroup(val name: String, val buckets: List<QuotaBucket>)

object QuotaJson {

    fun parse(output: String): List<QuotaGroup> {
        val gi = output.indexOf("\"groups\"")
        if (gi < 0) return emptyList()
        val arr = output.indexOf('[', gi + 8)
        if (arr < 0) return emptyList()
        val out = mutableListOf<QuotaGroup>()
        var i = arr + 1
        while (i < output.length && out.size < 10) {
            while (i < output.length && (output[i].isWhitespace() || output[i] == ',')) i++
            if (i >= output.length || output[i] == ']') break
            if (output[i] != '{') {
                i++
                continue
            }
            val end = matchObject(output, i) ?: break
            val obj = output.substring(i, end)
            val name = SseParser.extractStringAfterKey(obj, "name", 0).orEmpty()
            out.add(QuotaGroup(name, parseBuckets(obj)))
            i = end
        }
        return out
    }

    private fun parseBuckets(groupObj: String): List<QuotaBucket> {
        val bi = groupObj.indexOf("\"buckets\"")
        if (bi < 0) return emptyList()
        val arr = groupObj.indexOf('[', bi + 9)
        if (arr < 0) return emptyList()
        val out = mutableListOf<QuotaBucket>()
        var i = arr + 1
        while (i < groupObj.length && out.size < 10) {
            while (i < groupObj.length && (groupObj[i].isWhitespace() || groupObj[i] == ',')) i++
            if (i >= groupObj.length || groupObj[i] == ']') break
            if (groupObj[i] != '{') {
                i++
                continue
            }
            val end = matchObject(groupObj, i) ?: break
            val o = groupObj.substring(i, end)
            out.add(
                QuotaBucket(
                    id = SseParser.extractStringAfterKey(o, "id", 0).orEmpty(),
                    name = SseParser.extractStringAfterKey(o, "name", 0).orEmpty(),
                    window = SseParser.extractStringAfterKey(o, "window", 0).orEmpty(),
                    remaining = extractDouble(o, "remaining_fraction") ?: 0.0,
                    resetTime = SseParser.extractStringAfterKey(o, "reset_time", 0).orEmpty(),
                ),
            )
            i = end
        }
        return out
    }

    private fun extractDouble(json: String, key: String): Double? {
        var i = json.indexOf("\"$key\"")
        if (i < 0) return null
        i += key.length + 2
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') return null
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        var j = i
        while (j < json.length && (json[j].isDigit() || json[j] == '.' || json[j] == '-' || json[j] == 'e' || json[j] == 'E' || json[j] == '+')) j++
        if (j == i) return null
        return json.substring(i, j).toDoubleOrNull()
    }

    private fun matchObject(json: String, openIdx: Int): Int? {
        var depth = 0
        var inStr = false
        var i = openIdx
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Группа лимитов под текущую модель: claude/gpt-* → группа Claude,
     * остальное → группа Gemini. Нет подходящей — первая (старое поведение).
     * Чистая — покрыта JVM-тестами.
     */
    fun groupForModel(groups: List<QuotaGroup>, modelId: String): QuotaGroup? {
        if (groups.isEmpty()) return null
        val m = modelId.lowercase()
        val wantClaude = "claude" in m || "gpt" in m
        val byName = groups.firstOrNull { g ->
            val n = g.name.lowercase()
            if (wantClaude) "claude" in n else "gemini" in n
        }
        return byName ?: groups.first()
    }
    fun resetIn(resetTime: String, nowMs: Long = System.currentTimeMillis()): String {
        val reset = parseIsoUtc(resetTime) ?: return ""
        val diff = reset - nowMs
        if (diff <= 0) return "скоро"
        val mins = diff / 60000
        val days = mins / 1440
        val hours = (mins % 1440) / 60
        val mm = mins % 60
        return when {
            days > 0 -> "через ${days}д ${hours}ч"
            hours > 0 -> "через ${hours}ч ${mm}м"
            else -> "через ${mm}м"
        }
    }

    /** ISO UTC `2026-10-05T11:20:04Z` (и с миллисекундами) → epoch ms. */
    fun parseIsoUtc(s: String): Long? {
        val t = s.trim()
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""").find(t) ?: return null
        val (y, mo, d, h, mi, sec) = m.destructured
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), sec.toInt())
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
