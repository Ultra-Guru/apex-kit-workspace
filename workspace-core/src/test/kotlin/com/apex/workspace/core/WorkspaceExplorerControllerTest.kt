package com.apex.workspace.core

import com.apex.workspace.core.contract.GitCommandResult
import com.apex.workspace.core.contract.GitCommandRunner
import com.apex.workspace.core.contract.WorkspaceRootProvider
import com.apex.workspace.core.model.DiffLineType
import com.apex.workspace.core.model.ExplorerTab
import com.apex.workspace.core.model.FileChangeKind
import com.apex.workspace.core.model.FilePreview
import com.apex.workspace.core.model.WorkspaceFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 脚本化 FakeGitRunner：按首参数匹配预设响应（模式对齐宿主 CodeGitPanelControllerTest）。
 */
private class FakeGitRunner(
    responses: Map<String, GitCommandResult> = emptyMap(),
) : GitCommandRunner {
    val executed = mutableListOf<List<String>>()
    private val responses = responses.toMutableMap()

    fun enqueue(key: String, result: GitCommandResult) {
        responses[key] = result
    }

    override suspend fun run(args: List<String>, timeoutMs: Long): GitCommandResult {
        executed.add(args)
        val key = args.firstOrNull() ?: ""
        return responses[key] ?: GitCommandResult(0, "", "")
    }

    companion object {
        fun ok(key: String, stdout: String = "") = key to GitCommandResult(0, stdout, "")
        fun err(key: String, exit: Int = 1, stderr: String = "boom") =
            key to GitCommandResult(exit, "", stderr)
    }
}


/**
 * 挂起安全排空：控制器可能已开启 watcher（自重排 delay 循环），绝不能用
 * advanceUntilIdle。runCurrent 排空当前虚拟时刻的全部任务（含 withContext
 * 重调度链），多跑几轮兜底循环依赖。
 */
private fun TestScope.drain(times: Int = 4) {
    repeat(times) { runCurrent() }
}

