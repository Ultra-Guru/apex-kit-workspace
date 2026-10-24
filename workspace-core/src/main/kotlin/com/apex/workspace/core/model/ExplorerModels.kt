package com.apex.workspace.core.model

import com.apex.workspace.core.fs.FileKindResolver

/**
 * apex-kit-workspace 数据模型（冻结契约）。
 *
 * 本文件是库的公共数据面：workspace-core 引擎产出、workspace-explorer UI 消费、
 * 宿主 app 只读。改动任何字段都必须同步三方 + 核对调用方。
 */

// ═══════════════════════ 标签 ═══════════════════════

/** 工作区浏览器顶部切换栏的三个标签。 */
enum class ExplorerTab { FOLDER, GIT, CHANGES }

// ═══════════════════════ 文件类型 ═══════════════════════

/** 文件大类：决定预览渲染方式与徽标语义。 */
enum class FileCategory {
    DIRECTORY, TEXT, CODE, MARKDOWN, JSON, CSV, XML, YAML,
    IMAGE, SVG, AUDIO, VIDEO, PDF, ARCHIVE, BINARY, UNKNOWN,
}

/** 单个文件/目录的类型信息：大类 + 展示标签（如 "PY" / "IMG" / "MD"）+ 扩展名。 */
data class FileKind(
    val category: FileCategory,
    val label: String,
    val extension: String?,
) {
    companion object {
        val DIRECTORY: FileKind = FileKind(FileCategory.DIRECTORY, "DIR", null)

        /** 文件名 → 类型。约 110 个扩展名 + 特殊文件名（Dockerfile / Makefile /
         *  .gitignore / README 等）；未知扩展退化为 TEXT + 大写扩展名标签。 */
        fun resolve(fileName: String): FileKind = FileKindResolver.resolve(fileName)

        /** 文本类（TEXT/CODE/MARKDOWN/JSON/CSV/XML/YAML）可以走文本预览。 */
        fun isTextual(kind: FileKind): Boolean =
            kind.category in TEXTUAL_CATEGORIES

        private val TEXTUAL_CATEGORIES = setOf(
            FileCategory.TEXT, FileCategory.CODE, FileCategory.MARKDOWN,
            FileCategory.JSON, FileCategory.CSV, FileCategory.XML, FileCategory.YAML,
        )
    }
}

// ═══════════════════════ 文件树 ═══════════════════════

/** 树节点（扁平表示；父子关系由 path 前缀推导，parent = path.substringBeforeLast('/')）。 */
data class FileNode(
    val path: String,          // 工作区相对路径，posix 风格；根目录为 ""
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,      // 文件大小；目录为 null
    val lastModifiedMs: Long,
    val kind: FileKind,
    val depth: Int,            // 根的直接子项 depth = 1
)

/** 文件夹标签的树状态：扫描产物 + 展开/过滤的可见视图。 */
data class TreeState(
    val rootName: String,
    /** 已按 expandedDirs + filter 计算出的可见节点（目录优先、同层自然序）。 */
    val visibleNodes: List<FileNode>,
    val expandedDirs: Set<String>,   // 展开的目录相对路径集合（"" = 根，默认展开）
    val filter: String,
    /** 扫描被 maxNodes / maxDepth 截断（提示用户树不完整）。 */
    val truncated: Boolean,
    val totalNodes: Int,
) {
    companion object {
        val EMPTY = TreeState("", emptyList(), setOf(""), "", false, 0)
    }
}

// ═══════════════════════ 预览 ═══════════════════════

/** 多格式预览负载：sealed 层次驱动 UI 分派渲染。 */
sealed interface FilePreview {
    val path: String
    val kind: FileKind

    /** 文本/代码/Markdown/JSON/CSV：内容 + 行数 + 截断标记。 */
    data class Text(
        override val path: String,
        override val kind: FileKind,
        val text: String,
        val truncated: Boolean,
        val lineCount: Int,
        /** JSON 且解析成功时为美化后的文本（UI 可一键切换原始/美化）。 */
        val prettyJson: String?,
    ) : FilePreview

