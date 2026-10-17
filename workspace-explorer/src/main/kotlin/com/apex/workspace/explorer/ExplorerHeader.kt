package com.apex.workspace.explorer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.workspace.core.model.ExplorerTab
import com.apex.workspace.core.model.ExplorerUiState
import com.apex.workspace.explorer.common.PulsingDot

// ─────────────────────────────────────────────────────────────────────────────
// 浏览器头部（两行）：
// 第一行 = 根目录名 + agent 脉冲点 + 实时跟随胶囊 + 关闭；
// 第二行 = 圆角胶囊标签栏（文件夹 / Git / 变更和产物），
// 胶囊语言镜像宿主 CodeLogicModeSelector：RoundedCornerShape(50) +
// heightIn(min=28dp) + labelMedium + secondaryContainer 选中色；窄屏横向滚动。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ExplorerHeader(
    state: ExplorerUiState,
    agentActive: Boolean,
    onToggleFollow: () -> Unit,
    onClose: () -> Unit,
    onTabSelect: (ExplorerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        HeaderTitleRow(
            rootName = state.rootName,
            agentActive = agentActive,
            followEnabled = state.followEnabled,
            onToggleFollow = onToggleFollow,
            onClose = onClose,
        )
        Spacer(Modifier.height(4.dp))
        TabPillRow(state = state, onTabSelect = onTabSelect)
    }
}

@Composable
private fun HeaderTitleRow(
    rootName: String,
    agentActive: Boolean,
    followEnabled: Boolean,
    onToggleFollow: () -> Unit,
    onClose: () -> Unit,
) {
    val agentActiveCd = stringResource(R.string.workspace_agent_active_cd)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = rootName.ifBlank { stringResource(R.string.workspace_no_root_title) },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (agentActive) {
            Spacer(Modifier.width(6.dp))
            PulsingDot(
                size = 6.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = agentActiveCd },
            )
        }
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = followEnabled,
            onClick = onToggleFollow,
            label = {
                Text(
                    text = stringResource(
                        if (followEnabled) R.string.workspace_follow_on
                        else R.string.workspace_follow_off
                    ),
                    maxLines = 1,
                )
            },
            leadingIcon = {
                if (followEnabled) {
                    PulsingDot(size = 6.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Radar,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                }
            },
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.workspace_close_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TabPillRow(state: ExplorerUiState, onTabSelect: (ExplorerTab) -> Unit) {
    // 徽标口径：Git = 变更条目总数；变更 = 变更文件数；非仓库时隐藏（传 0）。
    val gitBadge = if (state.git.isRepo) state.git.entries.size else 0
    val changesBadge = if (state.changes.isRepo) state.changes.summary.filesChanged else 0
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TabPill(
                selected = state.tab == ExplorerTab.FOLDER,
                icon = Icons.Rounded.Folder,
                label = stringResource(R.string.workspace_tab_folder),
                badge = 0,
                onClick = { onTabSelect(ExplorerTab.FOLDER) },
            )
            TabPill(
                selected = state.tab == ExplorerTab.GIT,
                icon = Icons.Rounded.CallSplit,
                label = stringResource(R.string.workspace_tab_git),
                badge = gitBadge,
                onClick = { onTabSelect(ExplorerTab.GIT) },
            )
            TabPill(
                selected = state.tab == ExplorerTab.CHANGES,
                icon = Icons.Rounded.Inventory2,
                label = stringResource(R.string.workspace_tab_changes),
                badge = changesBadge,
                onClick = { onTabSelect(ExplorerTab.CHANGES) },
            )
        }
    }
}

// 单个胶囊：选中 secondaryContainer；徽标数 > 0 时以 “标签 · N” 合并展示。
@Composable
private fun TabPill(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    badge: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.heightIn(min = 28.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = if (badge > 0) {
                    stringResource(R.string.workspace_tab_badge_fmt, label, badge)
                } else {
                    label
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
