package com.apex.workspace.core.git

import com.apex.workspace.core.model.DiffFile
import com.apex.workspace.core.model.DiffHunk
import com.apex.workspace.core.model.DiffLine
import com.apex.workspace.core.model.DiffLineType

// 解析 unified diff 输出（纯函数）。状态机：
// 文件头区（diff --git 之后、首个 @@ 之前）可出现 rename from/to、---、+++、Binary files；
// hunk 区（@@ 之后、下一个 diff --git 或 @@ 之前）逐行消费 +/减/空格/反斜杠。
// 超过 maxChars 截断：截断落在最后一个文件上，置 truncated。
internal object UnifiedDiffParser {

    private val hunkRe = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")

    fun parse(text: String, maxChars: Int = 200_000): List<DiffFile> {
        val cut = text.length > maxChars
        val src = if (cut) text.substring(0, maxChars) else text
        val files = mutableListOf<Builder>()
        var cur: Builder? = null
        var curHunk: HunkBuilder? = null

        val lines = src.split('\n')
        val count = if (lines.isNotEmpty() && lines.last() == "") lines.size - 1 else lines.size
        for (i in 0 until count) {
            val line = lines[i].trimEnd('\r')
            when {
                line.startsWith("diff --git ") -> {
                    curHunk = null
                    cur = Builder(parseHeaderPaths(line)).also { files.add(it) }
                }
                cur == null -> Unit // 文件头之前的杂行
                line.startsWith("@@ ") -> {
                    val head = parseHunkHeader(line) ?: continue
                    curHunk = HunkBuilder(head).also { cur!!.hunks.add(it) }
                }
                curHunk != null -> curHunk!!.consume(line)
                line.startsWith("rename from ") -> {
                    cur!!.renameFrom = unquote(line.removePrefix("rename from ").trim())
                }
                line.startsWith("rename to ") -> {
                    cur!!.renameTo = unquote(line.removePrefix("rename to ").trim())
                }
                line.startsWith("--- ") -> {
                    cur!!.oldSide = sidePath(line.removePrefix("--- ").trim())
                }
                line.startsWith("+++ ") -> {
                    cur!!.newSide = sidePath(line.removePrefix("+++ ").trim())
                }
                line.startsWith("Binary files ") -> {
                    cur!!.binary = true
                }
                line.startsWith("GIT binary patch") -> {
                    cur!!.binary = true
                }
                else -> Unit // index / similarity / mode 等头部行
            }
        }
        val out = files.map { it.build() }.toMutableList()
        if (cut && out.isNotEmpty()) {
            out[out.size - 1] = out.last().copy(truncated = true)
        }
        return out
    }

    // ── 文件头解析 ─────────────────────────────────────

    private fun parseHeaderPaths(line: String): HeaderPaths {
        val rest = line.removePrefix("diff --git ").trim()
        if (rest.startsWith("\"")) {
            val secondQuote = rest.indexOf('"', 1)
            if (secondQuote > 0) {
                val aRaw = rest.substring(1, secondQuote)
                val tail = rest.substring(secondQuote + 1).trim()
                if (tail.startsWith("\"")) {
                    val bRaw = tail.substring(1).removeSuffix("\"")
                    return HeaderPaths(sidePath(aRaw), sidePath(bRaw))
                }
            }
        }
        val idx = rest.indexOf(" b/")
        if (idx > 0) {
            return HeaderPaths(sidePath(rest.substring(0, idx)), sidePath(rest.substring(idx + 3)))
        }
        return HeaderPaths(null, null)
    }

    // 剥引号与 a/ b/ 前缀；/dev/null 归一为 null
    private fun sidePath(raw: String): String? {
        if (raw == "/dev/null") return null
        var p = unquote(raw)
        if (p.length >= 2 && (p[0] == 'a' || p[0] == 'b') && p[1] == '/') p = p.substring(2)
        return p
    }

    private fun parseHunkHeader(line: String): HunkHead? {
        val m = hunkRe.find(line) ?: return null
        val oldStart = m.groupValues[1].toIntOrNull() ?: return null
        val newStart = m.groupValues[3].toIntOrNull() ?: return null
        return HunkHead(
            oldStart = oldStart,
            oldLines = m.groupValues[2].toIntOrNull() ?: 1,
            newStart = newStart,
            newLines = m.groupValues[4].toIntOrNull() ?: 1,
            header = line,
        )
    }

    private fun unquote(p: String): String =
        if (p.length >= 2 && p.startsWith("\"") && p.endsWith("\"")) p.substring(1, p.length - 1) else p

    // ── 构建器 ─────────────────────────────────────────

    private data class HeaderPaths(val old: String?, val new: String?)

    private data class HunkHead(
        val oldStart: Int, val oldLines: Int,
        val newStart: Int, val newLines: Int,
        val header: String,
    )

    private class HunkBuilder(private val head: HunkHead) {
        private val lines = mutableListOf<DiffLine>()
        private var curOld = head.oldStart
        private var curNew = head.newStart
        var additions = 0
            private set
        var deletions = 0
            private set

        fun consume(line: String) {
            when {
                line == "\\" || line.startsWith("\\ No newline") ->
                    lines.add(DiffLine(DiffLineType.META, null, null, line))
                line.startsWith("+") -> {
                    lines.add(DiffLine(DiffLineType.ADD, null, curNew, line.substring(1)))
                    curNew++
                    additions++
                }
                line.startsWith("-") -> {
                    lines.add(DiffLine(DiffLineType.DELETE, curOld, null, line.substring(1)))
                    curOld++
                    deletions++
                }
                line.startsWith(" ") -> {
                    lines.add(DiffLine(DiffLineType.CONTEXT, curOld, curNew, line.substring(1)))
                    curOld++
                    curNew++
                }
                line.isEmpty() -> {
                    // 防御：部分产出方会吞掉空上下文行的前导空格
                    lines.add(DiffLine(DiffLineType.CONTEXT, curOld, curNew, ""))
                    curOld++
                    curNew++
                }
                else -> lines.add(DiffLine(DiffLineType.CONTEXT, curOld, curNew, line))
            }
        }

        fun build(): DiffHunk =
            DiffHunk(head.oldStart, head.oldLines, head.newStart, head.newLines, head.header, lines.toList())
    }

    private class Builder(private val header: HeaderPaths) {
        var renameFrom: String? = null
        var renameTo: String? = null
        var oldSide: String? = null
        var newSide: String? = null
        var binary = false
        val hunks = mutableListOf<HunkBuilder>()

        fun build(): DiffFile {
            val path = renameTo ?: newSide ?: oldSide ?: header.new ?: header.old ?: ""
            val oldPath = renameFrom ?: oldSide?.takeIf { it != path }
            val additions = hunks.sumOf { it.additions }
            val deletions = hunks.sumOf { it.deletions }
            return DiffFile(
                path = path,
                oldPath = oldPath,
                hunks = hunks.map { it.build() },
                binary = binary,
                additions = additions,
                deletions = deletions,
                truncated = false,
            )
        }
    }
}
