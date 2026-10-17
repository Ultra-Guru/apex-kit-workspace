package com.apex.workspace.core.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root() = tmp.newFolder("ws")

    @Test
    fun `空串返回根本身`() {
        val root = root()
        assertEquals(root.canonicalFile, WorkspacePaths.safeResolve(root, ""))
    }

    @Test
    fun `正常嵌套路径`() {
        val root = root()
        val target = WorkspacePaths.safeResolve(root, "src/main/App.kt")
        assertNotNull(target)
        assertEquals(
            root.resolve("src/main/App.kt").canonicalFile,
            target,
        )
    }

    @Test
    fun `点段被接受`() {
        // "./a.txt" 与 "a/./b.txt" 在词法上无越界，canonical 解析后等价
        val root = root()
        val t1 = WorkspacePaths.safeResolve(root, "./a.txt")
        val t2 = WorkspacePaths.safeResolve(root, "a.txt")
        assertEquals(t2, t1)
    }

    @Test
    fun `绝对路径拒绝`() {
        assertNull(WorkspacePaths.safeResolve(root(), "/etc/passwd"))
    }

    @Test
    fun `点点段拒绝 - 简单`() {
        assertNull(WorkspacePaths.safeResolve(root(), "../outside.txt"))
    }

    @Test
    fun `点点段拒绝 - 嵌套`() {
        assertNull(WorkspacePaths.safeResolve(root(), "a/b/../../../escape"))
    }

    @Test
    fun `反斜杠诡计统一处理`() {
        val root = root()
        // 反斜杠视为分隔词法归一：.. 诡计仍被 .. 段规则拦截
        assertNull(WorkspacePaths.safeResolve(root, "..\\..\\escape.txt"))
        assertNull(WorkspacePaths.safeResolve(root, "a\\\\..\\\\..\\\\x"))
        // 合法反斜杠路径（Windows 风格）归一为正斜杠解析
        val t = WorkspacePaths.safeResolve(root, "src\\App.kt")
        assertNotNull(t)
    }

    @Test
    fun `git 目录前缀拒绝`() {
        val root = root()
        assertNull(WorkspacePaths.safeResolve(root, ".git/config"))
        assertNull(WorkspacePaths.safeResolve(root, ".git"))
    }

    @Test
    fun `node_modules 前缀拒绝`() {
        assertNull(WorkspacePaths.safeResolve(root(), "node_modules/pkg/index.js"))
    }

    @Test
    fun `深层的 git 仍可访问`() {
        // .git 只保护首段：docs/.gitignore 这类文件合法
        assertNotNull(WorkspacePaths.safeResolve(root(), "docs/.gitignore"))
    }

    @Test
    fun `空段拒绝`() {
        val root = root()
        assertNull(WorkspacePaths.safeResolve(root, "a//b"))
        assertNull(WorkspacePaths.safeResolve(root, "a/"))
    }
}
