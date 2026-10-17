package com.apex.workspace.core.git

import com.apex.workspace.core.model.GitFileEntry
import com.apex.workspace.core.model.GitFileStatus
import com.apex.workspace.core.model.GitStatusInfo

// 解析 git status --porcelain=v1 -b 输出（纯函数）。
// 分支行形如：## main...origin/main [ahead 1, behind 2]
//             ## No commits yet on main
//             ## HEAD (no branch)
internal object StatusParser {

    private val conflictCodes = setOf("UU", "AA", "DD", "AU", "UA", "DU", "UD")
    private val aheadRe = Regex("ahead (\\d+)")
    private val behindRe = Regex("behind (\\d+)")

    fun parse(text: String): GitStatusInfo {
        var branch: String? = null
        var ahead = 0
        var behind = 0
        var sawBranchLine = false
        val entries = mutableListOf<GitFileEntry>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            if (line.startsWith("## ")) {
                sawBranchLine = true
                val rest = line.removePrefix("## ").trim()
                if (rest.startsWith("No commits yet on ")) {
                    branch = rest.removePrefix("No commits yet on ").trim()
                    continue
                }
                if (rest.startsWith("HEAD")) {
                    branch = "HEAD"
                    continue
                }
                branch = rest.substringBefore("...").substringBefore(' ').trim().ifEmpty { null }
                ahead = aheadRe.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                behind = behindRe.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                continue
            }
            parseEntry(line)?.let { entries.add(it) }
        }
        // porcelain=v1 -b 的仓库输出必以 ## 分支行开头；没有分支行 = 非仓库输出
        return GitStatusInfo(sawBranchLine, branch, ahead, behind, entries)
    }

    private fun parseEntry(line: String): GitFileEntry? {
        if (line.length < 4) return null
        val xy = line.substring(0, 2)
        val pathPart = line.substring(3)
        if (pathPart.isEmpty()) return null
        var path = pathPart
        var oldPath: String? = null
        // 文件名可能自带字面量箭头：取最后一个 " -> " 为分隔（与 porcelain 生成规则一致）
        val arrow = path.lastIndexOf(" -> ")
        if (arrow >= 0) {
            oldPath = unquote(path.substring(0, arrow).trim())
            path = unquote(path.substring(arrow + 4).trim())
        } else {
            path = unquote(path)
        }
        val x = xy.getOrNull(0) ?: return null
        val y = xy.getOrNull(1) ?: return null
        return GitFileEntry(
            statusCode = xy,
            status = statusOf(xy, x, y),
            path = path,
            oldPath = oldPath,
            // 冲突条目无「已暂存」语义，统一 false；?? 恒未暂存
            staged = xy !in conflictCodes && x != ' ' && x != '?',
        )
    }

    private fun statusOf(xy: String, x: Char, y: Char): GitFileStatus = when {
        xy in conflictCodes -> GitFileStatus.CONFLICT
        x == '?' -> GitFileStatus.UNTRACKED
        map(x) != null -> map(x)!!
        map(y) != null -> map(y)!!
        else -> GitFileStatus.MODIFIED
    }

    private fun map(c: Char): GitFileStatus? = when (c) {
        'R' -> GitFileStatus.RENAMED
        'A' -> GitFileStatus.ADDED
        'D' -> GitFileStatus.DELETED
        'M' -> GitFileStatus.MODIFIED
        'C' -> GitFileStatus.COPIED
        else -> null
    }

    private fun unquote(p: String): String =
        if (p.length >= 2 && p.startsWith("\"") && p.endsWith("\"")) p.substring(1, p.length - 1) else p
}
