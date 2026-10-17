package com.apex.workspace.explorer.changes

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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.model.ArtifactGroup
import com.apex.workspace.core.model.ChangeEntry
import com.apex.workspace.core.model.ExplorerUiState
import com.apex.workspace.explorer.R
import com.apex.workspace.explorer.common.EmptyStateView
import com.apex.workspace.explorer.common.SkeletonRows
import com.apex.workspace.explorer.common.StatusBadge
import com.apex.workspace.explorer.common.fileKindIcon
import com.apex.workspace.explorer.common.fileKindTint
import com.apex.workspace.explorer.common.formatBytes
import com.apex.workspace.explorer.common.relTimeText
import com.apex.workspace.explorer.common.statusShortCode
import com.apex.workspace.explorer.git.DiffPanelView

// ─────────────────────────────────────────────────────────────────────────────
// 变更和产物标签：统计三卡（非仓库替换为提示卡）→ 变更文件行
// （点击展开内联 Diff，再次点击收起）→ 产物分组清单（点击跳文件夹预览）。
// 展开集合是纯视图态（UI 本地 remember）；Diff 数据全部来自 inlineDiffs。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ChangesTabView(
    state: ExplorerUiState,
    controller: WorkspaceExplorerController,
    modifier: Modifier = Modifier,
) {
    val changes = state.changes
    val hasData = changes.summary.changes.isNotEmpty() || changes.artifacts.groups.isNotEmpty()
    var expanded by remember { mutableStateOf(setOf<String>()) }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            changes.loading && !hasData -> SkeletonRows(count = 7)

            !hasData -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                EmptyStateView(
                    icon = Icons.Rounded.CheckCircle,
                    text = stringResource(R.string.workspace_changes_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                item(key = "changes-stats") {
                    StatsRow(
                        isRepo = changes.isRepo,
                        filesChanged = changes.summary.filesChanged,
                        additions = changes.summary.additionsTotal,
                        deletions = changes.summary.deletionsTotal,
                    )
                }
                if (changes.summary.changes.isNotEmpty()) {
                    item(key = "changes-header") {
                        SectionHeader(
                            title = stringResource(R.string.workspace_changes_section),
                            countText = stringResource(
                                R.string.workspace_changes_count_fmt,
                                changes.summary.changes.size,
                            ),
                        )
                    }
                    items(changes.summary.changes, key = { "c-" + it.path }) { entry ->
                        ChangeRow(
                            entry = entry,
                            expanded = entry.path in expanded,
                            inlineDiff = changes.inlineDiffs[entry.path],
                            loading = entry.path in expanded && !changes.inlineDiffs.containsKey(entry.path),
                            onClick = {
                                expanded = if (entry.path in expanded) {
                                    expanded - entry.path
                                } else {
                                    expanded + entry.path
                                }
                                controller.toggleChangeExpand(entry.path)
                            },
                        )
                    }
                }
                if (changes.artifacts.groups.isNotEmpty()) {
                    item(key = "artifacts-header") {
                        val total = changes.artifacts.groups.sumOf { it.entries.size }
                        SectionHeader(
                            title = stringResource(R.string.workspace_artifacts_title),
                            countText = stringResource(R.string.workspace_artifacts_total_fmt, total),
                        )
                    }
                    changes.artifacts.groups.forEach { group ->
                        item(key = "artifacts-group-" + group.dir + "-" + group.entries.size) {
                            ArtifactGroupSection(
                                group = group,
                                onOpen = controller::openArtifact,
                            )
                        }
                    }
                    if (changes.artifacts.truncated) {
                        item(key = "artifacts-truncated") {
                            Text(
                                text = stringResource(R.string.workspace_artifacts_truncated),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 统计三卡 ──

@Composable
private fun StatsRow(
    isRepo: Boolean,
    filesChanged: Int,
    additions: Int,
    deletions: Int,
) {
    if (!isRepo) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Inventory2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.workspace_stat_not_repo),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        StatCard(
            label = stringResource(R.string.workspace_stat_files),
            value = filesChanged.toString(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.workspace_stat_additions),
            value = "+$additions",
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = stringResource(R.string.workspace_stat_deletions),
            value = "-$deletions",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

// ── 区块标题 ──

@Composable
private fun SectionHeader(title: String, countText: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = countText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 变更行（可展开内联 Diff）──

@Composable
private fun ChangeRow(
    entry: ChangeEntry,
    expanded: Boolean,
    inlineDiff: com.apex.workspace.core.model.DiffFile?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(
                    if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                    RoundedCornerShape(8.dp),
                )
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = fileKindIcon(entry.kind),
                contentDescription = null,
                tint = fileKindTint(entry.kind, scheme),
                modifier = Modifier.size(16.dp),
            )
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
            StatusBadge(code = statusShortCode(entry.status))
            if (entry.additions == null) {
                Text(
                    text = stringResource(R.string.workspace_changes_binary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "+" + entry.additions,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "-" + (entry.deletions ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (expanded) {
            if (inlineDiff != null) {
                DiffPanelView(diff = inlineDiff, onClose = null)
            } else if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ── 产物分组 ──

@Composable
private fun ArtifactGroupSection(
    group: ArtifactGroup,
    onOpen: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = (group.dir.ifEmpty { null } ?: stringResource(R.string.workspace_artifacts_root_dir)) + "/",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        group.entries.forEach { artifact ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.workspace_artifacts_open_cd),
                    ) { onOpen(artifact.path) }
                    .padding(horizontal = 12.dp),
            ) {
                Icon(
                    imageVector = fileKindIcon(artifact.kind),
                    contentDescription = null,
                    tint = fileKindTint(artifact.kind, scheme),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = artifact.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatBytes(artifact.sizeBytes) + " · " + relTimeText(artifact.lastModifiedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
