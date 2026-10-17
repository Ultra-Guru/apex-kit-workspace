package com.apex.workspace.explorer.preview

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.apex.workspace.core.WorkspaceExplorerController
import com.apex.workspace.core.model.FilePreview
import com.apex.workspace.explorer.R
import com.apex.workspace.explorer.common.fileNameOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// 图片 / SVG 预览：coil + SvgDecoder；棋盘格底；双指缩放 1-5x + 双击复位；
// SVG 渲染失败 → 回退按钮读源码以等宽文本展示（IO 走 Dispatchers.IO）。
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun ImagePreviewView(
    preview: FilePreview.Image,
    controller: WorkspaceExplorerController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    var file by remember(preview.path) { mutableStateOf<File?>(null) }
    LaunchedEffect(preview.path) {
        file = withContext(Dispatchers.IO) { controller.resolveFile(preview.path) }
    }

    var scale by remember(preview.path) { mutableFloatStateOf(1f) }
    var offsetX by remember(preview.path) { mutableFloatStateOf(0f) }
    var offsetY by remember(preview.path) { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        if (scale > 1.01f) {
            offsetX += pan.x
            offsetY += pan.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    var svgFailed by remember(preview.path) { mutableStateOf(false) }
    var loadFailed by remember(preview.path) { mutableStateOf(false) }
    var showSource by remember(preview.path) { mutableStateOf(false) }
    var sourceText by remember(preview.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(showSource, preview.path) {
        if (showSource && sourceText == null) {
            sourceText = withContext(Dispatchers.IO) {
                runCatching {
                    file?.takeIf { it.exists() }?.readText()?.take(400_000)
                }.getOrNull().orEmpty()
            }
        }
    }

    if (showSource && sourceText != null) {
        CodeText(
            text = sourceText.orEmpty(),
            showLineNumbers = false,
            modifier = modifier,
        )
        return
    }

    val checkerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawCheckerboard(checkerColor) }
                .transformable(transformState)
                .pointerInput(preview.path) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                    )
                },
        ) {
            AsyncImage(
                model = file,
                contentDescription = fileNameOf(preview.path),
                imageLoader = imageLoader,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                onError = {
                    if (preview.isSvg) svgFailed = true else loadFailed = true
                },
            )
        }
        when {
            svgFailed -> SvgFallbackCard(
                onShowSource = { showSource = true },
                modifier = Modifier.align(Alignment.Center),
            )

            loadFailed -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.workspace_preview_failed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// 棋盘格：12dp 网格的淡色交替块（透明 PNG/SVG 可视化边界）。
private fun DrawScope.drawCheckerboard(color: Color) {
    val cell = 12.dp.toPx()
    if (cell <= 0f) return
    val columns = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            if ((row + column) % 2 == 0) {
                drawRect(
                    color = color,
                    topLeft = Offset(column * cell, row * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

@Composable
private fun SvgFallbackCard(
    onShowSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = modifier
            .widthIn(max = 280.dp)
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.workspace_preview_failed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onShowSource) {
                Text(stringResource(R.string.workspace_preview_svg_fallback))
            }
        }
    }
}
