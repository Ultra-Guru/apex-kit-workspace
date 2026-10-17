package com.apex.workspace.core.fs

import com.apex.workspace.core.model.FileCategory
import com.apex.workspace.core.model.FileKind

/**
 * 文件名 → FileKind 解析表。
 *
 * 三级查找：特殊文件名表 → 扩展名表 → 兜底（未知扩展 = TEXT + 大写扩展标签；
 * 无扩展 = TEXT）。目录判定不在此处（调用方用 FileKind.DIRECTORY）。
 */
internal object FileKindResolver {

    /** 特殊文件名（大小写敏感优先，再做不区分大小写匹配）。 */
    private val specialNames: Map<String, FileKind> = buildMap {
        fun put(name: String, label: String) {
            put(name, FileKind(FileCategory.CODE, label, null))
        }
        put("Dockerfile", "DOCK")
        put("dockerfile", "DOCK")
        put("Makefile", "MK")
        put("makefile", "MK")
        put("gradlew", "GRADLE")
        put("CMakeLists.txt", "CMAKE")
        put("Jenkinsfile", "JKS")
        put("Vagrantfile", "VG")
        put(".gitignore", FileKind(FileCategory.TEXT, "GIT", null))
        put(".gitattributes", FileKind(FileCategory.TEXT, "GIT", null))
        put(".gitmodules", FileKind(FileCategory.TEXT, "GIT", null))
        put(".editorconfig", FileKind(FileCategory.TEXT, "ED", null))
        put(".dockerignore", FileKind(FileCategory.TEXT, "DOCK", null))
        put("LICENSE", FileKind(FileCategory.TEXT, "LIC", null))
        put("LICENCE", FileKind(FileCategory.TEXT, "LIC", null))
        put("NOTICE", FileKind(FileCategory.TEXT, "LIC", null))
        put(".env", FileKind(FileCategory.TEXT, "ENV", null))
        put(".npmrc", FileKind(FileCategory.TEXT, "NPM", null))
        put(".prettierrc", FileKind(FileCategory.TEXT, "PRC", null))
        put(".eslintrc", FileKind(FileCategory.TEXT, "PRC", null))
    }

    /** 前缀匹配的特殊名（.env.local / Dockerfile.dev 等）。 */
    private val specialPrefixes: List<Pair<String, FileKind>> = listOf(
        "Dockerfile" to FileKind(FileCategory.CODE, "DOCK", null),
        "README" to FileKind(FileCategory.MARKDOWN, "MD", null),
        "CHANGELOG" to FileKind(FileCategory.MARKDOWN, "MD", null),
        "CONTRIBUTING" to FileKind(FileCategory.MARKDOWN, "MD", null),
        ".env" to FileKind(FileCategory.TEXT, "ENV", null),
        ".git" to FileKind(FileCategory.TEXT, "GIT", null),
    )

    private val extensionTable: Map<String, FileCategory> = buildMap {
        // CODE
        for (ext in listOf(
            "kt", "kts", "java", "go", "rs", "c", "cpp", "cc", "cxx", "h", "hpp",
            "cs", "swift", "rb", "php", "py", "ts", "tsx", "js", "jsx", "mjs", "cjs",
            "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "sql", "html", "htm",
            "css", "scss", "sass", "less", "vue", "svelte", "dart", "scala", "lua",
            "pl", "r", "m", "mm", "groovy", "gradle", "proto", "tf", "hcl",
        )) put(ext, FileCategory.CODE)
        // MARKDOWN
        for (ext in listOf("md", "markdown", "mdx", "rst")) put(ext, FileCategory.MARKDOWN)
        // JSON
        for (ext in listOf("json", "geojson", "jsonc", "har", "ipynb")) put(ext, FileCategory.JSON)
        // CSV
        for (ext in listOf("csv", "tsv")) put(ext, FileCategory.CSV)
        // XML（SVG 单列）
        for (ext in listOf("xml", "plist", "xsl", "xslt", "csproj", "resx")) put(ext, FileCategory.XML)
        // YAML
        for (ext in listOf("yaml", "yml", "toml")) put(ext, FileCategory.YAML)
        // IMAGE（SVG 单列）
        for (ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "tiff", "tif", "heic", "avif")) {
            put(ext, FileCategory.IMAGE)
        }
        // SVG
        put("svg", FileCategory.SVG)
        // AUDIO
        for (ext in listOf("mp3", "wav", "ogg", "oga", "flac", "aac", "m4a", "opus", "amr", "mid")) {
            put(ext, FileCategory.AUDIO)
        }
        // VIDEO（.ts 归 TypeScript 代码类 —— 编码工作区语义优先）
        for (ext in listOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "3g2", "m4v", "flv", "wmv")) {
            put(ext, FileCategory.VIDEO)
        }
        // PDF
        put("pdf", FileCategory.PDF)
        // ARCHIVE
        for (ext in listOf("zip", "apk", "jar", "aab", "aar", "war", "ear", "epub", "tgz", "tar", "gz", "xz", "bz2", "7z", "rar", "iso")) {
            put(ext, FileCategory.ARCHIVE)
        }
        // BINARY
        for (ext in listOf("so", "dll", "dylib", "exe", "class", "bin", "dat", "db", "sqlite", "o", "a", "lib", "pyc", "wasm", "ttf", "otf")) {
            put(ext, FileCategory.BINARY)
        }
        // TEXT
        for (ext in listOf(
            "txt", "log", "ini", "conf", "cfg", "properties", "lock", "pem", "key",
            "crt", "patch", "diff", "gitignore", "input", "list", "text", "rst0",
            "csv0", "sum", "asc",
        )) put(ext, FileCategory.TEXT)
    }

    fun resolve(fileName: String): FileKind {
        val name = fileName.substringAfterLast('/')
        // 1. 精确特殊名
        specialNames[name]?.let { return it }
        val dot = name.lastIndexOf('.')
        if (dot <= 0) {
            // 无扩展（dot == -1）或以点开头的隐藏文件（dot == 0）
            if (dot == 0) {
                // 隐藏文件：.env.local 等前缀匹配；否则 TEXT
                specialPrefixes.firstOrNull { (prefix, _) ->
                    name.startsWith(prefix)
                }?.second?.let { return it }
                return FileKind(FileCategory.TEXT, name.take(4).uppercase().ifEmpty { "TXT" }, null)
            }
            // 前缀匹配（README.zh.md 会在后面扩展名分支命中；这里兜底 README / Dockerfile.dev）
            val lower = name.lowercase()
            specialPrefixes.firstOrNull { (prefix, _) -> lower.startsWith(prefix.lowercase()) }
                ?.second?.let { return it }
            return FileKind(FileCategory.TEXT, "TXT", null)
        }
        // 2. 扩展名
        val ext = name.substring(dot + 1).lowercase()
        val category = extensionTable[ext] ?: run {
            // 3. 前缀匹配（Dockerfile.dev）
            val lower = name.lowercase()
            specialPrefixes.firstOrNull { (prefix, _) -> lower.startsWith(prefix.lowercase()) }
                ?.second?.let { return it }
            return FileKind(FileCategory.TEXT, ext.uppercase(), ext)
        }
        return FileKind(category, ext.uppercase(), ext)
    }
}
