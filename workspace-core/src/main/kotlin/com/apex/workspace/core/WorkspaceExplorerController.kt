package com.apex.workspace.core

import com.apex.workspace.core.contract.CommitIdentity
import com.apex.workspace.core.contract.GitCommandRunner
import com.apex.workspace.core.contract.KitLogLevel
import com.apex.workspace.core.contract.KitLogger
import com.apex.workspace.core.contract.WorkspacePaths
import com.apex.workspace.core.contract.WorkspaceRootProvider
import com.apex.workspace.core.fs.ArtifactScanner
import com.apex.workspace.core.fs.FileKindResolver
import com.apex.workspace.core.fs.FileTreeScanner
import com.apex.workspace.core.git.GitEngine
import com.apex.workspace.core.model.ChangesSummary
import com.apex.workspace.core.model.CommitDetail
import com.apex.workspace.core.model.DiffFile
import com.apex.workspace.core.model.ExplorerTab
import com.apex.workspace.core.model.ExplorerUiState
import com.apex.workspace.core.model.FileChangeKind
import com.apex.workspace.core.model.FileNode
import com.apex.workspace.core.model.FilePreview
import com.apex.workspace.core.model.FeedEntry
import com.apex.workspace.core.model.GitFileEntry
import com.apex.workspace.core.model.GitFileStatus
import com.apex.workspace.core.model.TreeState
import com.apex.workspace.core.model.WorkspaceFileEvent
import com.apex.workspace.core.preview.PreviewLoader
import com.apex.workspace.core.watch.WatchKind
import com.apex.workspace.core.watch.WorkspaceWatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

