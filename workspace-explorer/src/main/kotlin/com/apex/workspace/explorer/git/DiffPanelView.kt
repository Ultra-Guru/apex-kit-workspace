package com.apex.workspace.explorer.git

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.workspace.core.model.DiffFile
import com.apex.workspace.core.model.DiffHunk
import com.apex.workspace.core.model.DiffLine
import com.apex.workspace.core.model.DiffLineType
import com.apex.workspace.explorer.R

// ─────────────────────────────────────────────────────────────────────────────
// Diff 面板：头部（路径 + 增删徽标 + BIN chip + 关闭）+ hunk 卡片懒列表。
// 增/删行配色逐值镜像宿主 CodeDiffView：
//   ADD    → 背景 Color(0xFF059669).copy(alpha 0.10f)，符号色 Color(0xFF059669)
//   DELETE → 背景 colorScheme.error.copy(alpha 0.10f)，符号色 error
// 行号列 32dp x2 + 符号列 12dp；正文等宽 10sp，hunk 内横向滚动，整体封顶 360dp。
// ─────────────────────────────────────────────────────────────────────────────

// 镜像 CodeDiffView 的增行绿（非蓝靛系静态色，深浅主题各自可读的柔和 alpha）。
private val DiffAddGreen = Color(0xFF059669)

@Composable
internal fun DiffPanelView(
    diff: DiffFile,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            DiffHeader(diff = diff, onClose = onClose)
            if (diff.binary) {
                Text(
                    text = stringResource(R.string.workspace_changes_binary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else if (diff.hunks.isEmpty()) {
                Text(
                    text = stringResource(R.string.workspace_git_clean),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(diff.hunks) { hunk ->
                        DiffHunkCard(hunk = hunk)
                    }
                    if (diff.truncated) {
                        item(key = "diff-truncated") {
                            Text(
                                text = stringResource(R.string.workspace_diff_truncated),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffHeader(diff: DiffFile, onClose: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = diff.path,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "+${diff.additions}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = "-${diff.deletions}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
        )
        if (diff.binary) {
            BinChip()
        }
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.workspace_git_diff_close_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BinChip() {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = "BIN",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

// hunk 卡片：头行（@@ … @@）+ 行级渲染（镜像 CodeDiffView 的 hunk Surface 语言）。
@Composable
private fun DiffHunkCard(hunk: DiffHunk) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Column {
            Text(
                text = hunk.header,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Column(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                hunk.lines.forEach { line ->
                    DiffLineRow(line = line)
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val error = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    val background: Color
    val sign: String
    val signColor: Color
    val textColor: Color
    val italic: Boolean
    when (line.type) {
        DiffLineType.ADD -> {
            background = DiffAddGreen.copy(alpha = 0.10f)
            sign = "+"
            signColor = DiffAddGreen
            textColor = onSurface
            italic = false
        }

        DiffLineType.DELETE -> {
            background = error.copy(alpha = 0.10f)
            sign = "-"
            signColor = error
            textColor = onSurface
            italic = false
        }

        DiffLineType.META -> {
            background = Color.Transparent
            sign = " "
            signColor = MaterialTheme.colorScheme.onSurfaceVariant
            textColor = onSurface.copy(alpha = 0.6f)
            italic = true
        }

        DiffLineType.CONTEXT -> {
            background = Color.Transparent
            sign = " "
            signColor = MaterialTheme.colorScheme.outline
            textColor = onSurface.copy(alpha = 0.85f)
            italic = false
        }
    }
    val lineNumberColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .background(background)
            .padding(horizontal = 6.dp),
    ) {
        Text(
            text = line.oldNumber?.toString() ?: "·",
            fontSize = 9.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = lineNumberColor,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
        Text(
            text = line.newNumber?.toString() ?: "·",
            fontSize = 9.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = lineNumberColor,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
        Text(
            text = sign,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = signColor,
            modifier = Modifier.width(12.dp),
        )
        Text(
            text = line.text.ifEmpty { " " },
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            color = textColor,
        )
    }
}
