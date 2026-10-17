package com.apex.workspace.explorer

import com.apex.workspace.explorer.common.REL_UNIT_DAY
import com.apex.workspace.explorer.common.REL_UNIT_HOUR
import com.apex.workspace.explorer.common.REL_UNIT_MIN
import com.apex.workspace.explorer.common.REL_UNIT_NOW
import com.apex.workspace.explorer.common.REL_UNIT_SEC
import com.apex.workspace.explorer.common.formatBytes
import com.apex.workspace.explorer.common.formatDuration
import com.apex.workspace.explorer.common.relativeTime
import com.apex.workspace.explorer.common.relativeTimeParts
import org.junit.Assert.assertEquals
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// 纯函数格式化测试：字节档位 / 相对时间区间 / 时长。
// ─────────────────────────────────────────────────────────────────────────────

class FormatTest {

    // ── formatBytes ──

    @Test
    fun formatBytes_bytesLevel() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1 B", formatBytes(1))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun formatBytes_kbLevel() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1024 + 512))
        assertEquals("1023.9 KB", formatBytes(1024 * 1024 - 100))
    }

    @Test
    fun formatBytes_mbLevel() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.5 MB", formatBytes((1024L * 1024 * 3) / 2))
        assertEquals("512.0 MB", formatBytes(512L * 1024 * 1024))
    }

    @Test
    fun formatBytes_gbLevel() {
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("2.5 GB", formatBytes(1024L * 1024 * 1024 * 5 / 2))
        assertEquals("1024.0 GB", formatBytes(1024L * 1024 * 1024 * 1024))
    }

    // ── relativeTimeParts ──

    @Test
    fun relativeTimeParts_justNow() {
        val now = 1_000_000_000L
        assertEquals(0L to REL_UNIT_NOW, relativeTimeParts(now, now))
        assertEquals(0L to REL_UNIT_NOW, relativeTimeParts(now - 9_999, now))
        // 未来时间防御性折叠为刚刚
        assertEquals(0L to REL_UNIT_NOW, relativeTimeParts(now + 60_000, now))
    }

    @Test
    fun relativeTimeParts_seconds() {
        val now = 1_000_000_000L
        assertEquals(10L to REL_UNIT_SEC, relativeTimeParts(now - 10_000, now))
        assertEquals(59L to REL_UNIT_SEC, relativeTimeParts(now - 59_999, now))
    }

    @Test
    fun relativeTimeParts_minutes() {
        val now = 1_000_000_000L
        assertEquals(1L to REL_UNIT_MIN, relativeTimeParts(now - 60_000, now))
        assertEquals(59L to REL_UNIT_MIN, relativeTimeParts(now - 59 * 60_000 - 59_999, now))
    }

    @Test
    fun relativeTimeParts_hours() {
        val now = 1_000_000_000L
        assertEquals(1L to REL_UNIT_HOUR, relativeTimeParts(now - 3_600_000, now))
        assertEquals(23L to REL_UNIT_HOUR, relativeTimeParts(now - 23 * 3_600_000, now))
    }

    @Test
    fun relativeTimeParts_days() {
        val now = 1_000_000_000L
        assertEquals(1L to REL_UNIT_DAY, relativeTimeParts(now - 86_400_000, now))
        assertEquals(30L to REL_UNIT_DAY, relativeTimeParts(now - 30L * 86_400_000, now))
    }

    // ── relativeTime（语言中立兜底）──

    @Test
    fun relativeTime_compact() {
        val now = 1_000_000_000L
        assertEquals("now", relativeTime(now, now))
        assertEquals("45s", relativeTime(now - 45_000, now))
        assertEquals("12m", relativeTime(now - 12 * 60_000, now))
        assertEquals("3h", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d", relativeTime(now - 2L * 86_400_000, now))
    }

    // ── formatDuration ──

    @Test
    fun formatDuration_short() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("59:59", formatDuration(59 * 60_000 + 59_000))
    }

    @Test
    fun formatDuration_hours() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:01:01", formatDuration(3_600_000 + 60_000 + 1_000))
    }
}
