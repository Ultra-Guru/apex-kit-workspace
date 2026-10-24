package com.apex.workspace.core.git

import com.apex.workspace.core.contract.GitCommandResult
import com.apex.workspace.core.contract.GitCommandRunner
import com.apex.workspace.core.model.GitFileStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真 git 集成测试：ProcessBuilder 起 git 进程，完整链路（status / changes 行数 /
 * diffFor hunk / log / branches / stage / unstage / commit / init）。
 * git 不存在时整类跳过（沙箱与 GitHub runner 均预装 git）。
 */
class GitIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 直接跑 git 的 runner：cwd = 工作区目录。 */
    private class ProcessGitRunner(private val cwd: File) : GitCommandRunner {
        override suspend fun run(args: List<String>, timeoutMs: Long): GitCommandResult = try {
            val proc = ProcessBuilder(listOf("git") + args)
                .directory(cwd)
                .start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                GitCommandResult(-1, "", "timeout")
            } else {
                GitCommandResult(
                    proc.exitValue(),
                    proc.inputStream.bufferedReader().readText(),
                    proc.errorStream.bufferedReader().readText(),
                )
            }
        } catch (t: Throwable) {
            GitCommandResult(-1, "", t.message ?: "spawn failed")
        }
    }

    private fun gitAvailable(): Boolean = try {
        ProcessBuilder("git", "--version").start().waitFor(5, TimeUnit.SECONDS)
        true
    } catch (_: Throwable) {
        false
    }

    private fun newRepo(): File {
        val ws = tmp.newFolder("repo" + System.nanoTime())
        run(ws, "init", "-b", "main")
        run(ws, "config", "user.email", "test@test.local")
        run(ws, "config", "user.name", "Tester")
        return ws
    }

    private fun run(ws: File, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git") + args).directory(ws)
            .redirectErrorStream(false).start()
        proc.waitFor(10, TimeUnit.SECONDS)
        return proc.inputStream.bufferedReader().readText()
    }

    private fun commitAll(ws: File, msg: String) {
        run(ws, "add", "-A")
        run(ws, "commit", "-m", msg, "--no-verify")
    }

    @Test
    fun `全链路 - status 变更与行数`() {
        assumeTrue(gitAvailable())
        val ws = newRepo()
        File(ws, "a.txt").writeText("line1\nline2\n")
        File(ws, "b.kt").writeText("fun main() {}\n")
        commitAll(ws, "init commit")
        // 修改 a.txt（+2/-1），新增未跟踪 c.py（3 行），删除 b.kt
        File(ws, "a.txt").writeText("line1\nline2-new\nline3\nline4\n")
        File(ws, "c.py").writeText("x = 1\ny = 2\nz = 3\n")
        File(ws, "b.kt").delete()

        val engine = GitEngine(ProcessGitRunner(ws)) { _, _, _, _ -> }
        runBlocking {
            val status = engine.status()
            assertTrue(status.isRepo)
            assertEquals("main", status.branch)
            val byPath = status.entries.associateBy { it.path }
            assertEquals(GitFileStatus.MODIFIED, byPath["a.txt"]?.status)
            assertEquals(GitFileStatus.DELETED, byPath["b.kt"]?.status)
            assertEquals(GitFileStatus.UNTRACKED, byPath["c.py"]?.status)

            val changes = engine.changes(ws)
            assertEquals(3, changes.filesChanged)
            val a = changes.changes.single { it.path == "a.txt" }
            assertEquals(3, a.additions)
            assertEquals(1, a.deletions)
            val c = changes.changes.single { it.path == "c.py" }
            assertEquals(3, c.additions)
            assertEquals(0, c.deletions)

            val diff = engine.diffFor(ws, byPath["a.txt"]!!)
            assertNotNull(diff)
            assertEquals(3, diff!!.additions)
            assertEquals(1, diff.deletions)
            assertTrue(diff.hunks.isNotEmpty())

            val untrackedDiff = engine.diffFor(ws, byPath["c.py"]!!)
            assertNotNull(untrackedDiff)
            assertEquals(3, untrackedDiff!!.additions)
            assertEquals(0, untrackedDiff.deletions)
            assertTrue(untrackedDiff.hunks.single().lines.all { it.type == com.apex.workspace.core.model.DiffLineType.ADD })

            val log = engine.log()
            assertTrue(log.isNotEmpty())
            assertEquals("init commit", log[0].subject)
            assertEquals("Tester", log[0].author)

            val branches = engine.branches()
            assertTrue(branches.any { it.name == "main" && it.current })
        }
    }

    @Test
    fun `stage 与 unstage`() {
        assumeTrue(gitAvailable())
        val ws = newRepo()
        File(ws, "x.txt").writeText("1\n")
        commitAll(ws, "first")
        File(ws, "x.txt").writeText("1\n2\n")
        val engine = GitEngine(ProcessGitRunner(ws)) { _, _, _, _ -> }
        runBlocking {
            engine.stage("x.txt")
            val staged = engine.status()
            assertTrue(staged.entries.single().staged)
            engine.unstage("x.txt")
            val unstaged = engine.status()
            assertFalse(unstaged.entries.single().staged)
        }
    }

    @Test
    fun `commitAll 身份注入`() {
        assumeTrue(gitAvailable())
        // 隔离全局 git 配置，验证 -c 身份注入在无全局配置时依然可提交
        val ws = newRepo()
        File(ws, "y.txt").writeText("content\n")
        val engine = GitEngine(ProcessGitRunnerNoEnv(ws)) { _, _, _, _ -> }
        runBlocking {
            val r = engine.commitAll("feat: identity", com.apex.workspace.core.contract.CommitIdentity())
            assertTrue("commit failed: ${r.stderr}", r.success)
            val log = engine.log()
            assertEquals("Guru Agent", log[0].author)
            assertEquals("feat: identity", log[0].subject)
        }
    }

    @Test
    fun `非仓库识别`() {
        assumeTrue(gitAvailable())
        val plain = tmp.newFolder("plain" + System.nanoTime())
        val engine = GitEngine(ProcessGitRunner(plain)) { _, _, _, _ -> }
        runBlocking {
            val status = engine.status()
            assertFalse(status.isRepo)
            // 区分化：真实非仓库（git 正常报 128）不带 unavailableReason
            val detailed = engine.statusDetailed()
            assertFalse(detailed.info.isRepo)
            assertNull("真实非仓库不是环境问题", detailed.unavailableReason)
        }
    }

    @Test
    fun `环境不可用与真实非仓库区分`() {
        // INFRA（exit=-1，宿主 rootfs 未装 / proot 失败 / 超时的同款信号）：
        // unavailableReason 必须携带 stderr 引导文案，不再伪装成「不是仓库」。
        val infraRunner = GitCommandRunner { _, _ ->
            GitCommandResult(
                -1, "",
                "Ubuntu 环境未安装或未就绪——Git 工具依赖沙箱内预装的 git，请先在终端页安装 Ubuntu"
            )
        }
        val engine = GitEngine(infraRunner) { _, _, _, _ -> }
        runBlocking {
            val detailed = engine.statusDetailed()
            assertFalse(detailed.info.isRepo)
            assertNotNull("INFRA 失败必须带原因", detailed.unavailableReason)
            assertTrue(
                "原因保留宿主引导文案",
                detailed.unavailableReason!!.contains("Ubuntu")
            )
        }
    }

    @Test
    fun `INFRA 空文案兜底为通用原因`() {
        val infraRunner = GitCommandRunner { _, _ -> GitCommandResult(-1, "", "  ") }
        val engine = GitEngine(infraRunner) { _, _, _, _ -> }
        runBlocking {
            val detailed = engine.statusDetailed()
            assertNotNull(detailed.unavailableReason)
            assertTrue(detailed.unavailableReason!!.contains("不可用"))
        }
    }

    /** 清空 GIT_* 环境变量的 runner：验证身份注入不依赖全局配置。 */
    private class ProcessGitRunnerNoEnv(private val cwd: File) : GitCommandRunner {
        override suspend fun run(args: List<String>, timeoutMs: Long): GitCommandResult = try {
            val pb = ProcessBuilder(listOf("git") + args).directory(cwd)
            pb.environment().apply {
                remove("GIT_CONFIG_GLOBAL")
                remove("GIT_CONFIG_SYSTEM")
                remove("GIT_DIR")
                remove("GIT_WORK_TREE")
                remove("GIT_INDEX_FILE")
            }
            val proc = pb.start()
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            GitCommandResult(
                proc.exitValue(),
                proc.inputStream.bufferedReader().readText(),
                proc.errorStream.bufferedReader().readText(),
            )
        } catch (t: Throwable) {
            GitCommandResult(-1, "", t.message ?: "spawn failed")
        }
    }
}
