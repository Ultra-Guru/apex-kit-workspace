package com.apex.workspace.explorer.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.apex.workspace.explorer.R
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// 纯函数格式化工具：字节、相对时间、时长。
// 全部无 Android 依赖，可在 JVM 单测直接覆盖。
// ─────────────────────────────────────────────────────────────────────────────

// 单位索引约定（与 workspace_rel_time_units stringArray 一一对应）：
// 0=刚刚 1=秒 2=分钟 3=小时 4=天
internal const val REL_UNIT_NOW = 0
internal const val REL_UNIT_SEC = 1
internal const val REL_UNIT_MIN = 2
internal const val REL_UNIT_HOUR = 3
internal const val REL_UNIT_DAY = 4

// 字节数 → "512 B" / "1.5 KB" / "2.3 MB" / "1.0 GB"（KB 以上保留一位小数）。
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb)
    return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
}

// 时间戳 → (数值, 单位索引)。未来时间与 10 秒内都折叠为 (0, 刚刚)。
internal fun relativeTimeParts(timeMs: Long, nowMs: Long): Pair<Long, Int> {
    val diff = (nowMs - timeMs).coerceIn(0L, Long.MAX_VALUE)
    return when {
        diff < 10_000L -> 0L to REL_UNIT_NOW
        diff < 60_000L -> diff / 1_000L to REL_UNIT_SEC
        diff < 3_600_000L -> diff / 60_000L to REL_UNIT_MIN
        diff < 86_400_000L -> diff / 3_600_000L to REL_UNIT_HOUR
        else -> diff / 86_400_000L to REL_UNIT_DAY
    }
}

// 语言中立的紧凑相对时间（"now" / "45s" / "12m" / "3h" / "2d"），兜底与测试用。
internal fun relativeTime(timeMs: Long, nowMs: Long): String {
    val (value, unit) = relativeTimeParts(timeMs, nowMs)
    return when (unit) {
        REL_UNIT_NOW -> "now"
        REL_UNIT_SEC -> "${value}s"
        REL_UNIT_MIN -> "${value}m"
        REL_UNIT_HOUR -> "${value}h"
        else -> "${value}d"
    }
}

// 时长毫秒 → "m:ss" / "h:mm:ss"（音视频信息卡用）。
internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1_000L
    val hours = totalSec / 3_600L
    val minutes = (totalSec % 3_600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

// 本地化相对时间文案：数值 + 单位词拼接走资源（workspace_rel_time_fmt + 单位数组）。
@Composable
internal fun relTimeText(timeMs: Long): String {
    val units = stringArrayResource(R.array.workspace_rel_time_units)
    val (value, unit) = relativeTimeParts(timeMs, System.currentTimeMillis())
    return if (unit == REL_UNIT_NOW) {
        units[REL_UNIT_NOW]
    } else {
        stringResource(R.string.workspace_rel_time_fmt, value, units[unit])
    }
}

// 路径 → 文件名（最后一个 '/' 之后；无分隔符返回原串）。
internal fun fileNameOf(path: String): String = path.substringAfterLast('/')
