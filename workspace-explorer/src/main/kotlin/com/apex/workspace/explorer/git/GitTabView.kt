package com.apex.workspace.explorer.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.model.ExplorerUiState
import com.apex.workspace.core.model.GitFileEntry
import com.apex.workspace.core.model.GitFileStatus
import com.apex.workspace.explorer.R
import com.apex.workspace.explorer.common.SkeletonRows
import com.apex.workspace.explorer.common.StatusBadge
import com.apex.workspace.explorer.common.statusShortCode
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// Git 标签：分支行 / 提交框（回执条 4s 自消）/ 分组变更列表 /
// 底部固定 DiffPanel。状态徽标色语义镜像宿主 CodeWorkspacePanel。
// 提交草稿是纯输入态（remember，宿主面板同款），不进 controller。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun GitTabView(
    state: ExplorerUiState,
    controller: WorkspaceExplorerController,
    modifier: Modifier = Modifier,
) {
    val git = state.git
    Column(modifier = modifier.fillMaxSize()) {
        when {
            git.loading && git.entries.isEmpty() -> SkeletonRows(count = 7)

            !git.isRepo -> NotRepoCard(
                busy = git.loading || git.committing,
                onInit = controller::initRepo,
                modifier = Modifier.weight(1f),
            )

            else -> {
                BranchRow(
                    branch = git.branch,
                    ahead = git.ahead,
                    behind = git.behind,
                    loading = git.loading,
                    onRefresh = controller::refreshGit,
                )
                CommitBox(
                    committing = git.committing,
                    message = state.message,
                    messageIsError = state.messageIsError,
                    onCommit = controller::commitAll,
                    onConsumeMessage = controller::consumeMessage,
                )
                if (git.entries.isEmpty()) {
                    CleanState(modifier = Modifier.weight(1f))
                } else {
                    ChangeGroups(
                        entries = git.entries,
                        selectedPath = git.diff?.path,
                        onRowClick = controller::loadDiff,
                        onStage = controller::stage,
                        onUnstage = controller::unstage,
                        modifier = Modifier.weight(1f),
                    )
                }
                git.diff?.let { diff ->
                    DiffPanelView(
                        diff = diff,
                        onClose = controller::closeDiff,
                    )
                }
            }
        }
    }
}

// ── 分支行 ──

@Composable
private fun BranchRow(
    branch: String?,
    ahead: Int,
    behind: Int,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.CallSplit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = branch ?: stringResource(R.string.workspace_git_no_branch),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (ahead > 0) {
            AheadBehindBadge(text = "\u2191$ahead", color = MaterialTheme.colorScheme.tertiary)
        }
        if (behind > 0) {
            AheadBehindBadge(text = "\u2193$behind", color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.workspace_refresh_cd),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AheadBehindBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── 提交框 + 回执 ──

@Composable
private fun CommitBox(
    committing: Boolean,
    message: String?,
    messageIsError: Boolean,
    onCommit: (String) -> Unit,
    onConsumeMessage: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    // 成功回执后清空草稿（失败保留，便于改完重试）
    LaunchedEffect(message, messageIsError) {
        if (message != null && !messageIsError) draft = ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(R.string.workspace_git_commit_hint)) },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onCommit(draft) },
                enabled = draft.isNotBlank() && !committing,
            ) {
                if (committing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.workspace_git_commit))
            }
        }
        if (message != null) {
            Spacer(Modifier.padding(top = 6.dp))
            MessageBar(
                message = message,
                isError = messageIsError,
                onDismiss = onConsumeMessage,
            )
        }
    }
}

// 回执条：成功 surfaceVariant + CheckCircle / 失败 errorContainer；点击消、4s 自消。
@Composable
private fun MessageBar(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(message) {
        delay(4_000L)
        onDismiss()
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDismiss() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── 变更分组列表 ──

@Composable
private fun ChangeGroups(
    entries: List<GitFileEntry>,
    selectedPath: String?,
    onRowClick: (String) -> Unit,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val staged = entries.filter { it.staged }
    val unstaged = entries.filter { !it.staged && it.status != GitFileStatus.UNTRACKED }
    val untracked = entries.filter { it.status == GitFileStatus.UNTRACKED }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
    ) {
        if (staged.isNotEmpty()) {
            item(key = "group-staged") {
                GroupHeader(text = stringResource(R.string.workspace_git_staged_fmt, staged.size))
            }
            items(staged, key = { "s-" + it.path }) { entry ->
                GitFileRow(
                    entry = entry,
                    selected = selectedPath == entry.path,
                    onRowClick = onRowClick,
                    onStage = onStage,
                    onUnstage = onUnstage,
                )
            }
        }
        if (unstaged.isNotEmpty()) {
            item(key = "group-unstaged") {
                GroupHeader(text = stringResource(R.string.workspace_git_unstaged_fmt, unstaged.size))
            }
            items(unstaged, key = { "u-" + it.path }) { entry ->
                GitFileRow(
                    entry = entry,
                    selected = selectedPath == entry.path,
                    onRowClick = onRowClick,
                    onStage = onStage,
                    onUnstage = onUnstage,
                )
            }
        }
        if (untracked.isNotEmpty()) {
            item(key = "group-untracked") {
                GroupHeader(text = stringResource(R.string.workspace_git_untracked_fmt, untracked.size))
            }
            items(untracked, key = { "n-" + it.path }) { entry ->
                GitFileRow(
                    entry = entry,
                    selected = selectedPath == entry.path,
                    onRowClick = onRowClick,
                    onStage = onStage,
                    onUnstage = onUnstage,
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// 单行变更：状态徽标 + 路径（rename 附旧路径灰字）+ 暂存/取消暂存按钮。
@Composable
private fun GitFileRow(
    entry: GitFileEntry,
    selected: Boolean,
    onRowClick: (String) -> Unit,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(role = Role.Button) { onRowClick(entry.path) }
            .padding(start = 12.dp, end = 4.dp),
    ) {
        StatusBadge(code = statusShortCode(entry.status))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.oldPath != null) {
                Text(
                    text = entry.oldPath.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.staged) {
            IconButton(onClick = { onUnstage(entry.path) }) {
                Icon(
                    imageVector = Icons.Outlined.Remove,
                    contentDescription = stringResource(R.string.workspace_git_unstage_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            IconButton(onClick = { onStage(entry.path) }) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.workspace_git_stage_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 空态 / 引导态 ──

@Composable
private fun CleanState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.workspace_git_clean),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NotRepoCard(
    busy: Boolean,
    onInit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CallSplit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.padding(top = 10.dp))
                Text(
                    text = stringResource(R.string.workspace_git_not_repo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 14.dp))
                Button(onClick = onInit, enabled = !busy) {
                    Text(stringResource(R.string.workspace_git_init))
                }
            }
        }
    }
}