    /** 位图与 SVG：UI 用 coil 按 File 加载（isSvg 时需要 SvgDecoder）。 */
    data class Image(
        override val path: String,
        override val kind: FileKind,
        val sizeBytes: Long,
        val isSvg: Boolean,
    ) : FilePreview

    /** PDF：UI 用 android.graphics.pdf.PdfRenderer 渲染首页。 */
    data class Pdf(
        override val path: String,
        override val kind: FileKind,
        val sizeBytes: Long,
    ) : FilePreview

    /** 音频/视频：信息卡 + 时长（取不到为 null）。 */
    data class Media(
        override val path: String,
        override val kind: FileKind,
        val sizeBytes: Long,
        val durationMs: Long?,
    ) : FilePreview

    /** 压缩包/apk：条目清单（上限 200 条）。 */
    data class Archive(
        override val path: String,
        override val kind: FileKind,
        val sizeBytes: Long,
        val entries: List<ArchiveEntry>,
        val truncated: Boolean,
    ) : FilePreview

    /** 未知二进制：十六进制头部转储 + 大小。 */
    data class Binary(
        override val path: String,
        override val kind: FileKind,
        val sizeBytes: Long,
        val hexDump: String,
    ) : FilePreview

    /** 加载失败（文件缺失 / 越界 / 读错误）：UI 呈现原因。 */
    data class Failure(
        override val path: String,
        override val kind: FileKind,
        val reason: String,
    ) : FilePreview
}

data class ArchiveEntry(val name: String, val sizeBytes: Long)

// ═══════════════════════ Git 状态 ═══════════════════════

/** 变更性质（从 porcelain XY 码归并）。 */
enum class GitFileStatus { ADDED, MODIFIED, DELETED, RENAMED, COPIED, UNTRACKED, CONFLICT }

/** porcelain 单条变更：原始两字符码 + 归并状态 + 路径（rename 取新路径）。 */
data class GitFileEntry(
    val statusCode: String,      // 原始 XY（如 "M " / " M" / "??" / "UU" / "R "）
    val status: GitFileStatus,
    val path: String,
    val oldPath: String?,        // rename 时的旧路径
    val staged: Boolean,         // X 位非空白 = 已暂存
)

/** `git status --porcelain=v1 -b` 的完整解析产物。 */
data class GitStatusInfo(
    val isRepo: Boolean,
    val branch: String?,         // 分离头指针时为 "HEAD"；无提交时为分支名
    val ahead: Int,
    val behind: Int,
    val entries: List<GitFileEntry>,
)

// ═══════════════════════ 变更（含行数）═══════════════════════

/** 变更和产物标签的单文件条目：类型徽标 + 状态 + 精确增删行数。 */
data class ChangeEntry(
    val path: String,
    val oldPath: String?,
    val status: GitFileStatus,
    val kind: FileKind,
    val additions: Int?,         // null = 二进制或行数不可知
    val deletions: Int?,
    val staged: Boolean,
)

/** 变更汇总（统计卡数据源）。 */
data class ChangesSummary(
    val isRepo: Boolean,
    val changes: List<ChangeEntry>,
    val additionsTotal: Int,
    val deletionsTotal: Int,
    val filesChanged: Int,
) {
    companion object {
        val NOT_REPO = ChangesSummary(false, emptyList(), 0, 0, 0)
    }
}

// ═══════════════════════ Diff ═══════════════════════

enum class DiffLineType { CONTEXT, ADD, DELETE, META }

data class DiffLine(
    val type: DiffLineType,
    val oldNumber: Int?,
    val newNumber: Int?,
    val text: String,
)

data class DiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val header: String,          // "@@ -a,b +c,d @@ ..." 原文
    val lines: List<DiffLine>,
)

data class DiffFile(
    val path: String,
    val oldPath: String?,
    val hunks: List<DiffHunk>,
    val binary: Boolean,
    val additions: Int,
    val deletions: Int,
    val truncated: Boolean,      // 超过 maxChars 截断
)

// ═══════════════════════ 历史 / 分支 ═══════════════════════

data class CommitEntry(
    val sha: String,
    val shortSha: String,
    val author: String,
    val dateMs: Long,
    val subject: String,
    val body: String?,
)

