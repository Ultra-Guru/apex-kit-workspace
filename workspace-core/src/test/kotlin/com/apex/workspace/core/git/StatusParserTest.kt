package com.apex.workspace.core.git

import com.apex.workspace.core.model.GitFileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusParserTest {

    @Test
    fun `分支行 - 普通 main`() {
        val s = StatusParser.parse("## main\n M a.txt\n")
        assertTrue(s.isRepo)
        assertEquals("main", s.branch)
        assertEquals(0, s.ahead)
        assertEquals(0, s.behind)
    }

    @Test
    fun `分支行 - ahead behind`() {
        val s = StatusParser.parse("## main...origin/main [ahead 1, behind 2]\n")
        assertEquals("main", s.branch)
        assertEquals(1, s.ahead)
        assertEquals(2, s.behind)
    }

    @Test
    fun `分支行 - 仅 ahead`() {
        val s = StatusParser.parse("## feat/x...origin/feat/x [ahead 3]\n")
        assertEquals("feat/x", s.branch)
        assertEquals(3, s.ahead)
        assertEquals(0, s.behind)
    }

    @Test
    fun `分支行 - 尚无提交`() {
        val s = StatusParser.parse("## No commits yet on main\n?? a.txt\n")
        assertTrue(s.isRepo)
        assertEquals("main", s.branch)
        assertEquals(1, s.entries.size)
        assertEquals(GitFileStatus.UNTRACKED, s.entries[0].status)
    }

    @Test
    fun `分支行 - 分离头指针`() {
        val s = StatusParser.parse("## HEAD (no branch)\n")
        assertTrue(s.isRepo)
        assertEquals("HEAD", s.branch)
    }

    @Test
    fun `条目 - 工作区修改`() {
        val s = StatusParser.parse("## main\n M a.txt\n")
        val e = s.entries.single()
        assertEquals(" M", e.statusCode)
        assertEquals(GitFileStatus.MODIFIED, e.status)
        assertFalse(e.staged)
        assertEquals("a.txt", e.path)
    }

    @Test
    fun `条目 - 已暂存修改`() {
        val s = StatusParser.parse("## main\nM  a.txt\n")
        val e = s.entries.single()
        assertEquals(GitFileStatus.MODIFIED, e.status)
        assertTrue(e.staged)
    }

    @Test
    fun `条目 - 未跟踪`() {
        val s = StatusParser.parse("## main\n?? new.py\n")
        val e = s.entries.single()
        assertEquals(GitFileStatus.UNTRACKED, e.status)
        assertFalse(e.staged)
    }

    @Test
    fun `条目 - 已暂存新增`() {
        val s = StatusParser.parse("## main\nA  b.txt\n")
        assertEquals(GitFileStatus.ADDED, s.entries.single().status)
    }

    @Test
    fun `条目 - 删除`() {
        val s = StatusParser.parse("## main\n D c.txt\n")
        assertEquals(GitFileStatus.DELETED, s.entries.single().status)
    }

    @Test
    fun `条目 - 重命名取新路径`() {
        val s = StatusParser.parse("## main\nR  old.txt -> new.txt\n")
        val e = s.entries.single()
        assertEquals(GitFileStatus.RENAMED, e.status)
        assertEquals("new.txt", e.path)
        assertEquals("old.txt", e.oldPath)
        assertTrue(e.staged)
    }

    @Test
    fun `条目 - 冲突 UU`() {
        val s = StatusParser.parse("## main\nUU both.txt\n")
        val e = s.entries.single()
        assertEquals(GitFileStatus.CONFLICT, e.status)
        assertFalse(e.staged)
    }

    @Test
    fun `条目 - 路径含空格`() {
        val s = StatusParser.parse("## main\n M my file name.txt\n")
        assertEquals("my file name.txt", s.entries.single().path)
    }

    @Test
    fun `条目 - 路径含箭头字面量`() {
        // 罕见但真实：文件名里就有 " -> "，按最后一个 " -> " 拆分
        val s = StatusParser.parse("## main\nR  a -> b -> c\n")
        assertEquals("c", s.entries.single().path)
        assertEquals("a -> b", s.entries.single().oldPath)
    }

    @Test
    fun `非仓库输出`() {
        val s = StatusParser.parse("")
        assertFalse(s.isRepo)
        assertNull(s.branch)
    }

    @Test
    fun `空仓库无变更`() {
        val s = StatusParser.parse("## main\n")
        assertTrue(s.isRepo)
        assertEquals(0, s.entries.size)
    }
}
