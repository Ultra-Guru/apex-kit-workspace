package com.apex.workspace.core.fs

import com.apex.workspace.core.model.ArtifactEntry
import com.apex.workspace.core.model.ArtifactGroup
import com.apex.workspace.core.model.ArtifactsState
import java.io.File

/**
 * 构建产物扫描器：识别工作区里的构建输出目录与散装产物文件。
 *
 * 目录识别（深度 ≤ 2，命中后不再向下找产物目录）：dist / out / output / outputs /
 * release / debug / artifacts / pkg / target / build（build 仅收 build/outputs 子树，
 * build 根太常见）。散装产物：apk / aab / jar / aar / zip / tgz，深度 ≤ 2。
 * entry.path 始终为工作区相对路径；dir 为分组键（产物目录相对路径）。
 */
internal object ArtifactScanner {

    private val ARTIFACT_DIR_NAMES = setOf(
        "dist", "out", "output", "outputs", "release", "debug", "artifacts", "pkg", "target",
    )
    private val LOOSE_EXTS = setOf("apk", "aab", "jar", "aar", "zip", "tgz")
    private const val MAX_ENTRIES = 500
    private const val MAX_PER_DIR = 200

    fun scan(root: File): ArtifactsState {
        if (!root.isDirectory) return ArtifactsState.EMPTY
        val entries = ArrayList<ArtifactEntry>(32)
        var truncated = false
        // 已收集的产物目录（相对路径）—— 深度 2 扫描跳过，防 build/outputs 双收
        val collected = HashSet<String>()

        fun addEntry(file: File, relPath: String, groupDir: String) {
            if (entries.size >= MAX_ENTRIES) { truncated = true; return }
            entries.add(
                ArtifactEntry(
                    path = relPath,
                    dir = groupDir,
                    name = file.name,
                    kind = FileKindResolver.resolve(file.name),
                    sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                    lastModifiedMs = runCatching { file.lastModified() }.getOrDefault(0L),
                ),
            )
        }

        /** 递归收集 artifactDir 下全部文件；relPath 以工作区根为基准（队列携带各自前缀）。 */
        fun collectDir(artifactDir: File, prefix: String, groupDir: String) {
            val queue = ArrayDeque<Pair<File, String>>()
            queue.addLast(artifactDir to prefix)
            while (queue.isNotEmpty() && entries.size < MAX_ENTRIES) {
                val (current, curPrefix) = queue.removeFirst()
                val children = runCatching { current.listFiles() }?.getOrNull() ?: continue
                if (children.size > MAX_PER_DIR) truncated = true
                for (child in children) {
                    if (entries.size >= MAX_ENTRIES) { truncated = true; return }
                    val childRel = if (curPrefix.isEmpty()) child.name else "$curPrefix/${child.name}"
                    if (child.isDirectory) {
                        queue.addLast(child to childRel)
                    } else {
                        addEntry(child, childRel, groupDir)
                    }
                }
            }
        }

        // 深度 1：产物目录 + 散装产物文件
        val level1 = runCatching { root.listFiles() }?.getOrNull() ?: return ArtifactsState.EMPTY
        for (item in level1) {
            if (entries.size >= MAX_ENTRIES) { truncated = true; break }
            if (item.isDirectory) {
                when {
                    item.name in ARTIFACT_DIR_NAMES -> {
                        collectDir(item, item.name, item.name)
                        collected.add(item.name)
                    }
                    item.name == "build" -> {
                        // build 根太常见：只收 build/outputs 子树
                        val outputs = File(item, "outputs")
                        if (outputs.isDirectory) {
                            collectDir(outputs, "build/outputs", "build/outputs")
                            collected.add("build/outputs")
                        }
                    }
                }
            } else if (item.extension.lowercase() in LOOSE_EXTS) {
                addEntry(item, item.name, "")
            }
        }
        // 深度 2：子目录里的产物目录（如 app/release）+ 散装产物
        if (entries.size < MAX_ENTRIES) {
            outer@ for (dir in level1) {
                if (!dir.isDirectory || dir.name == ".git" || dir.name == "node_modules") continue
                val level2 = runCatching { dir.listFiles() }?.getOrNull() ?: continue
                for (sub in level2) {
                    if (entries.size >= MAX_ENTRIES) { truncated = true; break@outer }
                    if (sub.isDirectory) {
                        val subRel = "${dir.name}/${sub.name}"
                        if (sub.name in ARTIFACT_DIR_NAMES && subRel !in collected) {
                            collectDir(sub, subRel, subRel)
                            collected.add(subRel)
                        }
                    } else if (sub.extension.lowercase() in LOOSE_EXTS) {
                        addEntry(sub, "${dir.name}/${sub.name}", dir.name)
                    }
                }
            }
        }

        val grouped = entries
            .groupBy { it.dir }
            .map { (dir, list) -> ArtifactGroup(dir, list.sortedByDescending { it.lastModifiedMs }) }
            .sortedByDescending { group -> group.entries.maxOf { it.lastModifiedMs } }
        return ArtifactsState(grouped, truncated)
    }
}
