package com.apex.workspace.core.contract

import java.io.File

/**
 * git 命令执行结果。
 *
 * 形状与宿主 Android-Guru-Agent 的 core:code-tools
 * com.apex.agent.core.codetools.git.GitCommandResult 逐字段一致 ——
 * 宿主侧适配器是一层零成本 lambda 包装，不存在语义漂移空间。
 */
data class GitCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    /** exitCode == 0 即成功；基础设施失败用 INFRA_EXIT_CODE 哨兵表达。 */
    val success: Boolean get() = exitCode == 0

    fun indicatesNotRepo(): Boolean =
        exitCode == NOT_REPO_EXIT_CODE && stderr.contains("not a git repository", ignoreCase = true)

    companion object {
        const val INFRA_EXIT_CODE = -1
        const val NOT_REPO_EXIT_CODE = 128
    }
}

/**
 * git 命令通道。宿主注入生产实现（PRoot Git 执行器薄包装），
 * 测试注入脚本回放或真实 git 进程。
 */
fun interface GitCommandRunner {
    suspend fun run(args: List<String>, timeoutMs: Long): GitCommandResult

    // 单参便捷重载：Kotlin 2.2 起 fun interface 抽象方法不允许默认参数值，
    // 原「timeoutMs 默认 30s」语义由该转发方法保留（偏离说明见 worklog Task 10-a）。
    suspend fun run(args: List<String>): GitCommandResult = run(args, DEFAULT_TIMEOUT_MS)

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * 工作区根目录提供者：返回当前激活工作区的宿主侧绝对路径；
 * 无激活工作区时返回 null（UI 呈现引导态）。
 *
 * 宿主实现：CodeWorkspaceManager.activeRoot() 的薄包装。
 */
fun interface WorkspaceRootProvider {
    fun activeRoot(): File?
}

/** 库日志级别。宿主适配转发到 AppLogger（LogCategory.TOOL，source = "WorkspaceKit"）。 */
enum class KitLogLevel { DEBUG, INFO, WARN, ERROR }

/** 注入式日志：库零依赖输出，宿主决定去向。 */
fun interface KitLogger {
    fun log(level: KitLogLevel, source: String, message: String, error: Throwable?)

    // 三参便捷重载：error 默认 null 的语义由该转发方法保留（Kotlin 2.2 限制，见 worklog）。
    fun log(level: KitLogLevel, source: String, message: String) = log(level, source, message, null)
}

/** 提交身份：与宿主 GitCommitTool / CodeGitPanelController 的默认值保持一致。 */
data class CommitIdentity(
    val name: String = "Guru Agent",
    val email: String = "agent@guru.local",
)

/**
 * 工作区相对路径安全解析（语义对齐宿主 FilePathSafety 的三级防线）：
 * 1) 词法校验：拒绝绝对路径、`..` 段、反斜杠诡计、.git 与 node_modules 前缀；
 * 2) canonical 解析；
 * 3) 包含性断言：结果必须仍在 root 内。
 * 任何一步失败返回 null —— 调用方折叠为引导/错误态，绝不向上抛。
 */
object WorkspacePaths {
    fun safeResolve(root: File, relPath: String): File? = runCatching {
        // 根本身：空串返回 root（TreeState 根目录、toggleDir("") 的约定）
        if (relPath.isEmpty()) return@runCatching root
        // 纯空白串不是合法相对路径
        if (relPath.isBlank()) return@runCatching null
        // NUL 字节对任何文件系统都是非法输入，直接拒绝
        if (relPath.contains('\u0000')) return@runCatching null
        // 统一反斜杠为正斜杠，阻断 Windows 分隔符诡计
        val normalized = relPath.replace('\\', '/')
        // 绝对路径拒绝
        if (normalized.startsWith("/")) return@runCatching null
        // .. 段与空段拒绝（空段来自 "a//b" 或尾斜杠 "a/"）
        if (normalized.split('/').any { it.isEmpty() || it == ".." }) return@runCatching null
        // 首段命中保留目录拒绝
        val first = normalized.substringBefore('/')
        if (first == ".git" || first == "node_modules") return@runCatching null
        val rootCanon = root.canonicalFile
        val target = root.resolve(normalized).canonicalFile
        // 包含性断言：等于 root 或严格位于 root 内
        if (target == rootCanon || target.path.startsWith(rootCanon.path + File.separator)) target else null
    }.getOrNull()
}
