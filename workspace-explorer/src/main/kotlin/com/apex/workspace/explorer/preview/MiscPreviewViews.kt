package com.apex.workspace.explorer.preview

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.model.FilePreview
import com.apex.workspace.explorer.R
import com.apex.workspace.explorer.common.fileKindIcon
import com.apex.workspace.explorer.common.fileKindTint
import com.apex.workspace.explorer.common.fileNameOf
import com.apex.workspace.explorer.common.formatBytes
import com.apex.workspace.explorer.common.formatDuration
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// PDF / 媒体 / 压缩包 / 二进制 / 失败 五态预览。
// PDF 用系统 PdfRenderer 渲染首页（IO dispatcher，Bitmap → ImageBitmap）。
// ─────────────────────────────────────────────────────────────────────────────

// PDF：首页位图 + 页数 caption；渲染失败折叠为错误卡。
@Composable
internal fun PdfPreviewView(
    preview: FilePreview.Pdf,
    controller: WorkspaceExplorerController,
    modifier: Modifier = Modifier,
) {
    var file by remember(preview.path) { mutableStateOf<File?>(null) }
    var pageBitmap by remember(preview.path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var pageCount by remember(preview.path) { mutableStateOf(0) }
    var failed by remember(preview.path) { mutableStateOf(false) }

    LaunchedEffect(preview.path) {
        file = withContext(Dispatchers.IO) { controller.resolveFile(preview.path) }
    }
    LaunchedEffect(file) {
        val target = file
        if (target == null || !target.exists()) {
            failed = true
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { renderPdfFirstPage(target) }
        }.getOrNull()
        if (result == null) {
            failed = true
        } else {
            pageBitmap = result.first.asImageBitmap()
            pageCount = result.second
        }
    }

    when {
        failed -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ErrorCard(
                title = stringResource(R.string.workspace_pdf_failed),
                reason = fileNameOf(preview.path),
            )
        }

        pageBitmap != null -> Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                bitmap = pageBitmap!!,
                contentDescription = fileNameOf(preview.path),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.workspace_pdf_pages_fmt, pageCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// 渲染首页（目标宽 ≤ 1280px，自动缩放），返回 (bitmap, pageCount)。
private fun renderPdfFirstPage(file: File): Pair<Bitmap, Int> {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val count = renderer.pageCount
            renderer.openPage(0).use { page ->
                val scale = (1280f / page.width.coerceAtLeast(1)).coerceIn(1f, 3f)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap to count
            }
        }
    }
}

// 音视频：中心大图标 + 类型标签 + 大小（时长取到才显示）。
@Composable
internal fun MediaPreviewView(
    preview: FilePreview.Media,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = fileKindIcon(preview.kind),
            contentDescription = null,
            tint = fileKindTint(preview.kind, scheme),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = preview.kind.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = formatBytes(preview.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        preview.durationMs?.let { duration ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// 压缩包：摘要行 + 条目清单（名称等宽 10sp + 大小），封顶内滚。
@Composable
internal fun ArchivePreviewView(
    preview: FilePreview.Archive,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.workspace_archive_entries_fmt, preview.entries.size) +
                " · " + formatBytes(preview.sizeBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            // 条目名理论上可能重复（不同子目录同名文件），不传 key 防撞
            items(preview.entries.take(200)) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = entry.name,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = formatBytes(entry.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (preview.truncated) {
                item(key = "archive-truncated") {
                    Text(
                        text = stringResource(R.string.workspace_archive_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

// 未知二进制：信息卡 + 十六进制转储（surfaceContainerLowest 容器内等宽）。
@Composable
internal fun BinaryPreviewView(
    preview: FilePreview.Binary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_binary_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = preview.kind.label + " · " + formatBytes(preview.sizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Text(
                text = preview.hexDump.ifBlank { " " },
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(10.dp)
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

// 加载失败：errorContainer 错误卡（图标 + 标题 + 原因）。
@Composable
internal fun FailurePreviewView(
    preview: FilePreview.Failure,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ErrorCard(
            title = stringResource(R.string.workspace_preview_failed),
            reason = preview.reason.ifBlank { preview.path },
        )
    }
}

@Composable
private fun ErrorCard(title: String, reason: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
