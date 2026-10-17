package com.apex.workspace.core.watch

import com.apex.workspace.core.contract.KitLogLevel
import com.apex.workspace.core.contract.KitLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

// 轮询指纹 watcher：周期扫描（深度不超过 10，跳过 .git 与 node_modules，
// 条目上限 maxEntries），与前次快照 diff 出事件；非空批次经 250ms 去抖发射，
// 去抖窗口内新到事件合并后重新等待。纯 JVM 轮询，无 inotify 依赖
//（先例：宿主 SkillDirectoryWatcher）。
internal class WorkspaceWatcher(
    private val scope: CoroutineScope,
    private val pollMs: Long,
    private val maxEntries: Int = 4000,
    private val logger: KitLogger? = null,
) {

    private val _events = MutableSharedFlow<List<WorkspaceWatchEvent>>(extraBufferCapacity = 16)
    val events: SharedFlow<List<WorkspaceWatchEvent>> = _events.asSharedFlow()

    private var job: Job? = null
    private var rootRef: File? = null

    fun start(root: File?) {
        stop()
        rootRef = root
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // ── 主循环 ─────────────────────────────────────────

    private suspend fun loop() {
        var snapshot: Map<String, Fingerprint>? = null // null = 尚无基线，首轮静默
        while (true) {
            delay(pollMs)
            var pending: List<WorkspaceWatchEvent> = emptyList()
            try {
                val snap = fingerprint(rootRef)
                pending = if (snapshot == null) emptyList() else diff(snapshot!!, snap)
                snapshot = snap
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logWarn("轮询失败：${t.message ?: t::class.simpleName}")
            }
            if (pending.isEmpty()) continue
            while (true) {
                delay(DEBOUNCE_MS)
                var more: List<WorkspaceWatchEvent> = emptyList()
                try {
                    val snap = fingerprint(rootRef)
                    more = if (snapshot == null) emptyList() else diff(snapshot!!, snap)
                    snapshot = snap
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    logWarn("去抖轮询失败：${t.message ?: t::class.simpleName}")
                }
                if (more.isEmpty()) break
                pending = pending + more
            }
            if (pending.isNotEmpty()) _events.tryEmit(pending)
        }
    }

    // ── 指纹与差分 ─────────────────────────────────────

    private fun fingerprint(root: File?): Map<String, Fingerprint> {
        val out = LinkedHashMap<String, Fingerprint>()
        if (root == null || !root.isDirectory) return out
        visit(root, "", 1, out)
        return out
    }

    private fun visit(dir: File, rel: String, childDepth: Int, out: MutableMap<String, Fingerprint>) {
        if (out.size >= maxEntries) return
        val kids = dir.listFiles() ?: return
        for (k in kids) {
            if (out.size >= maxEntries) return
            val n = k.name
            if (n in IGNORE_DIR_NAMES) continue
            if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue
            val kRel = if (rel.isEmpty()) n else "$rel/$n"
            val isDir = k.isDirectory
            out[kRel] = Fingerprint(isDir, if (isDir) 0L else k.length(), k.lastModified())
            if (isDir && childDepth < MAX_DEPTH) visit(k, kRel, childDepth + 1, out)
        }
    }

    private fun diff(
        old: Map<String, Fingerprint>,
        new: Map<String, Fingerprint>,
    ): List<WorkspaceWatchEvent> {
        val out = mutableListOf<WorkspaceWatchEvent>()
        for ((path, nf) in new) {
            val of = old[path]
            when {
                of == null -> out.add(WorkspaceWatchEvent(if (nf.isDir) WatchKind.ADD_DIR else WatchKind.ADD, path))
                of.isDir != nf.isDir -> {
                    out.add(WorkspaceWatchEvent(if (of.isDir) WatchKind.REMOVE_DIR else WatchKind.UNLINK, path))
                    out.add(WorkspaceWatchEvent(if (nf.isDir) WatchKind.ADD_DIR else WatchKind.ADD, path))
                }
                !nf.isDir && (of.size != nf.size || of.mtime != nf.mtime) ->
                    out.add(WorkspaceWatchEvent(WatchKind.CHANGE, path))
                else -> Unit
            }
        }
        for ((path, of) in old) {
            if (path !in new) {
                out.add(WorkspaceWatchEvent(if (of.isDir) WatchKind.REMOVE_DIR else WatchKind.UNLINK, path))
            }
        }
        return out
    }

    private fun logWarn(msg: String) {
        logger?.log(KitLogLevel.WARN, "WorkspaceWatcher", msg)
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val MAX_DEPTH = 10
        val IGNORE_DIR_NAMES = setOf(".git", "node_modules")
    }
}

// 事件形状：kind + 工作区相对路径
internal enum class WatchKind { ADD, CHANGE, UNLINK, ADD_DIR, REMOVE_DIR }

internal data class WorkspaceWatchEvent(
    val kind: WatchKind,
    val path: String,
)

// 指纹：目录不计 size 与 mtime（目录 mtime 随子项变动，避免双重事件）
internal data class Fingerprint(
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)
