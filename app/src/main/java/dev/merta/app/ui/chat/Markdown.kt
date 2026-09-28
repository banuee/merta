package dev.merta.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.merta.app.ui.theme.LocalMetroScheme
import dev.merta.app.ui.theme.MetroDimens
import dev.merta.app.ui.theme.MetroFonts
import dev.merta.app.ui.theme.metroClickable

/**
 * Лёгкий markdown-рендер для ответов модели (без внешних зависимостей):
 * - ```блоки кода (с языком и кнопкой копирования),
 * - **жирный**, *курсив*, `код`,
 * - "- " списки, "# " заголовки.
 * Всё остальное — обычный текст построчно.
 */
internal sealed interface MdBlock {
    data class Para(val lines: List<String>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
}

internal data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

/** Разбить текст на абзацы и fencing-блоки кода. Чистая — JVM-тесты. */
internal fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()
    var inCode = false
    var lang = ""
    val code = StringBuilder()
    fun flushPara() {
        // Пустые строки-края не несём, внутренние — как есть (разрывы).
        val lines = para.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (lines.isNotEmpty()) out.add(MdBlock.Para(lines))
        para.clear()
    }
    for (raw in text.split('\n')) {
        val line = raw
        if (!inCode && line.trimStart().startsWith("```")) {
            flushPara()
            inCode = true
            lang = line.trimStart().removePrefix("```").trim().take(24)
            code.clear()
        } else if (inCode && line.trimStart().startsWith("```")) {
            inCode = false
            out.add(MdBlock.Code(lang, code.toString().trim('\n')))
        } else if (inCode) {
            if (code.isNotEmpty()) code.append('\n')
            code.append(line)
        } else {
            // Граница абзаца — пустая строка.
            if (line.isBlank() && para.any { it.isNotBlank() }) {
                flushPara()
            } else {
                para.add(line)
            }
        }
    }
    if (inCode) {
        // Незакрытый забор — тоже код, а не мусор.
        out.add(MdBlock.Code(lang, code.toString().trim('\n')))
    } else {
        flushPara()
    }
    return out
}

/** Инлайн-разбор строки: **жирный**, *курсив*, `код`. Чистая — JVM-тесты. */
internal fun parseInlineSpans(line: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    val cur = StringBuilder()
    var bold = false
    var italic = false
    var code = false
    fun flush() {
        if (cur.isNotEmpty()) {
            out.add(MdSpan(cur.toString(), bold, italic, code))
            cur.clear()
        }
    }
    var i = 0
    while (i < line.length) {
        when {
            code -> {
                if (line[i] == '`') {
                    flush()
                    code = false
                    i++
                } else {
                    cur.append(line[i])
                    i++
                }
            }
            line.startsWith("**", i) -> {
                flush()
                bold = !bold
                i += 2
            }
            line[i] == '*' -> {
                flush()
                italic = !italic
                i++
            }
            line[i] == '`' -> {
                flush()
                code = true
                i++
            }
            else -> {
                cur.append(line[i])
                i++
            }
        }
    }
    flush()
    // Незакрытые маркеры — считаем литералами: переразбираем без стилей.
    if (bold || italic || code) {
        return listOf(MdSpan(line))
    }
    return out
}

@Composable
fun MarkdownText(text: String, color: Color) {
    val scheme = LocalMetroScheme.current
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Para -> {
                    Column {
                        for (line in block.lines) {
                            MdLine(line, color, scheme.accent)
                        }
                    }
                }
                is MdBlock.Code -> CodeBlockView(lang = block.lang, code = block.code)
            }
        }
    }
}

@Composable
private fun MdLine(line: String, color: Color, accent: Color) {
    val scheme = LocalMetroScheme.current
    val trimmed = line.trimStart()
    when {
        // Заголовки "# ", "## ".
        trimmed.startsWith("# ") || trimmed.startsWith("## ") -> {
            val t = trimmed.dropWhile { it == '#' }.trimStart()
            Text(
                text = inlineAnnotated(t, color, accent),
                fontFamily = MetroFonts.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = color,
            )
        }
        // Списки "- ".
        trimmed.startsWith("- ") -> {
            Row {
                Text("• ", fontFamily = MetroFonts.text, fontSize = 14.sp, color = scheme.accent)
                Text(
                    text = inlineAnnotated(trimmed.removePrefix("- "), color, accent),
                    fontFamily = MetroFonts.text,
                    fontSize = 14.sp,
                    color = color,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        line.isBlank() -> Spacer(Modifier.padding(top = 2.dp))
        else -> {
            Text(
                text = inlineAnnotated(line, color, accent),
                fontFamily = MetroFonts.text,
                fontSize = 14.sp,
                color = color,
            )
        }
    }
}

@Composable
private fun inlineAnnotated(line: String, color: Color, accent: Color): AnnotatedString {
    val mono = MetroFonts.text
    return buildAnnotatedString {
        for (span in parseInlineSpans(line)) {
            val style = SpanStyle(
                color = if (span.code) accent else color,
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) mono else null,
            )
            withStyle(style) { append(span.text) }
        }
    }
}

@Composable
private fun CodeBlockView(lang: String, code: String) {
    val scheme = LocalMetroScheme.current
    val clip = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = lang.ifBlank { "код" },
                fontFamily = MetroFonts.text,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = scheme.textDim,
                modifier = Modifier.weight(1f),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(scheme.glassHover)
                    .metroClickable(targetScale = 0.93f) { clip.setText(AnnotatedString(code)) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text("Копировать", fontFamily = MetroFonts.text, fontSize = 12.sp, color = scheme.text)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        ) {
            Text(
                text = code,
                fontFamily = MetroFonts.text,
                fontSize = 13.sp,
                color = scheme.text,
                softWrap = false,
            )
        }
    }
}
