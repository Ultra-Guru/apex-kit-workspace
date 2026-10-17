package com.apex.workspace.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumstatParserTest {

    @Test
    fun `普通行`() {
        val recs = NumstatParser.parse("12\t3\tsrc/App.kt\n5\t0\tREADME.md\n")
        assertEquals(2, recs.size)
        val r0 = recs[0]
        assertEquals("src/App.kt", r0.path)
        assertNull(r0.oldPath)
        assertEquals(12, r0.additions)
        assertEquals(3, r0.deletions)
        assertEquals(5, recs[1].additions)
    }

    @Test
    fun `二进制行`() {
        val recs = NumstatParser.parse("-\t-\tlogo.png\n")
        val r = recs.single()
        assertNull(r.additions)
        assertNull(r.deletions)
    }

    @Test
    fun `重命名完整形式`() {
        val recs = NumstatParser.parse("1\t0\told/path.kt => new/path.kt\n")
        val r = recs.single()
        assertEquals("new/path.kt", r.path)
        assertEquals("old/path.kt", r.oldPath)
    }

    @Test
    fun `重命名花括号形式 - 共同目录`() {
        val recs = NumstatParser.parse("2\t1\tsrc/{Main => App}.kt\n")
        val r = recs.single()
        assertEquals("src/App.kt", r.path)
        assertEquals("src/Main.kt", r.oldPath)
    }

    @Test
    fun `重命名花括号形式 - 目录重命名`() {
        val recs = NumstatParser.parse("0\t0\t{old => new}/file.txt\n")
        val r = recs.single()
        assertEquals("new/file.txt", r.path)
        assertEquals("old/file.txt", r.oldPath)
    }

    @Test
    fun `带引号路径`() {
        // git core.quotepath 默认开启时非 ASCII 路径带引号；简单引号路径应剥壳
        val recs = NumstatParser.parse("1\t1\t\"weird name.txt\"\n")
        val r = recs.single()
        assertEquals("weird name.txt", r.path)
    }

    @Test
    fun `路径本身含制表符`() {
        // split('\t') 后 parts.size > 3：drop(2) 重组
        val recs = NumstatParser.parse("1\t1\ta\tb.txt\n")
        val r = recs.single()
        assertEquals("a\tb.txt", r.path)
    }

    @Test
    fun `空输入`() {
        assertTrue(NumstatParser.parse("").isEmpty())
        assertTrue(NumstatParser.parse("\n\n").isEmpty())
    }
}
