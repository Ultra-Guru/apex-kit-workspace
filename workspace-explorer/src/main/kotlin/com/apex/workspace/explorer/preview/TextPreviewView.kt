package com.apex.workspace.explorer.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.workspace.core.model.FileCategory
import com.apex.workspace.core.model.FilePreview
import com.apex.workspace.explorer.R

// ─────────────────────────────────────────────────────────────────────────────
// 文本预览：Markdown 渲染/源码切换、JSON 美化/原始切换、
// 代码类带行号列（60% alpha）+ 等宽 11sp + 双向滚动。
// 视图模式是纯视图态（remember，随 path 重置），不属于业务状态。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun TextPreviewView(
    preview: FilePreview.Text,
    modifier: Modifier = Modifier,
) {
    var mdRendered by remember(preview.path) { mutableStateOf(true) }
    var pretty by remember(preview.path) { mutableStateOf(true) }
    val isMarkdown = preview.kind.category == FileCategory.MARKDOWN
    val hasPrettyJson = preview.prettyJson != null

    Column(modifier = modifier.fillMaxSize()) {
        if (isMarkdown || hasPrettyJson) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                if (isMarkdown) {
                    FilterChip(
                        selected = mdRendered,
                        onClick = { mdRendered = true },
                        label = { Text(stringResource(R.string.workspace_preview_rendered)) },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = !mdRendered,
                        onClick = { mdRendered = false },
                        label = { Text(stringResource(R.string.workspace_preview_source)) },
                    )
                } else {
                    FilterChip(
                        selected = pretty,
                        onClick = { pretty = true },
                        label = { Text(stringResource(R.string.workspace_preview_pretty)) },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = !pretty,
                        onClick = { pretty = false },
                        label = { Text(stringResource(R.string.workspace_preview_raw)) },
                    )
                }
            }
        }
        if (preview.truncated) {
            Text(
                text = stringResource(R.string.workspace_preview_truncated_fmt, preview.lineCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        when {
            isMarkdown && mdRendered -> MarkdownDocument(
                text = preview.text,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            else -> CodeText(
                text = if (hasPrettyJson && pretty) preview.prettyJson.orEmpty() else preview.text,
                showLineNumbers = preview.kind.category == FileCategory.CODE,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

// 等宽文本 + 可选行号列。行号列与正文共用同一垂直 ScrollState（行高一致，
// 均为 16sp lineHeight，不会错位）；正文横向滚动，行号保持钉在左侧。
@Composable
internal fun CodeText(
    text: String,
    showLineNumbers: Boolean,
    modifier: Modifier = Modifier,
) {
    val lines = remember(text) { text.split('\n') }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val numberColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val textColor = MaterialTheme.colorScheme.onSurface

    Row(modifier = modifier) {
        if (showLineNumbers) {
            Column(
                modifier = Modifier
                    .width(38.dp)
                    .verticalScroll(vertical)
                    .padding(vertical = 8.dp),
            ) {
                lines.forEachIndexed { index, _ ->
                    Text(
                        text = "${index + 1}",
                        fontSize = 10.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = numberColor,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(vertical)
                .horizontalScroll(horizontal)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            lines.forEach { line ->
                Text(
                    text = line.ifEmpty { " " },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                )
            }
        }
    }
}
