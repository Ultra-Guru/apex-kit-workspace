package com.apex.workspace.core.fs

import com.apex.workspace.core.model.FileKind
import com.apex.workspace.core.model.FileNode
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 工作区文件树扫描器（纯 JVM）。
 *
 * 输出扁平 DFS 列表：每个目录的直接子项按「目录优先、同级名称不区分大小写升序」
 * 排列；目录的子孙紧随其后（父先于子）。symlink 一律跳过（NOFOLLOW_LINKS 探测）；
 * 单条目 stat 失败静默跳过；maxNodes / maxDepth 触发即停止（truncated = true）。
 */
internal object FileTreeScanner {

    val DEFAULT_IGNORE: Set<String> = setOf(".git", "node_modules")

    data class ScanResult(val nodes: List<FileNode>, val truncated: Boolean)

    fun scan(
        root: File,
        maxNodes: Int = 2000,
        maxDepth: Int = 12,
        ignoreDirs: Set<String> = DEFAULT_IGNORE,
    ): ScanResult {
        if (!root.isDirectory) return ScanResult(emptyList(), false)
        val out = ArrayList<FileNode>(minOf(maxNodes, 256))
        var truncated = false

        fun stat(file: File): Pair<Long, Long>? = runCatching {
            val attrs = Files.readAttributes(
                file.toPath(),
                java.nio.file.attribute.BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            attrs.lastModifiedTime().toMillis() to attrs.size()
        }.getOrNull()

        fun emit(file: File, rel: String, isDir: Boolean, depth: Int): FileNode? {
            val (mtime, size) = stat(file) ?: return null
            return FileNode(
                path = rel,
                name = file.name,
                isDirectory = isDir,
                sizeBytes = if (isDir) null else size,
                lastModifiedMs = mtime,
                kind = if (isDir) FileKind.DIRECTORY else FileKindResolver.resolve(file.name),
                depth = depth,
            )
        }

        // 递归 DFS：children(dir, rel, depth) 返回是否被截断
        fun children(dir: File, rel: String, depth: Int): Boolean {
            val list = runCatching { dir.listFiles() }?.getOrNull() ?: return false
            val dirs = ArrayList<File>(4)
            val files = ArrayList<File>(4)
            for (child in list) {
                // NOFOLLOW 属性探测：symlink 一律跳过（目录与文件同规则）
                val attrs = runCatching {
                    Files.readAttributes(
                        child.toPath(),
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }.getOrNull() ?: continue
                if (attrs.isSymbolicLink) continue
                if (attrs.isDirectory) {
                    if (child.name in ignoreDirs) continue
                    dirs.add(child)
                } else {
                    files.add(child)
                }
            }
            dirs.sortBy { it.name.lowercase() }
            files.sortBy { it.name.lowercase() }
            // 正序输出：目录优先 + 同级升序
            for (child in dirs + files) {
                if (out.size >= maxNodes) return true
                val isDir = child in dirs
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                emit(child, childRel, isDir, depth)?.let(out::add)
                if (isDir && out.size >= maxNodes) return true
            }
            // 递归下探：升序目录依次展开（DFS：先列同级，再下探第一个目录）
            for (d in dirs) {
                val childRel = if (rel.isEmpty()) d.name else "$rel/${d.name}"
                if (depth >= maxDepth) {
                    // 深度达上限：目录本身已列出，不再下探；非空则视为截断
                    val nonEmpty = runCatching { d.listFiles()?.isNotEmpty() }.getOrDefault(false)
                    if (nonEmpty == true) truncated = true
                    continue
                }
                if (children(d, childRel, depth + 1)) return true
            }
            return false
        }

        // children 返回值只反映「节点上限截断」；深度上限截断已直接写入闭包变量 ——
        // 两者取或，绝不覆盖闭包已置位的 truncated
        if (children(root, "", 1)) truncated = true
        return ScanResult(out, truncated)
    }
}
