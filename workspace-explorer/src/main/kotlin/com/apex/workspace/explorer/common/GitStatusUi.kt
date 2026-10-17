package com.apex.workspace.explorer.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.workspace.core.model.GitFileEntry
import com.apex.workspace.core.model.GitFileStatus

// ─────────────────────────────────────────────────────────────────────────────
// Git 状态徽标 / 状态点 / 目录聚合。
// StatusBadge 逐字符镜像宿主 CodeWorkspacePanel 的色语义：
// A/N → primaryContainer、D → errorContainer、U → tertiaryContainer、
// 其余（M/R/C…）→ surfaceVariant；前景取对应 on*Container / onSurfaceVariant。
// ─────────────────────────────────────────────────────────────────────────────

// 归并状态 → 徽标短码（与宿主面板口径一致：未跟踪显示 N）。
internal fun statusShortCode(status: GitFileStatus): String = when (status) {
    GitFileStatus.ADDED -> "A"
    GitFileStatus.UNTRACKED -> "N"
    GitFileStatus.DELETED -> "D"
    GitFileStatus.CONFLICT -> "U"
    GitFileStatus.RENAMED -> "R"
    GitFileStatus.COPIED -> "C"
    GitFileStatus.MODIFIED -> "M"
}

// 文件树右侧 6dp 状态点颜色：ADDED→primary、MODIFIED→tertiary、
// DELETED→error、UNTRACKED→outline、其余→secondary。
@Composable
internal fun statusDotColor(status: GitFileStatus): Color = when (status) {
    GitFileStatus.ADDED -> MaterialTheme.colorScheme.primary
    GitFileStatus.MODIFIED -> MaterialTheme.colorScheme.tertiary
    GitFileStatus.DELETED -> MaterialTheme.colorScheme.error
    GitFileStatus.UNTRACKED -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.secondary
}

// 状态徽标：22x20dp 圆角容器 + 等宽短码（镜像 CodeWorkspacePanel.StatusBadge）。
@Composable
internal fun StatusBadge(code: String, modifier: Modifier = Modifier) {
    val (bg, fg) = when (code) {
        "A", "N" -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        "D" -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        "U" -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        contentColor = fg,
        modifier = modifier.size(width = 22.dp, height = 20.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = code,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// 目录级聚合：某目录（含子树）下变更文件数 + 最强状态（决定状态点颜色）。
internal data class DirGitInfo(val count: Int, val status: GitFileStatus?)

// 变更条目 → 各祖先目录的计数与最强状态（供文件树目录行尾挂徽标/状态点）。
internal fun deriveDirGit(entries: List<GitFileEntry>): Map<String, DirGitInfo> {
    if (entries.isEmpty()) return emptyMap()
    val counts = HashMap<String, Int>()
    val best = HashMap<String, GitFileStatus>()
    for (entry in entries) {
        var dir = entry.path.substringBeforeLast('/', "")
        while (true) {
            counts[dir] = (counts[dir] ?: 0) + 1
            mergeStrongest(best, dir, entry.status)
            if (dir.isEmpty()) break
            val parent = dir.substringBeforeLast('/', "")
            if (parent == dir) break
            dir = parent
        }
    }
    val out = HashMap<String, DirGitInfo>(counts.size)
    for ((dir, count) in counts) {
        out[dir] = DirGitInfo(count, best[dir])
    }
    return out
}

// 最强状态优先级：DELETED > ADDED > 修改族 > UNTRACKED。
private fun mergeStrongest(map: MutableMap<String, GitFileStatus>, dir: String, status: GitFileStatus) {
    val current = map[dir]
    if (current == null || strength(status) > strength(current)) {
        map[dir] = status
    }
}

private fun strength(status: GitFileStatus): Int = when (status) {
    GitFileStatus.DELETED -> 4
    GitFileStatus.ADDED -> 3
    GitFileStatus.UNTRACKED -> 1
    else -> 2
}