// 工作区浏览器控制器：单一状态机（StateFlow of ExplorerUiState），
// 驱动 文件夹 / Git / 变更和产物 三个标签 + 实时跟随。
// 分层契约：IO 全部经注入（gitRunner / rootProvider / ioDispatcher）；
// 所有动作折叠进 state（busy / message / messageIsError），绝不向上抛
//（CancellationException 原样上抛是唯一例外）；watcher 仅在 start 与 stop 之间运行；
// 事件（agent 经 pushAgentEvent；watcher 自产）→ feed 直推，树立即重扫，
// git / changes 标脏：当前标签可见则去抖刷新，否则切回时刷新（惰性）。
class WorkspaceExplorerController(
    private val gitRunner: GitCommandRunner,
    private val rootProvider: WorkspaceRootProvider,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: KitLogger = KitLogger { _, _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    // 轮询 watcher 周期（SkillDirectoryWatcher 先例：指纹快照 diff，纯 JVM）
    private val pollMs: Long = 2_500L,
    // 提交身份（默认与宿主 GitCommitTool 一致）
    private val identity: CommitIdentity = CommitIdentity(),
) {

    private val _state = MutableStateFlow(ExplorerUiState())

    // 浏览器全局状态（UI 唯一数据源）
    val state: StateFlow<ExplorerUiState> = _state.asStateFlow()

    private val gitEngine = GitEngine(gitRunner, logger)
    private var allNodes: List<FileNode> = emptyList()

    private var watcher: WorkspaceWatcher? = null
    private var watchJob: Job? = null
    private var refreshDebounceJob: Job? = null
    private var started = false

    // 惰性加载脏标志：初始即为 true（从未加载视为待刷新）
    private var gitDirty = true
    private var changesDirty = true
    private var treeDirty = false

    private val seenCallIds = LinkedHashSet<String>()
    private var nextFeedId = 0L
    private var watchSeq = 0L
    private var busyCount = 0

    // ═══════════════ 生命周期 ═══════════════

    // 启动（幂等）：初始加载树与 Git；follow 开启时顺带启动 watcher。
    // loading 同步置位：launchIo 异步窗口内 GitUiState 默认 isRepo=false，
    // 会闪一帧误导性的「不是仓库」引导卡（宿主 Issue：Git 页面初始化）。
    fun start() {
        if (started) return
        started = true
        _state.update { it.copy(git = it.git.copy(loading = true)) }
        refreshTree()
        refreshGit()
        if (_state.value.followEnabled) startFollowing()
    }

    // 停止（幂等）：停 watcher + 取消订阅与在飞去抖刷新
    fun stop() {
        stopFollowing()
        refreshDebounceJob?.cancel()
        refreshDebounceJob = null
        started = false
    }

    // 工作区切换：清预览与差异、重扫树与 Git、标脏变更、重启 watcher
    fun onRootChanged() {
        _state.update {
            it.copy(
                preview = null,
                previewLoading = false,
                git = it.git.copy(diff = null, diffLoading = false, commitDetail = null),
                changes = it.changes.copy(inlineDiffs = emptyMap()),
                tree = it.tree.copy(expandedDirs = setOf(""), filter = ""),
            )
        }
        refreshTree()
        refreshGit()
        changesDirty = true
        if (_state.value.followEnabled) {
            stopFollowing()
            startFollowing()
        }
    }

    // ═══════════════ 导航 / 跟随 ═══════════════

    fun setTab(tab: ExplorerTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == ExplorerTab.GIT && gitDirty) refreshGit()
        if (tab == ExplorerTab.CHANGES && changesDirty) refreshChanges()
    }

    // 实时跟随开关：开 = watcher 启动 + 事件驱动刷新 + feed 直推
    fun toggleFollow() {
        val next = !_state.value.followEnabled
        _state.update { it.copy(followEnabled = next) }
        if (next) startFollowing() else stopFollowing()
    }

    // 宿主注入 agent 工具事件（callId 幂等去重；失败事件也进 feed）
    fun pushAgentEvent(event: WorkspaceFileEvent) {
        if (!rememberCallId(event.callId)) return
        handleFileEvent(event, agentOrigin = true)
    }

    // 消费一次性回执（message 置 null）
    fun consumeMessage() {
        _state.update { it.copy(message = null, messageIsError = false) }
    }

    // ═══════════════ 文件夹标签 ═══════════════

    // 重扫文件树（watcher 事件与下拉刷新用）
    fun refreshTree() {
        launchIo { doRefreshTree() }
    }

    // 树过滤词（空 = 全部；非空 = 路径包含匹配，忽略展开态直接平铺匹配链）
    fun setTreeFilter(query: String) {
        _state.update {
            val tree = it.tree.copy(filter = query)
            it.copy(tree = tree.copy(visibleNodes = computeVisible(allNodes, tree.expandedDirs, tree.filter)))
        }
    }

    // 展开 / 折叠目录；折叠时联动移除其子目录的展开态
    fun toggleDir(path: String) {
        _state.update {
            val old = it.tree.expandedDirs
            val next: Set<String> = if (path in old) {
                val prefix = "$path/"
                old - old.filter { d -> d == path || d.startsWith(prefix) }
            } else {
                old + path
            }
            val tree = it.tree.copy(expandedDirs = next)
            it.copy(tree = tree.copy(visibleNodes = computeVisible(allNodes, tree.expandedDirs, tree.filter)))
        }
    }

    // 打开文件预览（目录与越界折叠为 Failure 预览态）
    fun openFile(path: String) {
        _state.update { it.copy(previewLoading = true) }
        launchIo {
            val preview = loadPreview(path)
            _state.update { it.copy(preview = preview, previewLoading = false) }
        }
    }

    // 关闭预览
    fun closePreview() {
        _state.update { it.copy(preview = null, previewLoading = false) }
    }

    // 当前预览重载（跟随模式外部变更后自动刷新用）
    fun refreshPreview() {
        val current = _state.value.preview ?: return
        openFile(current.path)
    }

    // 相对路径 → 宿主 File（预览渲染解引用；走 WorkspacePaths.safeResolve）
    fun resolveFile(relPath: String): File? {
        val root = rootProvider.activeRoot() ?: return null
        return WorkspacePaths.safeResolve(root, relPath)
    }

    // ═══════════════ Git 标签 ═══════════════

    // 刷新 Git 聚合（status + log + branches；非仓库折叠为 isRepo=false）
    fun refreshGit() {
        launchIo { doRefreshGit() }
    }

    // 加载单文件差异面板（staged→cached；untracked→合成全增 hunk）
    fun loadDiff(path: String) {
        launchIo {
            _state.update { it.copy(git = it.git.copy(diffLoading = true)) }
            val entry = _state.value.git.entries.firstOrNull { it.path == path }
                ?: GitFileEntry("??", GitFileStatus.UNTRACKED, path, null, false)
            val diff = gitEngine.diffFor(rootProvider.activeRoot(), entry)
            _state.update { it.copy(git = it.git.copy(diff = diff, diffLoading = false)) }
        }
    }

    fun closeDiff() {
        _state.update { it.copy(git = it.git.copy(diff = null, diffLoading = false)) }
    }

    // ═══ 提交详情子页（Git 页面「页面完善」）═══

    // 历史条目点击 → 提交详情（git show 展开多文件差异）。
    // 先用历史快照立即占位（loading 态），差异就位后补齐。
    fun openCommitDetail(sha: String) {
        val commit = _state.value.git.log.firstOrNull { it.sha == sha || it.shortSha == sha }
            ?: return
        _state.update {
            it.copy(git = it.git.copy(commitDetail = CommitDetail(commit = commit, loading = true)))
        }
        launchIo {
            val files = gitEngine.commitDiff(commit.sha)
            _state.update { state ->
                val detail = state.git.commitDetail
                if (detail?.commit?.sha != commit.sha) {
                    // 用户已退出或换了别的提交——丢弃迟到结果
                    state
                } else {
                    state.copy(git = state.git.copy(commitDetail = detail.copy(files = files, loading = false)))
                }
            }
        }
    }

    fun closeCommitDetail() {
        _state.update { it.copy(git = it.git.copy(commitDetail = null)) }
    }

    // 暂存单文件
    fun stage(path: String) {
        launchIo {
            val r = gitEngine.stage(path)
            applyReceipt(r, ok = "已暂存", failPrefix = "暂存失败")
            doRefreshGit()
            changesDirty = true
        }
    }

    // 取消暂存单文件
    fun unstage(path: String) {
        launchIo {
            val r = gitEngine.unstage(path)
            applyReceipt(r, ok = "已取消暂存", failPrefix = "取消暂存失败")
            doRefreshGit()
            changesDirty = true
        }
    }

    // 暂存全部
    fun stageAll() {
        launchIo {
            val r = gitEngine.stageAll()
            applyReceipt(r, ok = "已暂存全部", failPrefix = "暂存全部失败")
            doRefreshGit()
            changesDirty = true
        }
    }

    // 提交全部变更
    fun commitAll(message: String) {
        if (message.isBlank()) {
            _state.update { it.copy(message = "提交说明不能为空", messageIsError = true) }
            return
        }
        if (_state.value.git.committing) return
        _state.update { it.copy(git = it.git.copy(committing = true)) }
        launchIo {
            val r = gitEngine.commitAll(message, identity)
            if (r.success) {
                val firstLine = r.stdout.lineSequence().firstOrNull()?.take(80)
                _state.update { it.copy(message = firstLine?.ifBlank { "已提交" } ?: "已提交", messageIsError = false) }
                doRefreshGit()
                doRefreshChanges()
            } else {
                _state.update {
                    it.copy(message = "提交失败：${r.stderr.take(200)}", messageIsError = true)
                }
            }
            _state.update { it.copy(git = it.git.copy(committing = false)) }
        }
    }

    // 非仓库态一键初始化
    fun initRepo() {
        launchIo {
            val r = gitEngine.initRepo()
            if (r.success) {
                _state.update { it.copy(message = "已初始化仓库", messageIsError = false) }
                doRefreshGit()
                doRefreshChanges()
            } else {
                _state.update { it.copy(message = "初始化失败：${r.stderr.take(200)}", messageIsError = true) }
            }
        }
    }

    // ═══════════════ 变更和产物标签 ═══════════════

    // 刷新变更汇总（status + numstat + untracked 行数）+ 产物扫描
    fun refreshChanges() {
        launchIo { doRefreshChanges() }
    }

    // 内联差异懒加载展开 / 收起
    fun toggleChangeExpand(path: String) {
        launchIo {
            val current = _state.value.changes.inlineDiffs
            if (path in current) {
                _state.update { it.copy(changes = it.changes.copy(inlineDiffs = current - path)) }
                return@launchIo
            }
            val change = _state.value.changes.summary.changes.firstOrNull { it.path == path }
            val entry = if (change != null) {
                GitFileEntry(
                    statusCode = pseudoCode(change.staged, change.status),
                    status = change.status,
                    path = change.path,
                    oldPath = change.oldPath,
                    staged = change.staged,
                )
            } else {
                GitFileEntry("??", GitFileStatus.UNTRACKED, path, null, false)
            }
            val loaded: DiffFile = gitEngine.diffFor(rootProvider.activeRoot(), entry) ?: return@launchIo
            _state.update { it.copy(changes = it.changes.copy(inlineDiffs = current + (path to loaded))) }
        }
    }

    // 打开产物预览（跳到文件夹标签并打开该文件预览）
    fun openArtifact(path: String) {
        setTab(ExplorerTab.FOLDER)
        openFile(path)
    }

    // ═══════════════ 内部：刷新体 ═══════════════

    // IO 调度 + busy 计数 + 错误折叠；CancellationException 原样上抛
    private fun launchIo(block: suspend () -> Unit): Job = scope.launch {
        busyCount++
        _state.update { it.copy(busy = true) }
        try {
            try {
                withContext(ioDispatcher) { block() }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logger.log(KitLogLevel.WARN, "WorkspaceExplorer", "操作失败：${t.message ?: t::class.simpleName}", t)
            }
        } finally {
            busyCount--
            _state.update { it.copy(busy = busyCount > 0) }
        }
    }

    private suspend fun doRefreshTree() {
        val root = rootProvider.activeRoot()
        val rootName = root?.let { canonicalName(it) } ?: ""
        val nodes: List<FileNode>
        var truncated = false
        if (root != null && root.isDirectory) {
            val result = FileTreeScanner.scan(root)
            nodes = result.nodes
            truncated = result.truncated
        } else {
            nodes = emptyList()
        }
        allNodes = nodes
        _state.update {
            val tree = it.tree
            it.copy(
                rootName = rootName,
                rootAvailable = root != null && root.exists(),
                rootPath = root?.let { f -> canonicalPath(f) },
                tree = TreeState(
                    rootName = rootName,
                    visibleNodes = computeVisible(nodes, tree.expandedDirs, tree.filter),
                    expandedDirs = tree.expandedDirs,
                    filter = tree.filter,
                    truncated = truncated,
                    totalNodes = nodes.size,
                ),
            )
        }
        treeDirty = false
    }

    private suspend fun doRefreshGit() {
        _state.update { it.copy(git = it.git.copy(loading = true)) }
        // 区分化：INFRA（环境未装/超时）与真实非仓库分开呈现 ——
        // 不可用时 unavailableReason 直达 UI（诚实引导），不再伪装成
        // 「不是仓库 + 初始化按钮」（初始化必然失败）。
        val status = gitEngine.statusDetailed()
        val unavailable = status.unavailableReason
        val log = if (status.info.isRepo && unavailable == null) gitEngine.log() else emptyList()
        val branches = if (status.info.isRepo && unavailable == null) gitEngine.branches() else emptyList()
        _state.update {
            it.copy(
                git = it.git.copy(
                    loading = false,
                    isRepo = status.info.isRepo,
                    unavailableReason = unavailable,
                    branch = status.info.branch,
                    ahead = status.info.ahead,
                    behind = status.info.behind,
                    entries = status.info.entries,
                    log = log,
                    branches = branches,
                    diff = null,
                    diffLoading = false,
                )
            )
        }
        gitDirty = false
    }

    private suspend fun doRefreshChanges() {
        _state.update { it.copy(changes = it.changes.copy(loading = true)) }
        val root = rootProvider.activeRoot()
        val summary: ChangesSummary = gitEngine.changes(root)
        val artifacts = root?.let { runCatching { ArtifactScanner.scan(it) }.getOrNull() }
            ?: com.apex.workspace.core.model.ArtifactsState.EMPTY
        _state.update {
            it.copy(
                changes = it.changes.copy(
                    loading = false,
                    isRepo = summary.isRepo,
                    summary = summary,
                    artifacts = artifacts,
                )
            )
        }
        changesDirty = false
    }

    private suspend fun loadPreview(path: String): FilePreview {
        val root = rootProvider.activeRoot()
            ?: return FilePreview.Failure(
                path,
                FileKindResolver.resolve(path.substringAfterLast('/')),
                "工作区不可用",
            )
        return runCatching { PreviewLoader.load(root, path) }.getOrElse {
            FilePreview.Failure(
                path,
                FileKindResolver.resolve(path.substringAfterLast('/')),
                "读取失败：${it.message ?: it::class.simpleName}",
            )
        }
    }

    // ═══════════════ 内部：事件管线 ═══════════════

    private fun handleFileEvent(event: WorkspaceFileEvent, agentOrigin: Boolean) {
        val entry = FeedEntry(nextFeedId++, event, agentOrigin)
        _state.update { it.copy(feed = (it.feed + entry).takeLast(FEED_CAP)) }
        if (!_state.value.followEnabled) return
        maybeRefreshTree()
        val previewPath = _state.value.preview?.path
        if (previewPath != null && event.path == previewPath) refreshPreview()
        gitDirty = true
        changesDirty = true
        val tab = _state.value.tab
        if (tab == ExplorerTab.GIT || tab == ExplorerTab.CHANGES) scheduleDirtyRefresh()
    }

    // 树刷新合并：在飞 / 排队期间新到事件不再叠加扫描
    private fun maybeRefreshTree() {
        if (treeDirty) return
        treeDirty = true
        launchIo {
            try {
                doRefreshTree()
            } finally {
                treeDirty = false
            }
        }
    }

    // git / changes 去抖刷新（300ms，cancel+delay 模式，多次事件合并）
    private fun scheduleDirtyRefresh() {
        refreshDebounceJob?.cancel()
        refreshDebounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            when (_state.value.tab) {
                ExplorerTab.GIT -> if (gitDirty) launchIo { doRefreshGit() }
                ExplorerTab.CHANGES -> if (changesDirty) launchIo { doRefreshChanges() }
                ExplorerTab.FOLDER -> Unit
            }
        }
    }

    private fun startFollowing() {
        val root = rootProvider.activeRoot()
        val w = watcher ?: WorkspaceWatcher(scope, pollMs, logger = logger).also { watcher = it }
        if (watchJob == null || watchJob?.isActive != true) {
            watchJob = scope.launch {
                w.events.collect { batch ->
                    for (ev in batch) {
                        val mapped = WorkspaceFileEvent(
                            callId = "watcher-${watchSeq++}",
                            kind = mapWatchKind(ev.kind),
                            toolId = "watcher",
                            path = ev.path,
                            success = true,
                            timestampMs = clock(),
                        )
                        handleFileEvent(mapped, agentOrigin = false)
                    }
                }
            }
        }
        w.start(root)
    }

    private fun stopFollowing() {
        watcher?.stop()
        watchJob?.cancel()
        watchJob = null
    }

    private fun mapWatchKind(kind: WatchKind): FileChangeKind = when (kind) {
        WatchKind.ADD -> FileChangeKind.CREATED
        WatchKind.CHANGE -> FileChangeKind.MODIFIED
        WatchKind.UNLINK -> FileChangeKind.DELETED
        WatchKind.ADD_DIR -> FileChangeKind.CREATED
        WatchKind.REMOVE_DIR -> FileChangeKind.DELETED
    }

    // callId 幂等去重（上限 500，先进先出）
    private fun rememberCallId(id: String): Boolean {
        if (!seenCallIds.add(id)) return false
        if (seenCallIds.size > SEEN_CAP) {
            val it = seenCallIds.iterator()
            it.next()
            it.remove()
        }
        return true
    }

    private fun applyReceipt(r: com.apex.workspace.core.contract.GitCommandResult, ok: String, failPrefix: String) {
        _state.update {
            if (r.success) {
                it.copy(message = ok, messageIsError = false)
            } else {
                it.copy(message = "$failPrefix：${r.stderr.take(200)}", messageIsError = true)
            }
        }
    }

    private fun pseudoCode(staged: Boolean, status: GitFileStatus): String = when {
        status == GitFileStatus.UNTRACKED -> "??"
        staged -> "M "
        else -> " M"
    }

    // ═══════════════ 内部：可见节点计算 ═══════════════

    private fun computeVisible(
        nodes: List<FileNode>,
        expandedDirs: Set<String>,
        filter: String,
    ): List<FileNode> {
        val q = filter.trim().lowercase()
        if (q.isEmpty()) {
            return nodes.filter { n -> ancestorDirs(n.path).all { it in expandedDirs } }
        }
        val matchPaths = nodes.filter { it.path.lowercase().contains(q) }.map { it.path }
        if (matchPaths.isEmpty()) return emptyList()
        val visible = mutableSetOf<String>()
        visible.addAll(matchPaths)
        for (p in matchPaths) visible.addAll(ancestorDirs(p))
        return nodes.filter { it.path in visible }
    }

    // 全部祖先目录相对路径（不含根）；「a/b/c.txt」→ [a/b, a]
    private fun ancestorDirs(path: String): List<String> {
        val out = mutableListOf<String>()
        var cur = path
        while (true) {
            val idx = cur.lastIndexOf('/')
            if (idx <= 0) break
            cur = cur.substring(0, idx)
            out.add(cur)
        }
        return out
    }

    private fun canonicalName(f: File): String =
        runCatching { f.canonicalFile.name }.getOrDefault(f.name)

    private fun canonicalPath(f: File): String =
        runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    private companion object {
        const val FEED_CAP = 50
        const val SEEN_CAP = 500
        const val DEBOUNCE_MS = 300L
    }
}
