package com.apex.workspace.core.git

import com.apex.workspace.core.model.DiffLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffParserTest {

    @Test
    fun `标准单 hunk 解析`() {
        val text = """
            diff --git a/a.txt b/a.txt
            index 1111111..2222222 100644
            --- a/a.txt
            +++ b/a.txt
            @@ -1,3 +1,4 @@
             line1
            -line2
            +line2-new
            +line2b
             line3
        """.trimIndent()
        val files = UnifiedDiffParser.parse(text)
        val f = files.single()
        assertEquals("a.txt", f.path)
        assertNull(f.oldPath)
        assertFalse(f.binary)
        assertEquals(2, f.additions)
        assertEquals(1, f.deletions)
        val h = f.hunks.single()
        assertEquals(1, h.oldStart)
        assertEquals(3, h.oldLines)
        assertEquals(1, h.newStart)
        assertEquals(4, h.newLines)
        // 行序：context / del / add / add / context
        assertEquals(5, h.lines.size)
        assertEquals(DiffLineType.CONTEXT, h.lines[0].type)
        assertEquals(1, h.lines[0].oldNumber)
        assertEquals(1, h.lines[0].newNumber)
        assertEquals(DiffLineType.DELETE, h.lines[1].type)
        assertEquals(2, h.lines[1].oldNumber)
        assertNull(h.lines[1].newNumber)
        assertEquals(DiffLineType.ADD, h.lines[2].type)
        assertNull(h.lines[2].oldNumber)
        assertEquals(2, h.lines[2].newNumber)
        assertEquals(DiffLineType.ADD, h.lines[3].type)
        assertEquals(3, h.lines[3].newNumber)
        assertEquals(DiffLineType.CONTEXT, h.lines[4].type)
        assertEquals(3, h.lines[4].oldNumber)
        assertEquals(4, h.lines[4].newNumber)
    }

    @Test
    fun `多文件多 hunk`() {
        val text = """
            diff --git a/src/one.kt b/src/one.kt
            --- a/src/one.kt
            +++ b/src/one.kt
            @@ -1 +1 @@
            -old
            +new
            @@ -10,2 +11,2 @@
             c1
            -c2
            +c2x
            diff --git a/two.md b/two.md
            --- a/two.md
            +++ b/two.md
            @@ -5 +5 @@
            -a
            +b
        """.trimIndent()
        val files = UnifiedDiffParser.parse(text)
        assertEquals(2, files.size)
        assertEquals("src/one.kt", files[0].path)
        assertEquals(2, files[0].hunks.size)
        // 省略 count=1 的 hunk 头
        assertEquals(1, files[0].hunks[0].oldLines)
        assertEquals(1, files[0].hunks[0].newLines)
        assertEquals(10, files[0].hunks[1].oldStart)
        assertEquals("two.md", files[1].path)
    }

    @Test
    fun `重命名捕获旧路径`() {
        val text = """
            diff --git a/old.txt b/new.txt
            similarity index 90%
            rename from old.txt
            rename to new.txt
            --- a/old.txt
            +++ b/new.txt
            @@ -1 +1 @@
            -x
            +y
        """.trimIndent()
        val f = UnifiedDiffParser.parse(text).single()
        assertEquals("new.txt", f.path)
        assertEquals("old.txt", f.oldPath)
    }

    @Test
    fun `二进制文件`() {
        val text = """
            diff --git a/logo.png b/logo.png
            index 111..222 100644
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent()
        val f = UnifiedDiffParser.parse(text).single()
        assertTrue(f.binary)
        assertEquals(0, f.hunks.size)
    }

    @Test
    fun `no newline at end of file 标记`() {
        val text = """
            diff --git a/a.txt b/a.txt
            --- a/a.txt
            +++ b/a.txt
            @@ -1 +1 @@
            -old
            \ No newline at end of file
            +new
        """.trimIndent()
        val f = UnifiedDiffParser.parse(text).single()
        // META 行不参与 adds/dels 统计
        assertEquals(1, f.additions)
        assertEquals(1, f.deletions)
        val metaLine = f.hunks.single().lines.first { it.type == DiffLineType.META }
        assertEquals("\\ No newline at end of file", metaLine.text)
    }

    @Test
    fun `超长截断`() {
        val sb = StringBuilder()
        sb.append("diff --git a/big.txt b/big.txt\n--- a/big.txt\n+++ b/big.txt\n@@ -1 +1,100000 @@\n")
        repeat(100_000) { sb.append("+line").append(it).append('\n') }
        val f = UnifiedDiffParser.parse(sb.toString(), maxChars = 50_000).single()
        assertTrue(f.truncated)
    }

    @Test
    fun `空输入`() {
        assertTrue(UnifiedDiffParser.parse("").isEmpty())
    }
}
