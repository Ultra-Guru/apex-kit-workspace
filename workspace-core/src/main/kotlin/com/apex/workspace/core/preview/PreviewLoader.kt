package com.apex.workspace.core.preview

import com.apex.workspace.core.contract.WorkspacePaths
import com.apex.workspace.core.fs.FileKindResolver
import com.apex.workspace.core.model.FileCategory
import com.apex.workspace.core.model.FileKind
import com.apex.workspace.core.model.FilePreview
import com.apex.workspace.core.model.ArchiveEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

// 多格式预览装配器：只读文件，永不抛异常（读失败折叠为 Failure 态）。
// 上限口径：文本 256KB（8KB NUL 嗅探转 Binary）；JSON 额外 512KB 内尝试美化；
// 图片 20MB；PDF 50MB；压缩包条目 200；二进制头部 256 字节十六进制转储。
internal object PreviewLoader {

    private const val TEXT_LIMIT = 256 * 1024
    private const val JSON_LIMIT = 512 * 1024
    private const val SNIFF = 8192
    private const val IMAGE_LIMIT = 20L * 1024 * 1024
    private const val PDF_LIMIT = 50L * 1024 * 1024
    private const val ZIP_ENTRY_CAP = 200
    private const val HEX_BYTES = 256

    private val prettyJson = Json { prettyPrint = true }

    fun load(root: File, relPath: String): FilePreview {
        val kind0 = FileKindResolver.resolve(relPath.substringAfterLast('/'))
        val resolved = WorkspacePaths.safeResolve(root, relPath)
            ?: return FilePreview.Failure(relPath, kind0, "路径不合法或越界")
        if (resolved.isDirectory) return FilePreview.Failure(relPath, kind0, "目录")
        if (!resolved.exists()) return FilePreview.Failure(relPath, kind0, "文件不存在")
        val kind = FileKindResolver.resolve(resolved.name)
        return try {
            dispatch(resolved, relPath, kind)
        } catch (t: Throwable) {
            FilePreview.Failure(relPath, kind, "读取失败：${t.message ?: t::class.simpleName}")
        }
    }

    private fun dispatch(f: File, relPath: String, kind: FileKind): FilePreview = when {
        FileKind.isTextual(kind) -> loadTextual(f, relPath, kind)
        kind.category == FileCategory.IMAGE || kind.category == FileCategory.SVG -> loadImage(f, relPath, kind)
        kind.category == FileCategory.AUDIO || kind.category == FileCategory.VIDEO ->
            FilePreview.Media(relPath, kind, f.length(), null)
        kind.category == FileCategory.PDF -> loadPdf(f, relPath, kind)
        kind.category == FileCategory.ARCHIVE -> loadArchive(f, relPath, kind)
        else -> loadBinaryFromFile(f, relPath, kind)
    }

    // ── 文本族 ─────────────────────────────────────────

    private fun loadTextual(f: File, relPath: String, kind: FileKind): FilePreview {
        val bytes = readHead(f, TEXT_LIMIT)
        if (hasNulInHead(bytes)) return loadBinaryFromFile(f, relPath, kind)
        val text = String(bytes, Charsets.UTF_8)
        val truncated = f.length() > TEXT_LIMIT
        val pretty = if (kind.category == FileCategory.JSON && f.length() <= JSON_LIMIT) {
            tryPrettyJson(text)
        } else {
            null
        }
        return FilePreview.Text(
            path = relPath,
            kind = kind,
            text = text,
            truncated = truncated,
            lineCount = countLines(text),
            prettyJson = pretty,
        )
    }

    private fun tryPrettyJson(text: String): String? = try {
        val element: JsonElement = Json.parseToJsonElement(text)
        prettyJson.encodeToString(JsonElement.serializer(), element)
    } catch (_: Exception) {
        null
    }

    // ── 图片 / PDF ────────────────────────────────────

    private fun loadImage(f: File, relPath: String, kind: FileKind): FilePreview {
        if (f.length() > IMAGE_LIMIT) return tooLarge(relPath, kind)
        return FilePreview.Image(relPath, kind, f.length(), isSvg = kind.category == FileCategory.SVG)
    }

    private fun loadPdf(f: File, relPath: String, kind: FileKind): FilePreview {
        if (f.length() > PDF_LIMIT) return tooLarge(relPath, kind)
        return FilePreview.Pdf(relPath, kind, f.length())
    }

    private fun tooLarge(relPath: String, kind: FileKind): FilePreview =
        FilePreview.Failure(relPath, kind, "文件过大")

    // ── 压缩包 ─────────────────────────────────────────

    private fun loadArchive(f: File, relPath: String, kind: FileKind): FilePreview = try {
        ZipFile(f).use { zf ->
            val all = zf.entries().toList()
            val entries = all.take(ZIP_ENTRY_CAP).map { ArchiveEntry(it.name, maxOf(0L, it.size)) }
            FilePreview.Archive(relPath, kind, f.length(), entries, all.size > ZIP_ENTRY_CAP)
        }
    } catch (t: Throwable) {
        FilePreview.Failure(relPath, kind, "无法读取压缩包：${t.message ?: t::class.simpleName}")
    }

    // ── 二进制 ─────────────────────────────────────────

    private fun loadBinaryFromFile(f: File, relPath: String, kind: FileKind): FilePreview {
        val bytes = readHead(f, HEX_BYTES)
        return FilePreview.Binary(relPath, kind, f.length(), hexDump(bytes))
    }

    private fun readHead(f: File, limit: Int): ByteArray {
        val want = if (f.length() > limit) limit.toLong() else f.length()
        val size = if (want > Int.MAX_VALUE) Int.MAX_VALUE else want.toInt()
        val out = ByteArray(size)
        FileInputStream(f).use { ins ->
            var off = 0
            while (off < size) {
                val n = ins.read(out, off, size - off)
                if (n < 0) break
                off += n
            }
        }
        return out
    }

    private fun hasNulInHead(bytes: ByteArray): Boolean {
        val head = if (bytes.size > SNIFF) SNIFF else bytes.size
        for (i in 0 until head) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    private fun countLines(text: String): Int = when {
        text.isEmpty() -> 0
        else -> text.count { it == '\n' } + (if (text.endsWith("\n")) 0 else 1)
    }

    // 十六进制转储：每行 16 字节 = 偏移 + 两组各 8 字节 + ASCII 栏
    private fun hexDump(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder()
        var off = 0
        while (off < bytes.size) {
            val end = if (off + 16 <= bytes.size) off + 16 else bytes.size
            sb.append(String.format("%06x  ", off))
            for (g in 0 until 2) {
                val from = off + g * 8
                for (i in 0 until 8) {
                    val idx = from + i
                    if (idx < end) sb.append(String.format("%02x ", bytes[idx])) else sb.append("   ")
                }
                if (g == 0) sb.append(' ')
            }
            sb.append('|')
            for (i in off until end) {
                val c = bytes[i].toInt() and 0xff
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append('|')
            off = end
            if (off < bytes.size) sb.append('\n')
        }
        return sb.toString()
    }
}