data class BranchEntry(
    val name: String,            // 远程分支带 "origin/" 前缀
    val current: Boolean,
    val isRemote: Boolean,
    val lastCommitMs: Long?,
)

// ═══════════════════════ 产物 ═══════════════════════

/** 构建产物文件（dist / build outputs / 散装 apk-jar-aab 等）。 */
data class ArtifactEntry(
    val path: String,            // 工作区相对路径
    val dir: String,             // 分组目录（相对路径；根目录为 ""）
    val name: String,
    val kind: FileKind,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

data class ArtifactGroup(
    val dir: String,
    val entries: List<ArtifactEntry>,
)

data class ArtifactsState(
    val groups: List<ArtifactGroup>,
    val truncated: Boolean,
) {
    companion object {
        val EMPTY = ArtifactsState(emptyList(), false)
    }
}

// ═══════════════════════ 实时事件 ═══════════════════════

/** 文件变更事件性质。COMMAND = 无法静态判定改了什么的命令（全量刷新兜底）。 */
enum class FileChangeKind { CREATED, MODIFIED, DELETED, RENAMED, COMMAND, GIT_OP }

/**
 * 工作区文件事件（agent 工具操作或轮询 watcher 产生）。
 *
 * callId 是幂等键（agent 事件沿用 ToolCall 的 callId；watcher 事件用合成 id）。
 */
data class WorkspaceFileEvent(
    val callId: String,
    val kind: FileChangeKind,
    val toolId: String,          // code_write / code_edit / delete_file / watcher ...
    val path: String?,           // 工作区相对路径；命令类/解析失败为 null
    val success: Boolean,
    val timestampMs: Long,
    val durationMs: Long = 0,
    val diffText: String? = null,
    val detail: String? = null,
)

/** 事件流展示条目（feed 数据源）。agentOrigin = 来自 agent 工具（true）或 watcher。 */
data class FeedEntry(
    val id: Long,
    val event: WorkspaceFileEvent,
    val agentOrigin: Boolean,
)

// ═══════════════════════ UI 状态 ═══════════════════════

/** Git 标签聚合状态。 */
data class GitUiState(
    val loading: Boolean = false,
    val isRepo: Boolean = false,
    /**
     * git 通道不可用原因（null = 通道正常）。
     *
     * 区分「不是仓库」（isRepo=false + reason=null → 初始化引导卡）与
     * 「环境不可用」（reason 非空 → 诚实引导卡：rootfs 未装 / proot 失败 /
     * 超时等，文案来自宿主 runner 的 stderr 引导）。
     * 宿主 Issue（Git 页面初始化）：旧实现两态混叠，初始化按钮必然失败。
     */
    val unavailableReason: String? = null,
    val branch: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val entries: List<GitFileEntry> = emptyList(),
    val log: List<CommitEntry> = emptyList(),
    val branches: List<BranchEntry> = emptyList(),
    val diff: DiffFile? = null,
    val diffLoading: Boolean = false,
    val committing: Boolean = false,
)

/** 变更和产物标签聚合状态。 */
data class ChangesUiState(
    val loading: Boolean = false,
    val isRepo: Boolean = false,
    val summary: ChangesSummary = ChangesSummary.NOT_REPO,
    /** 内联差异懒加载：path → DiffFile。 */
    val inlineDiffs: Map<String, DiffFile> = emptyMap(),
    val artifacts: ArtifactsState = ArtifactsState.EMPTY,
)

/** 工作区浏览器全局状态（StateFlow 数据源，UI 只读）。 */
data class ExplorerUiState(
    val rootName: String = "",
    val rootAvailable: Boolean = false,
    /** 宿主侧绝对路径（预览 File 解引用用；仅展示不参与布局）。 */
    val rootPath: String? = null,
    val tab: ExplorerTab = ExplorerTab.FOLDER,
    val tree: TreeState = TreeState.EMPTY,
    val preview: FilePreview? = null,
    val previewLoading: Boolean = false,
    val git: GitUiState = GitUiState(),
    val changes: ChangesUiState = ChangesUiState(),
    val followEnabled: Boolean = false,
    val feed: List<FeedEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)
