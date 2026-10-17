package com.apex.workspace.explorer.folder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.model.ExplorerUiState
import com.apex.workspace.core.model.FilePreview
import com.apex.workspace.explorer.R
import com.apex.workspace.explorer.common.EmptyStateView
import com.apex.workspace.explorer.common.SkeletonRows
import com.apex.workspace.explorer.common.fileNameOf
import com.apex.workspace.explorer.common.fileKindIcon
import com.apex.workspace.explorer.common.fileKindTint
import com.apex.workspace.explorer.common.formatBytes
import com.apex.workspace.explorer.preview.ArchivePreviewView
import com.apex.workspace.explorer.preview.BinaryPreviewView
import com.apex.workspace.explorer.preview.FailurePreviewView
import com.apex.workspace.explorer.preview.ImagePreviewView
import com.apex.workspace.explorer.preview.MediaPreviewView
import com.apex.workspace.explorer.preview.PdfPreviewView
import com.apex.workspace.explorer.preview.TextPreviewView

// ─────────────────────────────────────────────────────────────────────────────
// 预览面板：头部（类型图标 + 文件名 + 类型徽标 + 元信息 + 返回/关闭/重载）+
// 内容区按 FilePreview sealed 分派七态渲染。
// 加载中 = 顶部进度条 + 旧内容淡显（0.45 alpha），不闪空屏。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun PreviewPane(
    state: ExplorerUiState,
    controller: WorkspaceExplorerController,
    showBack: Boolean,
    modifier: Modifier = Modifier,
) {
    val preview = state.preview

    Column(modifier = modifier.fillMaxSize()) {
        if (preview != null) {
            PreviewHeader(
                preview = preview,
                showBack = showBack,
                onBack = controller::closePreview,
                onClose = controller::closePreview,
                onReload = controller::refreshPreview,
            )
        }
        if (state.previewLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Box(modifier = Modifier.weight(1f)) {
            val dimModifier = if (state.previewLoading) {
                Modifier.fillMaxSize().alpha(0.45f)
            } else {
                Modifier.fillMaxSize()
            }
            when (preview) {
                null -> if (state.previewLoading) {
                    SkeletonRows(count = 8)
                } else {
                    EmptyStateView(
                        icon = Icons.Rounded.InsertDriveFile,
                        text = stringResource(R.string.workspace_preview_empty),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                is FilePreview.Text -> TextPreviewView(
                    preview = preview,
                    modifier = dimModifier,
                )

                is FilePreview.Image -> ImagePreviewView(
                    preview = preview,
                    controller = controller,
                    modifier = dimModifier,
                )

                is FilePreview.Pdf -> PdfPreviewView(
                    preview = preview,
                    controller = controller,
                    modifier = dimModifier,
                )

                is FilePreview.Media -> MediaPreviewView(
                    preview = preview,
                    modifier = dimModifier,
                )

                is FilePreview.Archive -> ArchivePreviewView(
                    preview = preview,
                    modifier = dimModifier,
                )

                is FilePreview.Binary -> BinaryPreviewView(
                    preview = preview,
                    modifier = dimModifier,
                )

                is FilePreview.Failure -> FailurePreviewView(preview = preview)
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    preview: FilePreview,
    showBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onReload: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val metaText = when (preview) {
        is FilePreview.Text -> stringResource(R.string.workspace_preview_lines_fmt, preview.lineCount)
        is FilePreview.Image -> formatBytes(preview.sizeBytes)
        is FilePreview.Pdf -> formatBytes(preview.sizeBytes)
        is FilePreview.Media -> formatBytes(preview.sizeBytes)
        is FilePreview.Archive -> formatBytes(preview.sizeBytes)
        is FilePreview.Binary -> formatBytes(preview.sizeBytes)
        is FilePreview.Failure -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.workspace_preview_back_cd),
                )
            }
        }
        Icon(
            imageVector = fileKindIcon(preview.kind),
            contentDescription = null,
            tint = fileKindTint(preview.kind, scheme),
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileNameOf(preview.path),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KindBadge(label = preview.kind.label)
                if (metaText != null) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(onClick = onReload) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.workspace_preview_reload_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!showBack) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.workspace_preview_close_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// 类型徽标：小号等宽胶囊（如 PY / IMG / MD）。
@Composable
private fun KindBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
