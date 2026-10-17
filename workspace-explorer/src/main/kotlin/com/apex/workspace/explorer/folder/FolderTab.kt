package com.apex.workspace.explorer.folder

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.model.ExplorerUiState

// ─────────────────────────────────────────────────────────────────────────────
// 文件夹标签：宽屏双栏（树 0.42 + 分隔线 + 预览 0.58），窄屏单栏
// （预览打开时整屏预览、可返回；否则整屏文件树）。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun FolderTab(
    state: ExplorerUiState,
    controller: WorkspaceExplorerController,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                FileTreeView(
                    state = state,
                    controller = controller,
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                )
                HorizontalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                PreviewPane(
                    state = state,
                    controller = controller,
                    showBack = false,
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                )
            }
        } else {
            if (state.preview != null) {
                PreviewPane(
                    state = state,
                    controller = controller,
                    showBack = true,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FileTreeView(
                    state = state,
                    controller = controller,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
