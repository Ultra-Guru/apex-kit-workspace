package com.apex.workspace.core.git

import com.apex.workspace.core.model.CommitEntry

// 解析 git log --pretty=%H%x1f%h%x1f%an%x1f%at%x1f%s%x1f%b%x1e 输出（纯函数）。
// 记录以 unit-separator 分隔，字段以 record-separator 分隔；dateMs = at 秒乘 1000。
internal object LogParser {

    private const val RS = '\u001e'
    private const val US = '\u001f'

    fun parse(text: String): List<CommitEntry> {
        val out = mutableListOf<CommitEntry>()
        for (chunk in text.split(RS)) {
            val record = chunk.trimStart('\n', '\r')
            if (record.isEmpty()) continue
            val fields = record.split(US)
            if (fields.size < 6) continue
            val at = fields[3].trim().toLongOrNull() ?: continue
            val body = fields[5].trimEnd('\n', '\r')
            out.add(
                CommitEntry(
                    sha = fields[0],
                    shortSha = fields[1],
                    author = fields[2],
                    dateMs = at * 1000,
                    subject = fields[4],
                    body = body.ifBlank { null },
                )
            )
        }
        return out
    }
}
