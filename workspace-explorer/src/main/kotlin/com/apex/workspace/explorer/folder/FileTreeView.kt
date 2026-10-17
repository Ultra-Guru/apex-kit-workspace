package com.apex.workspace.explorer.folder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.model.ExplorerUiState
import com.apex.workspace.core.model.FileNode
import com.apex.workspace.core.model.GitFileStatus
import com.apex.workspace.explorer.R
import com.apex.workspace.explorer.common.EmptyStateView
import com.apex.workspace.explorer.common.SkeletonRows
import com.apex.workspace.explorer.common.DirGitInfo
import com.apex.workspace.explorer.common.deriveDirGit
import com.apex.workspace.explorer.common.fileKindIcon
import com.apex.workspace.explorer.common.fileKindTint
import com.apex.workspace.explorer.common.formatBytes
import com.apex.workspace.explorer.common.relTimeText
import com.apex.workspace.explorer.common.statusDotColor

// ─────────────────────────────────────────────────────────────────────────────
// 文件树：过滤框 + 刷新 + 可见节点 LazyColumn（目录行/文件行/git 状态点）。
// 可见性/展开态/过滤匹配全部由 core 的 TreeState 计算，UI 只渲染。
// 行高纪律：目录 40dp 起、文件 44dp 起；树区 weight 内滚，底部统计 caption。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun FileTreeView(
    state: ExplorerUiState,
    controller: WorkspaceExplorerController,
    modifier: Modifier = Modifier,
) {
    val tree = state.tree
    val dirGit = remember(state.git.entries) { deriveDirGit(state.git.entries) }
    val pathStatus = remember(state.git.entries) {
        state.git.entries.associate { it.path to it.status }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = tree.filter,
                onValueChange = controller::setTreeFilter,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                placeholder = { Text(stringResource(R.string.workspace_tree_filter_hint)) },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = controller::refreshTree) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.workspace_refresh_cd),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                tree.visibleNodes.isEmpty() && state.busy -> SkeletonRows(count = 8)
                tree.visibleNodes.isEmpty() && tree.filter.isNotBlank() -> EmptyStateView(
                    icon = Icons.Rounded.Search,
                    text = stringResource(R.string.workspace_tree_no_match),
                    modifier = Modifier.align(Alignment.Center),
                )
                tree.visibleNodes.isEmpty() -> EmptyStateView(
                    icon = Icons.Rounded.FolderOpen,
                    text = stringResource(R.string.workspace_tree_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                ) {
                    items(tree.visibleNodes, key = { it.path }) { node ->
                        if (node.isDirectory) {
                            DirRow(
                                node = node,
                                expanded = node.path in tree.expandedDirs,
                                gitInfo = dirGit[node.path],
                                onClick = { controller.toggleDir(node.path) },
                            )
                        } else {
                            FileRow(
                                node = node,
                                selected = state.preview?.path == node.path,
                                status = pathStatus[node.path],
                                onClick = { controller.openFile(node.path) },
                            )
                        }
                    }
                    if (tree.truncated) {
                        item(key = "tree-truncated") {
                            Text(
                                text = stringResource(R.string.workspace_tree_truncated),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        if (tree.totalNodes > 0) {
            Text(
                text = stringResource(R.string.workspace_tree_items_fmt, tree.totalNodes),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

// 目录行：缩进 = depth*12dp；箭头 90° 展开动画；变更计数 + 状态点。
@Composable
private fun DirRow(
    node: FileNode,
    expanded: Boolean,
    gitInfo: DirGitInfo?,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "dir-chevron",
    )
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                start = 8.dp + (node.depth * 12).dp,
                end = 8.dp,
                top = 2.dp,
                bottom = 2.dp,
            ),
    ) {
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation },
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        val info = gitInfo
        if (info != null && info.count > 0) {
            Text(
                text = stringResource(R.string.workspace_tree_changed_fmt, info.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        info.status?.let { statusDotColor(it) } ?: scheme.secondary,
                        RoundedCornerShape(50),
                    ),
            )
        }
    }
}

// 文件行：类型图标 + 名称 + 大小/时间 caption + git 状态点；选中态高亮。
@Composable
private fun FileRow(
    node: FileNode,
    selected: Boolean,
    status: GitFileStatus?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val caption = buildString {
        node.sizeBytes?.let { append(formatBytes(it)) }
        node.lastModifiedMs.let {
            if (isNotEmpty()) append(" · ")
            append(relTimeText(it))
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                start = 8.dp + (node.depth * 12).dp,
                end = 12.dp,
                top = 2.dp,
                bottom = 2.dp,
            ),
    ) {
        Spacer(Modifier.width(18.dp))
        Icon(
            imageVector = fileKindIcon(node.kind),
            contentDescription = null,
            tint = fileKindTint(node.kind, scheme),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = node.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        if (caption.isNotEmpty()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (status != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(statusDotColor(status), RoundedCornerShape(50)),
            )
        }
    }
}
