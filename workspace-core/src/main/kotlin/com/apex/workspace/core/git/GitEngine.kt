package com.apex.workspace.core.git

import com.apex.workspace.core.contract.CommitIdentity
import com.apex.workspace.core.contract.GitCommandResult
import com.apex.workspace.core.contract.GitCommandRunner
import com.apex.workspace.core.contract.KitLogLevel
import com.apex.workspace.core.contract.KitLogger
import com.apex.workspace.core.contract.WorkspacePaths
import com.apex.workspace.core.fs.FileKindResolver
import com.apex.workspace.core.model.ChangesSummary
import com.apex.workspace.core.model.ChangeEntry
import com.apex.workspace.core.model.CommitEntry
import com.apex.workspace.core.model.DiffFile
import com.apex.workspace.core.model.DiffHunk
import com.apex.workspace.core.model.DiffLine
import com.apex.workspace.core.model.DiffLineType
import com.apex.workspace.core.model.GitFileEntry
import com.apex.workspace.core.model.GitFileStatus
import com.apex.workspace.core.model.GitStatusInfo
import com.apex.workspace.core.model.BranchEntry
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

// git 编排引擎：把 runner 的原始输出经解析器装配为模型。
// 错误纪律：所有异常折叠为结果对象 + logger 留痕，绝不向上抛
//（CancellationException 原样上抛是唯一例外）。
internal class GitEngine(
    private val gitRunner: GitCommandRunner,
    private val logger: KitLogger,
) {

    /**
     * status 聚合的区分化结果：**「不可用」≠「不是仓库」**。
     *
     * - [info]：解析产物（与旧契约一致；不可用时 isRepo=false）；
     * - [unavailableReason]：基础设施失败（exit=INFRA，如宿主 rootfs 未装、
     *   proot 不可用、超时）的人类可读原因。null = git 通道正常（是仓库或
     *   真实的非仓库）。
     *
     * 旧实现把 INFRA 与「不是 git 仓库」都折叠成 isRepo=false，Git 页面
     * 误导性亮出「初始化仓库」按钮——点了必然失败（真实原因是环境未装）。
     * 本结构让控制器能把两种状态分开呈现（宿主 Issue：Git 页面初始化）。
     */
    internal class StatusResult(val info: GitStatusInfo, val unavailableReason: String?)

    /** 兼容入口：只关心 info 的调用方（changes 聚合）。 */
    suspend fun status(): GitStatusInfo = statusDetailed().info

    /** 区分化入口：Git 页面刷新用（不可用原因直达 UI）。 */
    suspend fun statusDetailed(): StatusResult {
        val r = runOrInfra(listOf("status", "--porcelain=v1", "-b"))
        if (!r.success) {
            if (r.exitCode == GitCommandResult.INFRA_EXIT_CODE) {
                logWarn("status 不可用 exit=${r.exitCode} ${r.stderr.take(200)}")
                // stderr 即宿主 runner 的引导文案（含「请先安装 Ubuntu」等
                // 下一步指引）；兜底空串换通用文案。
                val reason = r.stderr.trim().ifEmpty { "git 执行通道不可用（exit ${r.exitCode}）" }
                return StatusResult(GitStatusInfo(false, null, 0, 0, emptyList()), reason)
            }
            // 真实 git 报错（128 非仓库 / 其它）：通道正常，按非仓库呈现
            logWarn("status 失败 exit=${r.exitCode} ${r.stderr.take(200)}")
            return StatusResult(GitStatusInfo(false, null, 0, 0, emptyList()), null)
        }
        return StatusResult(StatusParser.parse(r.stdout), null)
    }

    suspend fun changes(root: File?): ChangesSummary {
        val status = status()
        if (!status.isRepo) return ChangesSummary.NOT_REPO
        val records = numstatRecords()
        val numstat = records.associateBy { it.path }
        val entries = mutableListOf<ChangeEntry>()
        for (e in status.entries) {
            val rec = numstat[e.path]
            val adds: Int?
            val dels: Int?
            if (e.status == GitFileStatus.UNTRACKED) {
                val counts = untrackedLineCounts(root, e.path)
                adds = counts.first
                dels = counts.second
            } else {
                adds = rec?.additions
                dels = rec?.deletions
            }
            entries.add(
                ChangeEntry(
                    path = e.path,
                    oldPath = e.oldPath,
                    status = e.status,
                    kind = FileKindResolver.resolve(nameOf(e.path)),
                    additions = adds,
                    deletions = dels,
                    staged = e.staged,
                )
            )
        }
        // 防御：numstat 有而 status 无的记录（理论不该出现）
        val known = status.entries.map { it.path }.toSet()
        for (rec in records) {
            if (rec.path in known) continue
            entries.add(
                ChangeEntry(
                    path = rec.path,
                    oldPath = rec.oldPath,
                    status = GitFileStatus.MODIFIED,
                    kind = FileKindResolver.resolve(nameOf(rec.path)),
                    additions = rec.additions,
                    deletions = rec.deletions,
                    staged = false,
                )
            )
        }
        return ChangesSummary(
            isRepo = true,
            changes = entries,
            additionsTotal = entries.sumOf { it.additions ?: 0 },
            deletionsTotal = entries.sumOf { it.deletions ?: 0 },
            filesChanged = entries.size,
        )
    }

    suspend fun diffFor(root: File?, entry: GitFileEntry): DiffFile? {
        val path = entry.path
        if (!validPathspec(path)) return null
        if (entry.status == GitFileStatus.UNTRACKED) return untrackedDiff(root, entry)
        val first = if (entry.staged) {
            listOf("diff", "--cached", "-M", "--", path)
        } else {
            listOf("diff", "HEAD", "-M", "--", path)
        }
        var r = runOrInfra(first)
        if (!r.success && !entry.staged) {
            // 无 HEAD（新仓库）等情况降级为裸 diff
            r = runOrInfra(listOf("diff", "-M", "--", path))
        }
        if (!r.success) {
            logWarn("diff 失败 exit=${r.exitCode} ${r.stderr.take(200)}")
            return null
        }
        if (r.stdout.isBlank()) return null
        return UnifiedDiffParser.parse(r.stdout).firstOrNull()
    }

    suspend fun log(): List<CommitEntry> {
        val r = runOrInfra(
            listOf("log", "-n", "30", "--pretty=%H%x1f%h%x1f%an%x1f%at%x1f%s%x1f%b%x1e")
        )
        if (!r.success) {
            logWarn("log 失败 exit=${r.exitCode}")
            return emptyList()
        }
        return LogParser.parse(r.stdout)
    }

    suspend fun branches(): List<BranchEntry> {
        val r = runOrInfra(
            listOf(
                "for-each-ref",
                "--sort=-committerdate",
                "--format=%(HEAD)%00%(refname:short)%00%(committerdate:unix)",
                "refs/heads",
                "refs/remotes",
            )
        )
        if (!r.success) {
            logWarn("for-each-ref 失败 exit=${r.exitCode}")
            return emptyList()
        }
        return BranchParser.parse(r.stdout)
    }

    // ── 变更操作 ───────────────────────────────────────

    suspend fun stage(path: String): GitCommandResult {
        if (!validPathspec(path)) return illegal(path, "暂存")
        return runOrInfra(listOf("add", "--", path))
    }

    suspend fun unstage(path: String): GitCommandResult {
        if (!validPathspec(path)) return illegal(path, "取消暂存")
        val r = runOrInfra(listOf("reset", "HEAD", "--", path))
        if (r.exitCode == 128) {
            return runOrInfra(listOf("rm", "--cached", "--", path))
        }
        return r
    }

    suspend fun stageAll(): GitCommandResult = runOrInfra(listOf("add", "-A"))

    suspend fun commitAll(message: String, identity: CommitIdentity): GitCommandResult {
        val add = runOrInfra(listOf("add", "-A"))
        if (!add.success) logWarn("commit 前暂存失败：${add.stderr.take(200)}")
        return runOrInfra(
            listOf(
                "-c", "user.name=${identity.name}",
                "-c", "user.email=${identity.email}",
                "commit", "-m", message, "--no-verify",
            )
        )
    }

    suspend fun initRepo(): GitCommandResult = runOrInfra(listOf("init"))

    // ── 内部 ───────────────────────────────────────────

    private suspend fun numstatRecords(): List<NumstatParser.Record> {
        val head = runOrInfra(listOf("diff", "HEAD", "--numstat", "-M"))
        if (head.success) return NumstatParser.parse(head.stdout)
        val cached = runOrInfra(listOf("diff", "--cached", "--numstat", "-M"))
        if (cached.success) return NumstatParser.parse(cached.stdout)
        logWarn("numstat 降级后仍失败 exit=${cached.exitCode}")
        return emptyList()
    }

    // untracked 文件行数：root 缺失或越界或超限或二进制 → null 加 null
    private fun untrackedLineCounts(root: File?, path: String): Pair<Int?, Int?> {
        if (root == null) return null to null
        val f = WorkspacePaths.safeResolve(root, path) ?: return null to null
        if (!f.isFile) return null to null
        val len = f.length()
        if (len > TEXT_LIMIT) return null to null
        val bytes = try {
            f.readBytes()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logWarn("读取 untracked 失败：${t.message}")
            return null to null
        }
        if (hasNulInHead(bytes)) return null to null
        val text = String(bytes, Charsets.UTF_8)
        return countLines(text) to 0
    }

    // untracked 合成全增 hunk：上限 400 行 / 100KB
    private fun untrackedDiff(root: File?, entry: GitFileEntry): DiffFile? {
        if (root == null) return null
        val f = WorkspacePaths.safeResolve(root, entry.path) ?: return null
        if (!f.isFile) return null
        val size = f.length()
        if (size > DIFF_TEXT_LIMIT) {
            return DiffFile(entry.path, null, emptyList(), false, 0, 0, true)
        }
        val bytes = try {
            f.readBytes()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logWarn("读取 untracked diff 失败：${t.message}")
            return null
        }
        if (hasNulInHead(bytes)) {
            return DiffFile(entry.path, null, emptyList(), true, 0, 0, false)
        }
        val text = String(bytes, Charsets.UTF_8)
        val all = lineContents(text)
        val capped = all.take(MAX_DIFF_LINES)
        val truncated = all.size > MAX_DIFF_LINES
        val hunk = DiffHunk(
            oldStart = 0, oldLines = 0,
            newStart = 1, newLines = capped.size,
            header = "@@ -0,0 +1,${capped.size} @@",
            lines = capped.mapIndexed { i, content ->
                DiffLine(DiffLineType.ADD, null, i + 1, content)
            },
        )
        return DiffFile(entry.path, null, listOf(hunk), false, capped.size, 0, truncated)
    }

    private fun validPathspec(p: String): Boolean =
        p.isNotBlank() &&
            !p.startsWith("/") &&
            !p.startsWith("-") &&
            !p.split('/').any { it == ".." } &&
            !p.contains('\u0000')

    private fun illegal(path: String, op: String): GitCommandResult =
        GitCommandResult(GitCommandResult.INFRA_EXIT_CODE, "", "非法路径，拒绝$op：$path")

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    private fun hasNulInHead(bytes: ByteArray): Boolean {
        val head = if (bytes.size > SNIFF) SNIFF else bytes.size
        for (i in 0 until head) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    private fun logWarn(msg: String) {
        logger.log(KitLogLevel.WARN, "GitEngine", msg)
    }

    // 统一经 runner 执行；异常折叠为 INFRA 结果
    private suspend fun runOrInfra(args: List<String>): GitCommandResult =
        try {
            gitRunner.run(args)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logWarn("git 执行异常：${t.message ?: t::class.simpleName}")
            GitCommandResult(GitCommandResult.INFRA_EXIT_CODE, "", "执行异常：${t.message ?: t::class.simpleName}")
        }

    private companion object {
        const val TEXT_LIMIT = 256 * 1024
        const val SNIFF = 8192
        const val DIFF_TEXT_LIMIT = 100 * 1024
        const val MAX_DIFF_LINES = 400
    }
}

// 行工具：与 changes / diffFor 共用
internal fun countLines(text: String): Int = when {
    text.isEmpty() -> 0
    else -> text.count { it == '\n' } + (if (text.endsWith("\n")) 0 else 1)
}

internal fun lineContents(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split('\n')
    return if (text.endsWith("\n")) parts.dropLast(1) else parts
}
