package com.apex.workspace.explorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.model.ExplorerTab
import com.apex.workspace.explorer.changes.ChangesTabView
import com.apex.workspace.explorer.feed.EventFeedView
import com.apex.workspace.explorer.folder.FolderTab
import com.apex.workspace.explorer.git.GitTabView

// ─────────────────────────────────────────────────────────────────────────────
// 工作区浏览器入口（库唯一公开 Composable）。
//
// 分层契约：
// - 状态唯一来源 = controller.state（collectAsStateWithLifecycle），UI 不持业务态；
// - 生命周期：进入组合 start()，离开 stop()（幂等，core 保证）；
// - 返回键：按“最内层优先”关层 —— Git 标签的差异面板 > 窄屏预览 > 其余差异，
//   全部关闭后不再拦截，交给宿主导航栈。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun WorkspaceExplorerScreen(
    controller: WorkspaceExplorerController,
    onClose: () -> Unit,
    agentActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val narrow = maxWidth < 600.dp
        val diffOpen = state.git.diff != null
        val previewOpenNarrow = state.preview != null && narrow

        BackHandler(enabled = diffOpen || previewOpenNarrow) {
            when {
                // 最内层优先：Git 标签下差异面板是浮层，先关它
                state.tab == ExplorerTab.GIT && diffOpen -> controller.closeDiff()
                previewOpenNarrow -> controller.closePreview()
                diffOpen -> controller.closeDiff()
            }
        }

        if (!state.rootAvailable) {
            NoRootGuide(onClose = onClose)
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ExplorerHeader(
                        state = state,
                        agentActive = agentActive,
                        onToggleFollow = controller::toggleFollow,
                        onClose = onClose,
                        onTabSelect = controller::setTab,
                    )
                    when (state.tab) {
                        ExplorerTab.FOLDER -> FolderTab(state = state, controller = controller)
                        ExplorerTab.GIT -> GitTabView(state = state, controller = controller)
                        ExplorerTab.CHANGES -> ChangesTabView(state = state, controller = controller)
                    }
                }
                EventFeedView(
                    followEnabled = state.followEnabled,
                    feed = state.feed,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
}

// 无激活工作区：全屏引导 + 关闭返回宿主。
@Composable
private fun NoRootGuide(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.workspace_no_root_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.workspace_no_root),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose) {
            Text(stringResource(R.string.workspace_no_root_close))
        }
    }
}
