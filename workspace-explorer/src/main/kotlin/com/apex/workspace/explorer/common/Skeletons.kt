package com.apex.workspace.explorer.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// 通用视觉原子：骨架行 / 脉冲点 / 空态。
// 骨架脉冲参数镜像宿主 CodeDiffView.DiffSkeleton（0.3↔0.6，600ms 往返）。
// ─────────────────────────────────────────────────────────────────────────────

// 呼吸脉冲 alpha（骨架 + 各种“活着”的小圆点共用）。
@Composable
private fun pulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "workspace-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "workspace-pulse-alpha",
    )
    return alpha
}

// 加载骨架：几条渐窄的灰行（默认 6 行，首行 90%、次行 70%、其余 80% 宽）。
@Composable
internal fun SkeletonRows(
    modifier: Modifier = Modifier,
    count: Int = 6,
    rowHeight: Dp = 14.dp,
) {
    val alpha = pulseAlpha()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        repeat(count.coerceAtLeast(1)) { index ->
            val fraction = when (index % 3) {
                0 -> 0.9f
                1 -> 0.7f
                else -> 0.8f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(rowHeight)
                    .padding(vertical = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
                        RoundedCornerShape(4.dp),
                    ),
            )
            if (index != count - 1) Spacer(Modifier.height(8.dp))
        }
    }
}

// 无限脉冲小圆点（agent 运行提示 / 跟随中 leading 图标）。
@Composable
internal fun PulsingDot(
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val alpha = pulseAlpha()
    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = 0.35f + alpha * 0.65f), RoundedCornerShape(50)),
    )
}

// 空态 / 引导态：居中大图标 + 说明文案。
@Composable
internal fun EmptyStateView(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
