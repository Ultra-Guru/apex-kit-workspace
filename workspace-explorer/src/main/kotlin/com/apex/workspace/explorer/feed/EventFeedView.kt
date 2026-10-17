package com.apex.workspace.explorer.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.workspace.core.model.FeedEntry
import com.apex.workspace.core.model.FileChangeKind
import com.apex.workspace.explorer.R
import com.apex.workspace.explorer.common.relTimeText

// ─────────────────────────────────────────────────────────────────────────────
// 实时事件悬浮层（bottom-end）：跟随开启且有事件时滑入，最多展示最近 6 条。
// core 不提供清空 API，故只展示不提供清空按钮。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun EventFeedView(
    followEnabled: Boolean,
    feed: List<FeedEntry>,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = followEnabled && feed.isNotEmpty(),
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        modifier = modifier.padding(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.workspace_feed_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp, top = 2.dp),
                ) {
                    feed.take(6).forEach { entry ->
                        FeedRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedRow(entry: FeedEntry) {
    val scheme = MaterialTheme.colorScheme
    val event = entry.event
    val (icon, tint) = when (event.kind) {
        FileChangeKind.CREATED -> Icons.Rounded.AddCircle to scheme.primary
        FileChangeKind.MODIFIED -> Icons.Rounded.Edit to scheme.tertiary
        FileChangeKind.DELETED -> Icons.Rounded.Delete to scheme.error
        FileChangeKind.RENAMED -> Icons.Rounded.SwapHoriz to scheme.secondary
        FileChangeKind.COMMAND -> Icons.Rounded.Terminal to scheme.onSurfaceVariant
        FileChangeKind.GIT_OP -> Icons.Rounded.CallSplit to scheme.secondary
    }
    val target = event.path?.let { path -> event.toolId + " · " + path } ?: event.toolId
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = target,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relTimeText(event.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.agentOrigin) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(scheme.primary, RoundedCornerShape(50)),
            )
        }
    }
}
