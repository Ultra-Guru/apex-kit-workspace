package com.apex.workspace.explorer.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.workspace.core.model.FileCategory
import com.apex.workspace.core.model.FileKind

// ─────────────────────────────────────────────────────────────────────────────
// FileKind → 图标 / 主题色映射。
// 图标映射是纯函数（fileKindIconVector），单测可直接逐 category 断言；
// 颜色全部从 ColorScheme 令牌派生（primary/secondary/tertiary/error/
// onSurfaceVariant），零硬编码色值。
// ─────────────────────────────────────────────────────────────────────────────

// 纯函数版图标映射（@Composable 包装见 [fileKindIcon]，二者共享同一实现）。
internal fun fileKindIconVector(kind: FileKind): ImageVector = when (kind.category) {
    FileCategory.DIRECTORY -> Icons.Rounded.Folder
    FileCategory.CODE -> Icons.Rounded.Code
    FileCategory.MARKDOWN -> Icons.Rounded.Description
    FileCategory.JSON -> Icons.Rounded.DataObject
    FileCategory.CSV -> Icons.Rounded.TableChart
    FileCategory.XML -> Icons.Rounded.Code
    FileCategory.YAML -> Icons.Rounded.Code
    FileCategory.IMAGE -> Icons.Rounded.Image
    FileCategory.SVG -> Icons.Rounded.Image
    FileCategory.AUDIO -> Icons.Rounded.MusicNote
    FileCategory.VIDEO -> Icons.Rounded.Videocam
    FileCategory.PDF -> Icons.Rounded.PictureAsPdf
    FileCategory.ARCHIVE -> Icons.Rounded.FolderZip
    FileCategory.BINARY -> Icons.Rounded.Memory
    FileCategory.TEXT -> Icons.Rounded.InsertDriveFile
    FileCategory.UNKNOWN -> Icons.Rounded.HelpOutline
}

// Compose 侧便捷入口（签名沿用设计稿：@Composable fun fileKindIcon）。
@Composable
internal fun fileKindIcon(kind: FileKind): ImageVector = fileKindIconVector(kind)

// 图标配色：大类 → scheme 令牌。语义：
// 目录=secondary（结构）、代码族=tertiary（内容）、数据族=primary、
// 媒体=secondary、PDF=error（经典红）、普通文本与未知=onSurfaceVariant（弱化）。
internal fun fileKindTint(kind: FileKind, scheme: ColorScheme): Color = when (kind.category) {
    FileCategory.DIRECTORY -> scheme.secondary
    FileCategory.CODE -> scheme.tertiary
    FileCategory.MARKDOWN -> scheme.tertiary
    FileCategory.XML -> scheme.tertiary
    FileCategory.YAML -> scheme.tertiary
    FileCategory.JSON -> scheme.primary
    FileCategory.CSV -> scheme.primary
    FileCategory.IMAGE -> scheme.primary
    FileCategory.SVG -> scheme.primary
    FileCategory.AUDIO -> scheme.secondary
    FileCategory.VIDEO -> scheme.secondary
    FileCategory.PDF -> scheme.error
    FileCategory.ARCHIVE -> scheme.secondary
    FileCategory.BINARY -> scheme.onSurfaceVariant
    FileCategory.TEXT -> scheme.onSurfaceVariant
    FileCategory.UNKNOWN -> scheme.onSurfaceVariant
}
