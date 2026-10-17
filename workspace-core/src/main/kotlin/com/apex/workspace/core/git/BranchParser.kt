package com.apex.workspace.core.git

import com.apex.workspace.core.model.BranchEntry

// 解析 git for-each-ref --sort=-committerdate
// --format=%(HEAD)%00%(refname:short)%00%(committerdate:unix) refs/heads refs/remotes 输出。
// 说明：refname:short 不携带命名空间；本地分支惯例为单段名（main / dev），
// 远程跟踪分支恒为多段名（origin / main 形态），故以「包含斜杠」判 isRemote——
// 本地若存在多段名风格（如 feature 登录类分支）会被误判为远程，已在 worklog 记录该限制。
internal object BranchParser {

    fun parse(text: String): List<BranchEntry> {
        val out = mutableListOf<BranchEntry>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val parts = line.split('\u0000')
            if (parts.size < 3) continue
            val name = parts[1]
            if (name.isBlank()) continue
            val ts = parts[2].trim().toLongOrNull()
            out.add(
                BranchEntry(
                    name = name,
                    current = parts[0] == "*",
                    isRemote = name.contains('/'),
                    lastCommitMs = ts?.let { it * 1000 },
                )
            )
        }
        return out
    }
}
