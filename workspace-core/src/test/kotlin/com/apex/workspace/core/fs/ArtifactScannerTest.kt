package com.apex.workspace.core.fs

import com.apex.workspace.core.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtifactScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dist 目录产物`() {
        val ws = tmp.newFolder("ws")
        File(ws, "dist").mkdirs()
        File(ws, "dist/app.js").writeText("console.log(1)")
        File(ws, "dist/styles.css").writeText("body{}")
        File(ws, "src").mkdirs()
        File(ws, "src/main.kt").writeText("fun main() {}")
        val state = ArtifactScanner.scan(ws)
        assertEquals(1, state.groups.size)
        val g = state.groups[0]
        assertEquals("dist", g.dir)
        assertEquals(2, g.entries.size)
        assertTrue(g.entries.all { it.path.startsWith("dist/") })
        assertEquals(FileCategory.CODE, g.entries.first { it.name == "app.js" }.kind.category)
    }

    @Test
    fun `build 只收 outputs 子树`() {
        val ws = tmp.newFolder("ws")
        File(ws, "build/outputs/apk").mkdirs()
        File(ws, "build/tmp").mkdirs()
        File(ws, "build/outputs/apk/app-debug.apk").writeText("x")
        File(ws, "build/tmp/junk.txt").writeText("x")
        val state = ArtifactScanner.scan(ws)
        val g = state.groups.single()
        assertEquals("build/outputs", g.dir)
        assertEquals(1, g.entries.size)
        assertEquals("build/outputs/apk/app-debug.apk", g.entries[0].path)
    }

    @Test
    fun `散装产物文件`() {
        val ws = tmp.newFolder("ws")
        File(ws, "app-release.apk").writeText("x")
        File(ws, "lib.jar").writeText("x")
        File(ws, "readme.md").writeText("x")
        val state = ArtifactScanner.scan(ws)
        val g = state.groups.single { it.dir == "" }
        assertEquals(setOf("app-release.apk", "lib.jar"), g.entries.map { it.path }.toSet())
    }

    @Test
    fun `深度 2 产物目录`() {
        val ws = tmp.newFolder("ws")
        File(ws, "app/release").mkdirs()
        File(ws, "app/release/out.apk").writeText("x")
        val state = ArtifactScanner.scan(ws)
        val g = state.groups.single()
        assertEquals("app/release", g.dir)
        assertEquals("app/release/out.apk", g.entries[0].path)
    }

    @Test
    fun `组内 mtime 降序`() {
        val ws = tmp.newFolder("ws")
        File(ws, "dist").mkdirs()
        val a = File(ws, "dist/a.txt").apply { writeText("a") }
        val b = File(ws, "dist/b.txt").apply { writeText("b") }
        a.setLastModified(1_000)
        b.setLastModified(2_000)
        val state = ArtifactScanner.scan(ws)
        val g = state.groups.single()
        assertEquals("b.txt", g.entries[0].name)
        assertEquals("a.txt", g.entries[1].name)
    }

    @Test
    fun `无产物返回空`() {
        val ws = tmp.newFolder("ws")
        File(ws, "src").mkdirs()
        File(ws, "src/a.kt").writeText("x")
        val state = ArtifactScanner.scan(ws)
        assertTrue(state.groups.isEmpty())
        assertTrue(!state.truncated)
    }
}
