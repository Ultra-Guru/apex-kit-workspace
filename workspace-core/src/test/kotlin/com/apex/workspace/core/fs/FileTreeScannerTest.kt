package com.apex.workspace.core.fs

import com.apex.workspace.core.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FileTreeScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): File {
        val ws = tmp.newFolder("ws")
        File(ws, "src").mkdirs()
        File(ws, "src/App.kt").writeText("fun main() {}")
        File(ws, "src/util").mkdirs()
        File(ws, "src/util/Helper.java").writeText("class Helper {}")
        File(ws, "README.md").writeText("# hi")
        File(ws, "zeta.txt").writeText("z")
        File(ws, "alpha.txt").writeText("a")
        return ws
    }

    @Test
    fun `目录优先与同级排序`() {
        val r = FileTreeScanner.scan(root())
        val paths = r.nodes.map { it.path }
        // 期望：每一层都是「目录优先，同级不区分大小写升序」，DFS 父先于子
        val expected = listOf(
            "src", "alpha.txt", "README.md", "zeta.txt",   // 根层
            "src/util", "src/App.kt",                       // src 层（util 目录优先）
            "src/util/Helper.java",                         // util 层
        )
        assertEquals(expected, paths)
        // 深度正确
        assertEquals(1, r.nodes.first { it.path == "src" }.depth)
        assertEquals(2, r.nodes.first { it.path == "src/util" }.depth)
        assertEquals(3, r.nodes.first { it.path == "src/util/Helper.java" }.depth)
    }

    @Test
    fun `DFS 父先于子`() {
        val r = FileTreeScanner.scan(root())
        val paths = r.nodes.map { it.path }
        assertTrue(paths.indexOf("src") < paths.indexOf("src/App.kt"))
        assertTrue(paths.indexOf("src/util") < paths.indexOf("src/util/Helper.java"))
    }

    @Test
    fun `忽略目录`() {
        val ws = root()
        File(ws, ".git").mkdirs()
        File(ws, ".git/config").writeText("x")
        File(ws, "node_modules").mkdirs()
        File(ws, "node_modules/pkg.js").writeText("x")
        val r = FileTreeScanner.scan(ws)
        assertFalse(r.nodes.any { it.path.startsWith(".git") })
        assertFalse(r.nodes.any { it.path.startsWith("node_modules") })
    }

    @Test
    fun `节点上限触发截断`() {
        val ws = root()
        repeat(50) { File(ws, "f$it.txt").writeText("x") }
        val r = FileTreeScanner.scan(ws, maxNodes = 10)
        assertTrue(r.truncated)
        assertTrue(r.nodes.size <= 10)
    }

    @Test
    fun `深度上限触发截断`() {
        val ws = tmp.newFolder("deep")
        var cur = ws
        repeat(8) { cur = File(cur, "d$it").apply { mkdirs() } }
        File(cur, "bottom.txt").writeText("x")
        val r = FileTreeScanner.scan(ws, maxDepth = 4)
        assertTrue(r.truncated)
        assertFalse(r.nodes.any { it.depth > 4 })
    }

    @Test
    fun `symlink 跳过`() {
        val ws = root()
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("x")
        Files.createSymbolicLink(File(ws, "link").toPath(), outside.toPath())
        val r = FileTreeScanner.scan(ws)
        assertFalse(r.nodes.any { it.name == "link" })
    }

    @Test
    fun `空目录`() {
        val ws = tmp.newFolder("empty")
        val r = FileTreeScanner.scan(ws)
        assertTrue(r.nodes.isEmpty())
        assertFalse(r.truncated)
    }

    @Test
    fun `不存在的根返回空`() {
        val r = FileTreeScanner.scan(File(tmp.root, "nope"))
        assertTrue(r.nodes.isEmpty())
        assertFalse(r.truncated)
    }

    @Test
    fun `kind 与 size 填充`() {
        val r = FileTreeScanner.scan(root())
        val kt = r.nodes.first { it.path == "src/App.kt" }
        assertEquals("KT", kt.kind.label)
        assertEquals(FileCategory.CODE, kt.kind.category)
        assertNotNull(kt.sizeBytes)
        val src = r.nodes.first { it.path == "src" }
        assertNull(src.sizeBytes)
        assertTrue(src.isDirectory)
    }
}
