package com.apex.workspace.explorer.preview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// 轻量 Markdown 渲染（零第三方库）：标题 / 段落 / 粗体 / 斜体 / 行内 code /
// 围栏代码块 / 列表（有序 + 无序，支持一级缩进）/ 引用 / 分隔线。
// 解析是纯函数（parseMarkdown），渲染消费 sealed MdBlock 列表。
// ─────────────────────────────────────────────────────────────────────────────

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeBlock(val lines: List<String>) : MdBlock
    data class ListItem(val marker: String, val indent: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

// 逐行解析（不做嵌套 AST —— 展示级预览足够）。
internal fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = text.split('\n')
    var index = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val trimmed = paragraph.toString().trim()
        if (trimmed.isNotEmpty()) blocks.add(MdBlock.Paragraph(trimmed))
        paragraph.clear()
    }

    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trimEnd()
        val fence = fenceMarker(line)
        when {
            fence != null -> {
                flushParagraph()
                val body = ArrayList<String>()
                index++
                while (index < lines.size && fenceMarker(lines[index]) != fence) {
                    body.add(lines[index])
                    index++
                }
                blocks.add(MdBlock.CodeBlock(body))
            }

            line.isBlank() -> flushParagraph()

            headingLevel(line) > 0 -> {
                flushParagraph()
                val level = headingLevel(line)
                blocks.add(MdBlock.Heading(level, line.drop(level + 1).trim()))
            }

            isRule(line) -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
            }

            isQuote(line) -> {
                flushParagraph()
                blocks.add(MdBlock.Quote(line.removePrefix(">").trim()))
            }

            listItemMarker(line) != null -> {
                flushParagraph()
                val (marker, indent) = listItemMarker(line)!!
                val content = stripListItem(line)
                // 合并后续缩进续行
                var j = index + 1
                val merged = StringBuilder(content)
                while (j < lines.size && lines[j].isNotBlank() && listItemMarker(lines[j]) == null) {
                    merged.append(' ').append(lines[j].trim())
                    j++
                }
                blocks.add(MdBlock.ListItem(marker, indent, merged.toString()))
                index = j - 1
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        index++
    }
    flushParagraph()
    return blocks
}

// ``` 或 ~~~ 围栏（允许尾部语言标注）。
private fun fenceMarker(line: String): String? {
    val trimmed = line.trim()
    return when {
        trimmed.startsWith("```") -> "```"
        trimmed.startsWith("~~~") -> "~~~"
        else -> null
    }
}

private fun headingLevel(line: String): Int {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("#")) return 0
    val level = trimmed.takeWhile { it == '#' }.length
    val rest = trimmed.drop(level)
    return if (level in 1..6 && rest.startsWith(" ")) level else 0
}

private fun isRule(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length < 3) return false
    return (trimmed.all { it == '-' } && !trimmed[0].isWhitespace()) ||
        trimmed.all { it == '*' } ||
        trimmed.all { it == '_' }
}

private fun isQuote(line: String): Boolean = line.trimStart().startsWith(">")

// 列表标记 → (显示标记, 缩进层级)；有序列表保持原始编号。
private fun listItemMarker(line: String): Pair<String, Int>? {
    val indent = line.takeWhile { it == ' ' }.length / 2
    val trimmed = line.trimStart()
    val bullet = when {
        trimmed.startsWith("- ") -> "\u2022"
        trimmed.startsWith("* ") -> "\u2022"
        trimmed.startsWith("+ ") -> "\u2022"
        else -> null
    }
    if (bullet != null) return bullet to indent
    val digits = trimmed.takeWhile { it.isDigit() }
    if (digits.isNotEmpty() && trimmed.drop(digits.length).startsWith(". ")) {
        return "$digits." to indent
    }
    return null
}

private fun stripListItem(line: String): String {
    val trimmed = line.trimStart()
    val bullet = trimmed.takeWhile { it == '-' || it == '*' || it == '+' }.length
    if (bullet in 1..2) return trimmed.drop(bullet + 1).trim()
    val digits = trimmed.takeWhile { it.isDigit() }.length
    if (digits > 0) return trimmed.drop(digits + 2).trim()
    return trimmed
}

// 行内解析：**粗体**、*斜体*、_斜体_、`行内 code`（secondaryContainer 底 + 等宽）。
private val inlinePattern = Regex("(\\*\\*.+?\\*\\*)|(\\*[^*\\s][^*]*?\\*)|(_[^_\\s][^_]*?_)|(`[^`]+?`)")

@Composable
internal fun renderInline(text: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.secondaryContainer
    return buildAnnotatedString {
        var cursor = 0
        for (match in inlinePattern.findAll(text)) {
            val start = match.range.first
            if (start > cursor) append(text.substring(cursor, start))
            val token = match.value
            when {
                token.startsWith("**") -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(token.removeSurrounding("**"))
                    pop()
                }

                token.startsWith("`") -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            background = codeBackground,
                        )
                    )
                    append(token.removeSurrounding("`"))
                    pop()
                }

                else -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(token.removeSurrounding("*").removeSurrounding("_"))
                    pop()
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

@Composable
internal fun MarkdownDocument(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = renderInline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )

                is MdBlock.Paragraph -> Text(
                    text = renderInline(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )

                is MdBlock.CodeBlock -> Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        if (block.lines.isEmpty()) {
                            Text(
                                text = " ",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            block.lines.forEach { codeLine ->
                                Text(
                                    text = codeLine.ifEmpty { " " },
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                is MdBlock.ListItem -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.indent * 12).dp, top = 2.dp, bottom = 2.dp),
                ) {
                    Text(
                        text = block.marker,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = renderInline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                is MdBlock.Quote -> Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = renderInline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                MdBlock.Rule -> HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
