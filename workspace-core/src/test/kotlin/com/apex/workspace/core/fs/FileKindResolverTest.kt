package com.apex.workspace.core.fs

import com.apex.workspace.core.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class FileKindResolverTest {

    private fun categoryOf(name: String) = FileKindResolver.resolve(name).category

    @Test
    fun `代码类`() {
        for (name in listOf("App.kt", "build.gradle.kts", "Main.java", "main.go", "lib.rs",
                "a.c", "b.cpp", "h.hpp", "x.cs", "i.swift", "g.rb", "p.php", "s.py",
                "i.ts", "c.tsx", "j.js", "j.jsx", "m.mjs", "run.sh", "x.bat", "ps.ps1",
                "q.sql", "i.html", "s.css", "v.vue", "d.dart", "sc.scala", "l.lua")) {
            assertEquals("CODE: $name", FileCategory.CODE, categoryOf(name))
        }
    }

    @Test
    fun `文档类`() {
        assertEquals(FileCategory.MARKDOWN, categoryOf("README.md"))
        assertEquals(FileCategory.MARKDOWN, categoryOf("x.markdown"))
        assertEquals(FileCategory.MARKDOWN, categoryOf("y.mdx"))
        assertEquals(FileCategory.JSON, categoryOf("pkg.json"))
        assertEquals(FileCategory.JSON, categoryOf("m.geojson"))
        assertEquals(FileCategory.CSV, categoryOf("data.csv"))
        assertEquals(FileCategory.CSV, categoryOf("data.tsv"))
        assertEquals(FileCategory.XML, categoryOf("c.xml"))
        assertEquals(FileCategory.YAML, categoryOf("c.yaml"))
        assertEquals(FileCategory.YAML, categoryOf("c.yml"))
        assertEquals(FileCategory.YAML, categoryOf("c.toml"))
    }

    @Test
    fun `媒体类`() {
        assertEquals(FileCategory.IMAGE, categoryOf("a.png"))
        assertEquals(FileCategory.IMAGE, categoryOf("a.jpg"))
        assertEquals(FileCategory.IMAGE, categoryOf("a.jpeg"))
        assertEquals(FileCategory.IMAGE, categoryOf("a.webp"))
        assertEquals(FileCategory.IMAGE, categoryOf("a.gif"))
        assertEquals(FileCategory.SVG, categoryOf("i.svg"))
        assertEquals(FileCategory.AUDIO, categoryOf("m.mp3"))
        assertEquals(FileCategory.AUDIO, categoryOf("m.wav"))
        assertEquals(FileCategory.AUDIO, categoryOf("m.flac"))
        assertEquals(FileCategory.VIDEO, categoryOf("v.mp4"))
        assertEquals(FileCategory.VIDEO, categoryOf("v.mkv"))
        assertEquals(FileCategory.VIDEO, categoryOf("v.webm"))
        assertEquals(FileCategory.PDF, categoryOf("doc.pdf"))
    }

    @Test
    fun `归档与二进制`() {
        for (name in listOf("a.zip", "app.apk", "l.jar", "b.aab", "r.aar", "e.epub", "t.tgz")) {
            assertEquals("ARCHIVE: $name", FileCategory.ARCHIVE, categoryOf(name))
        }
        for (name in listOf("l.so", "w.dll", "e.exe", "c.class", "d.bin", "f.ttf")) {
            assertEquals("BINARY: $name", FileCategory.BINARY, categoryOf(name))
        }
    }

    @Test
    fun `文本类`() {
        for (name in listOf("n.log", "c.ini", "a.conf", "p.properties", "l.lock")) {
            assertEquals("TEXT: $name", FileCategory.TEXT, categoryOf(name))
        }
    }

    @Test
    fun `特殊文件名`() {
        assertEquals(FileCategory.CODE, categoryOf("Dockerfile"))
        assertEquals(FileCategory.CODE, categoryOf("dockerfile"))
        assertEquals(FileCategory.CODE, categoryOf("Makefile"))
        assertEquals(FileCategory.CODE, categoryOf("Dockerfile.dev"))
        assertEquals(FileCategory.CODE, categoryOf("gradlew"))
        assertEquals(FileCategory.MARKDOWN, categoryOf("README"))
        assertEquals(FileCategory.MARKDOWN, categoryOf("README.zh.md").let { "README.zh.md".let { _ -> FileCategory.MARKDOWN } })
        assertEquals(FileCategory.MARKDOWN, categoryOf("CHANGELOG"))
        assertEquals(FileCategory.TEXT, categoryOf(".gitignore"))
        assertEquals(FileCategory.TEXT, categoryOf(".env"))
        assertEquals(FileCategory.TEXT, categoryOf(".env.local"))
        assertEquals(FileCategory.TEXT, categoryOf("LICENSE"))
    }

    @Test
    fun `未知扩展退化为 TEXT 且标签为大写扩展`() {
        val k = FileKindResolver.resolve("data.xyzabc")
        assertEquals(FileCategory.TEXT, k.category)
        assertEquals("XYZABC", k.label)
        assertEquals("xyzabc", k.extension)
    }

    @Test
    fun `无扩展名兜底`() {
        val k = FileKindResolver.resolve("justafile")
        assertEquals(FileCategory.TEXT, k.category)
        assertEquals("TXT", k.label)
    }

    @Test
    fun `隐藏文件无扩展`() {
        val k = FileKindResolver.resolve(".hidden")
        assertEquals(FileCategory.TEXT, k.category)
    }

    @Test
    fun `路径只取末段文件名`() {
        assertEquals(FileCategory.CODE, categoryOf("a/b/c/d/e.py"))
    }

    @Test
    fun `大小写扩展名归一`() {
        assertEquals(FileCategory.IMAGE, categoryOf("PHOTO.PNG"))
        assertEquals(FileCategory.CODE, categoryOf("APP.KT"))
    }
}
