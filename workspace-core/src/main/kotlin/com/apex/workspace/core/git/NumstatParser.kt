package com.apex.workspace.core.git

// 解析 git diff --numstat -M 输出（纯函数）。
// 行格式：adds TAB dels TAB path；「-	-」表示二进制；
// rename 形如「old => new」或花括号「prefix{old => new}suffix」。
internal object NumstatParser {

    internal data class Record(
        val path: String,
        val oldPath: String?,
        val additions: Int?,
        val deletions: Int?,
    )

    fun parse(text: String): List<Record> {
        val out = mutableListOf<Record>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val adds = parts[0].toIntOrNull() // "-" 走 toIntOrNull 得 null，二进制语义
            val dels = parts[1].toIntOrNull()
            if (parts[0] != "-" && adds == null) continue
            if (parts[1] != "-" && dels == null) continue
            val token = parts.drop(2).joinToString("\t")
            val (path, oldPath) = splitRename(token)
            out.add(Record(path, oldPath, adds, dels))
        }
        return out
    }

    // rename 两种形态：old => new（全路径）与 prefix{old => new}suffix（花括号）
    private fun splitRename(token: String): Pair<String, String?> {
        val arrow = token.indexOf(" => ")
        if (arrow < 0) return unquote(token) to null
        val open = token.indexOf('{')
        val close = token.indexOf('}', arrow)
        if (open in 0 until arrow && close > arrow) {
            val prefix = token.substring(0, open)
            val oldSeg = token.substring(open + 1, arrow)
            val newSeg = token.substring(arrow + 4, close)
            val suffix = token.substring(close + 1)
            val oldPath = prefix + oldSeg + suffix
            val path = prefix + newSeg + suffix
            return unquote(path) to unquote(oldPath)
        }
        val old = token.substring(0, arrow)
        val new = token.substring(arrow + 4)
        return unquote(new) to unquote(old)
    }

    private fun unquote(p: String): String =
        if (p.length >= 2 && p.startsWith("\"") && p.endsWith("\"")) p.substring(1, p.length - 1) else p
}
