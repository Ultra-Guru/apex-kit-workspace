package com.apex.workspace.explorer

import androidx.compose.material3.lightColorScheme
import com.apex.workspace.core.model.FileCategory
import com.apex.workspace.core.model.FileKind
import com.apex.workspace.explorer.common.fileKindIconVector
import com.apex.workspace.explorer.common.fileKindTint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ─────────────────────────────────────────────────────────────────────────────
// 纯函数部分测试：每个 FileCategory 都有图标且 tint 是有效主题令牌色。
// ─────────────────────────────────────────────────────────────────────────────

class FileKindIconsTest {

    private val scheme = lightColorScheme()

    private fun kind(category: FileCategory): FileKind =
        FileKind(category, "X", null)

    @Test
    fun everyCategoryHasIcon() {
        FileCategory.entries.forEach { category ->
            val icon = fileKindIconVector(kind(category))
            assertNotNull("missing icon for $category", icon)
        }
    }

    @Test
    fun everyCategoryTintIsOpaqueSchemeColor() {
        FileCategory.entries.forEach { category ->
            val tint = fileKindTint(kind(category), scheme)
            assertTrue(
                "tint for $category should be opaque, was $tint",
                tint.alpha >= 0.99f,
            )
        }
    }

    @Test
    fun canonicalCategoryTintMapping() {
        assertEquals(scheme.secondary, fileKindTint(kind(FileCategory.DIRECTORY), scheme))
        assertEquals(scheme.tertiary, fileKindTint(kind(FileCategory.CODE), scheme))
        assertEquals(scheme.tertiary, fileKindTint(kind(FileCategory.MARKDOWN), scheme))
        assertEquals(scheme.primary, fileKindTint(kind(FileCategory.IMAGE), scheme))
        assertEquals(scheme.primary, fileKindTint(kind(FileCategory.SVG), scheme))
        assertEquals(scheme.onSurfaceVariant, fileKindTint(kind(FileCategory.TEXT), scheme))
        assertEquals(scheme.error, fileKindTint(kind(FileCategory.PDF), scheme))
    }
}