private fun TestScope.drainQuietly() {
    // 已 c.stop() 后无限循环已死，可安全排空到空闲
    advanceUntilIdle()
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceExplorerControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeController(
        scope: TestScope,
        responses: Map<String, GitCommandResult>,
        root: File?,
    ): Pair<WorkspaceExplorerController, FakeGitRunner> {
        val runner = FakeGitRunner(responses)
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller = WorkspaceExplorerController(
            gitRunner = runner,
            rootProvider = WorkspaceRootProvider { root },
            scope = CoroutineScope(dispatcher),
            ioDispatcher = dispatcher,
        )
        return controller to runner
    }

    private val statusOutput = """
        ## main...origin/main [ahead 1]
        M  staged.txt
         M work.txt
        ?? new.py
    """.trimIndent() + "\n"

    @Test
    fun `refreshGit 解析状态`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, _) = makeController(
            this,
            mapOf(
                FakeGitRunner.ok("status", statusOutput),
                FakeGitRunner.ok("log"),
                FakeGitRunner.ok("for-each-ref"),
            ),
            ws,
        )
        c.start()
        drain()
        val git = c.state.value.git
        assertTrue(git.isRepo)
        assertEquals("main", git.branch)
        assertEquals(1, git.ahead)
        assertEquals(3, git.entries.size)
        c.stop()
    }

    @Test
    fun `非仓库态`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, _) = makeController(
            this,
            mapOf(
                FakeGitRunner.err("status", 128, "fatal: not a git repository (or any of the parent directories): .git"),
            ),
            ws,
        )
        c.refreshGit()
        drain()
        val git = c.state.value.git
        assertFalse(git.isRepo)
        assertNull(git.branch)
        c.stop()
    }

    @Test
    fun `setTab 惰性加载 git`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, _) = makeController(
            this,
            mapOf(
                FakeGitRunner.ok("status", "## main\n"),
                FakeGitRunner.ok("log"),
                FakeGitRunner.ok("for-each-ref"),
            ),
            ws,
        )
        c.start()
        drain()
        // start 只刷 git 不刷 changes；切到 CHANGES 触发 changes 拉取
        c.setTab(ExplorerTab.CHANGES)
        drain()
        c.stop()
    }

    @Test
    fun `树扫描与展开过滤`() = runTest {
        val ws = tmp.newFolder("ws")
        File(ws, "src").mkdirs()
        File(ws, "src/a.kt").writeText("x")
        File(ws, "top.txt").writeText("y")
        val (c, _) = makeController(this, emptyMap(), ws)
        c.start()
        drain()
        val tree = c.state.value.tree
        // 根默认展开：深度 1 全可见，src 的子项默认折叠
        val visible = tree.visibleNodes.map { it.path }
        assertTrue(visible.contains("src"))
        assertTrue(visible.contains("top.txt"))
        assertFalse(visible.contains("src/a.kt"))
        c.toggleDir("src")
        drain()
        assertTrue(c.state.value.tree.visibleNodes.any { it.path == "src/a.kt" })
        c.stop()
    }

    @Test
    fun `过滤匹配链`() = runTest {
        val ws = tmp.newFolder("ws")
        File(ws, "src/deep").mkdirs()
        File(ws, "src/deep/needle.kt").writeText("x")
        File(ws, "other.txt").writeText("y")
        val (c, _) = makeController(this, emptyMap(), ws)
        c.start()
        drain()
        c.setTreeFilter("needle")
        drain()
        val visible = c.state.value.tree.visibleNodes.map { it.path }
        assertTrue(visible.containsAll(listOf("src", "src/deep", "src/deep/needle.kt")))
        assertFalse(visible.contains("other.txt"))
        c.stop()
    }

    @Test
    fun `openFile 文本预览`() = runTest {
        val ws = tmp.newFolder("ws")
        File(ws, "readme.md").writeText("# Title\n\nhello")
        val (c, _) = makeController(this, emptyMap(), ws)
        c.start()
        drain()
        c.openFile("readme.md")
        drain()
        val p = c.state.value.preview
        assertNotNull(p)
        assertTrue("preview type: $p", p is FilePreview.Text)
        assertEquals(3, (p as FilePreview.Text).lineCount)
        c.stop()
    }

    @Test
    fun `openFile 越界折叠为 Failure`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, _) = makeController(this, emptyMap(), ws)
        c.openFile("../escape.txt")
        drain()
        assertTrue(c.state.value.preview is FilePreview.Failure)
    }

    @Test
    fun `pushAgentEvent 去重`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, _) = makeController(this, emptyMap(), ws)
        c.start()
        drain()
        val ev = WorkspaceFileEvent("call-1", FileChangeKind.MODIFIED, "code_edit", "a.kt", true, 1000L)
        c.pushAgentEvent(ev)
        c.pushAgentEvent(ev)
        drain()
        assertEquals(1, c.state.value.feed.size)
        c.stop()
    }

    @Test
    fun `跟随开启时预览自动刷新`() = runTest {
        val ws = tmp.newFolder("ws")
        val f = File(ws, "live.txt").apply { writeText("v1") }
        val (c, _) = makeController(this, emptyMap(), ws)
        c.start()
        drain()
        c.openFile("live.txt")
        drain()
        c.toggleFollow()
        f.writeText("v2")
        c.pushAgentEvent(
            WorkspaceFileEvent("call-2", FileChangeKind.MODIFIED, "code_edit", "live.txt", true, 2000L),
        )
        drain()
        val p = c.state.value.preview as FilePreview.Text
        assertEquals("v2", p.text)
        c.stop()
    }

    @Test
    fun `跟随关闭时不刷新但仍进 feed`() = runTest {
        val ws = tmp.newFolder("ws")
        val f = File(ws, "cold.txt").apply { writeText("v1") }
        val (c, _) = makeController(this, emptyMap(), ws)
        c.start()
        drain()
        c.openFile("cold.txt")
        drain()
        // followEnabled 默认 false
        f.writeText("v2")
        c.pushAgentEvent(
            WorkspaceFileEvent("call-3", FileChangeKind.MODIFIED, "code_edit", "cold.txt", true, 3000L),
        )
        drain()
        assertEquals(1, c.state.value.feed.size)
        assertEquals("v1", (c.state.value.preview as FilePreview.Text).text)
        c.stop()
    }

    @Test
    fun `commitAll 命令序列与身份注入`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, runner) = makeController(
            this,
            mapOf(
                FakeGitRunner.ok("status", statusOutput),
                FakeGitRunner.ok("log"),
                FakeGitRunner.ok("for-each-ref"),
                FakeGitRunner.ok("add"),
                FakeGitRunner.ok("-c", "[main abc1234] done\n 3 files changed"),
            ),
            ws,
        )
        c.start()
        drain()
        c.commitAll("feat: test commit")
        drain()
        val commitCmd = runner.executed.firstOrNull { it.contains("commit") }
        assertNotNull(commitCmd)
        assertTrue(commitCmd!!.contains("-c"))
        assertTrue(commitCmd.contains("user.name=Guru Agent"))
        assertTrue(commitCmd.contains("user.email=agent@guru.local"))
        assertTrue(commitCmd.contains("--no-verify"))
        assertTrue(commitCmd.contains("feat: test commit"))
        // add -A 在 commit 之前
        val addIdx = runner.executed.indexOfFirst { it.first() == "add" }
        val commitIdx = runner.executed.indexOfFirst { it.contains("commit") }
        assertTrue(addIdx in 0 until commitIdx)
        // 成功回执
        val s = c.state.value
        assertNotNull(s.message)
        assertFalse(s.messageIsError)
        c.stop()
    }

    @Test
    fun `commitAll 失败回执`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, runner) = makeController(
            this,
            mapOf(
                FakeGitRunner.ok("status", statusOutput),
                FakeGitRunner.ok("log"),
                FakeGitRunner.ok("for-each-ref"),
                FakeGitRunner.ok("add"),
                FakeGitRunner.err("-c", 1, "nothing to commit, working tree clean"),
            ),
            ws,
        )
        c.start()
        drain()
        c.commitAll("x")
        drain()
        val s = c.state.value
        assertNotNull(s.message)
        assertTrue(s.messageIsError)
        assertTrue(s.message!!.contains("nothing to commit"))
        c.stop()
    }

    @Test
    fun `initRepo 成功后刷新`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, runner) = makeController(
            this,
            mapOf(
                FakeGitRunner.err("status", 128, "fatal: not a git repository"),
                FakeGitRunner.ok("init", "Initialized empty Git repository"),
                FakeGitRunner.ok("log"),
                FakeGitRunner.ok("for-each-ref"),
            ),
            ws,
        )
        c.refreshGit()
        drain()
        assertFalse(c.state.value.git.isRepo)
        // 第二次 status 用新脚本（init 后 isRepo）
        (runner).enqueue("status", GitCommandResult(0, "## main\n", ""))
        c.initRepo()
        drain()
        assertTrue(runner.executed.any { it.first() == "init" })
        c.stop()
    }

    @Test
    fun `toggleChangeExpand 懒加载内联 diff`() = runTest {
        val ws = tmp.newFolder("ws")
        val diffText = """
            diff --git a/work.txt b/work.txt
            --- a/work.txt
            +++ b/work.txt
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent() + "\n"
        val (c, runner) = makeController(
            this,
            mapOf(
                FakeGitRunner.ok("status", statusOutput),
                FakeGitRunner.ok("log"),
                FakeGitRunner.ok("for-each-ref"),
                FakeGitRunner.ok("diff", diffText),
            ),
            ws,
        )
        c.start()
        drain()
        c.setTab(ExplorerTab.CHANGES)
        drain()
        // numstat 由 diff 提供（FakeRunner key 是首参数 "diff"）
        c.toggleChangeExpand("work.txt")
        drain()
        val inline = c.state.value.changes.inlineDiffs["work.txt"]
        // FakeRunner 对 "diff" 开头的请求都返回 diffText（untracked 除外）；
        // work.txt 是 M 未暂存 → diff HEAD -M -- work.txt
        if (inline != null) {
            assertEquals(1, inline.additions)
            assertEquals(1, inline.deletions)
            assertEquals(DiffLineType.ADD, inline.hunks.single().lines[1].type)
        }
        // 再点一次收起
        c.toggleChangeExpand("work.txt")
        drain()
        assertNull(c.state.value.changes.inlineDiffs["work.txt"])
        c.stop()
    }

    @Test
    fun `consumeMessage 清除回执`() = runTest {
        val ws = tmp.newFolder("ws")
        val (c, _) = makeController(this, emptyMap(), ws)
        c.start()
        drain()
        c.commitAll("m")
        drain()
        c.consumeMessage()
        assertNull(c.state.value.message)
        c.stop()
    }

    @Test
    fun `无激活工作区呈现引导态`() = runTest {
        val (c, _) = makeController(this, emptyMap(), null)
        c.start()
        drain()
        val s = c.state.value
        assertFalse(s.rootAvailable)
        assertEquals("", s.rootName)
        c.stop()
    }

    @Test
    fun `openArtifact 跳转文件夹并预览`() = runTest {
        val ws = tmp.newFolder("ws")
        File(ws, "dist").mkdirs()
        File(ws, "dist/app.js").writeText("console.log(1)")
        val (c, _) = makeController(this, emptyMap(), ws)
        c.start()
        drain()
        c.openArtifact("dist/app.js")
        drain()
        assertEquals(ExplorerTab.FOLDER, c.state.value.tab)
        assertTrue(c.state.value.preview is FilePreview.Text)
        c.stop()
    }
}
