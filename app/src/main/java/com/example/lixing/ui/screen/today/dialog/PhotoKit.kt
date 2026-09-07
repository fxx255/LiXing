package com.example.lixing.ui.screen.today.dialog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.lixing.ui.theme.LiXingRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 打卡照片：多张 + 预览 + 旋转。
 *
 * 存储：`daily_task.checkin_photo` 存 JSON 数组字符串（多张路径）。
 * 兼容旧的单路径字符串（解析失败时当作单张）。
 *
 * 旋转黑屏的根因是「连点导致并发读写同一文件 + recycle 了还在用的 bitmap」。
 * 这里用单飞（进行中禁用按钮）+ 写临时文件再原子重命名 + 不 recycle 解决。
 */

private val json = Json { ignoreUnknownKeys = true }

/** 多张路径 → JSON 数组字符串。 */
fun encodePhotos(paths: List<String>): String? =
    if (paths.isEmpty()) null
    else json.encodeToString(ListSerializer(String.serializer()), paths)

/** JSON 数组字符串 → 多张路径；兼容旧的单路径。 */
fun decodePhotos(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        json.decodeFromString<List<String>>(raw)
    }.getOrDefault(listOf(raw))
}

/** 降采样解码，maxDim 为目标最大边。 */
private fun decodeSampled(path: String, maxDim: Int): Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    var scale = 1
    while (maxOf(opts.outWidth, opts.outHeight) / scale > maxDim) scale *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = scale })
}.getOrNull()

/**
 * 旋转并原子写回：先写 .tmp 再 rename，避免并发读到半成品；不 recycle，交给 GC。
 */
private fun rotateAndSave(path: String, degrees: Int): Boolean = runCatching {
    val src = BitmapFactory.decodeFile(path) ?: return@runCatching false
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    val original = File(path)
    val tmp = File(original.parentFile, original.name + ".tmp")
    tmp.outputStream().use { out -> rotated.compress(Bitmap.CompressFormat.JPEG, 90, out) }
    tmp.renameTo(original)
    true
}.getOrDefault(false)

/**
 * 照片条：横向展示多张缩略图，每张可点预览/旋转、可删除。
 */
@Composable
fun PhotoStrip(
    paths: List<String>,
    modifier: Modifier = Modifier,
    /** 传 null 表示只读（历史回顾里不允许删照片）。 */
    onRemove: ((String) -> Unit)? = null,
) {
    if (paths.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.forEach { p ->
            Box {
                PhotoThumb(
                    path = p,
                    modifier = Modifier.size(64.dp),
                    canCrop = onRemove != null,
                )
                if (onRemove != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                            .clip(LiXingRadius.Pill)
                            .background(MaterialTheme.colorScheme.error)
                            .clickable { onRemove(p) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除照片",
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 单张缩略图，点击全屏预览/旋转。 */
@Composable
fun PhotoThumb(
    path: String?,
    modifier: Modifier = Modifier,
    /** 仅打卡编辑页开启；饮食页和历史回顾保持原行为。 */
    canCrop: Boolean = false,
) {
    if (path.isNullOrBlank()) return
    var showPreview by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }

    val thumb = remember(path, version) { decodeSampled(path, 240) }

    if (thumb != null) {
        Image(
            bitmap = thumb.asImageBitmap(),
            contentDescription = "打卡照片，点击预览",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(LiXingRadius.Card)
                .clickable { showPreview = true },
        )
    }

    if (showPreview) {
        PhotoPreviewDialog(
            path = path,
            canCrop = canCrop,
            onPhotoChanged = { version++ },
            onDismiss = { showPreview = false },
        )
    }
}

/** 全屏预览 + 旋转（单飞防黑屏）。 */
@Composable
private fun PhotoPreviewDialog(
    path: String,
    canCrop: Boolean,
    onPhotoChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var rotating by remember { mutableStateOf(false) }
    var showCrop by remember { mutableStateOf(false) }
    val bitmap = remember(path, version) { decodeSampled(path, 1600) }

    if (showCrop) {
        PhotoCropDialog(
            path = path,
            onCropped = {
                version++
                onPhotoChanged()
                showCrop = false
            },
            onDismiss = { showCrop = false },
        )
    } else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f),
            shape = LiXingRadius.Hero,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("预览", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    FilledIconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }

                if (bitmap != null) {
                    // 高度不再写死 420dp：平板横屏可用高度只有 400dp 出头，
                    // 写死会把底部按钮挤出屏幕。取「屏高的 55%」和 420dp 里更小的那个。
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = previewMaxHeight())
                            .clip(LiXingRadius.Card)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                } else {
                    Text("照片加载失败", color = MaterialTheme.colorScheme.onSurface)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canCrop) {
                        TextButton(enabled = !rotating, onClick = { showCrop = true }) {
                            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("裁剪")
                        }
                    }
                    TextButton(
                        enabled = !rotating,
                        onClick = {
                            rotating = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { rotateAndSave(path, 90) }
                                rotating = false
                                if (ok) {
                                    version++
                                    onPhotoChanged()
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (rotating) "旋转中…" else "旋转 90°")
                    }
                }
            }
        }
    }
}

/** 预览图最大高度：竖屏最多 420dp，横屏/平板按屏高比例收缩，保证下方按钮不被挤出屏幕。 */
@Composable
private fun previewMaxHeight(): Dp {
    val configuration = LocalConfiguration.current
    return minOf(420.dp, configuration.screenHeightDp.dp * 0.55f)
}

/** 裁剪视口（正方形）的最大边长：横屏时按屏高比例限制，否则正方形会高出屏幕。 */
@Composable
private fun cropViewportMaxSide(): Dp {
    val configuration = LocalConfiguration.current
    return minOf(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp * 0.56f)
}

private data class CropRatio(val label: String, val value: Float?)

private val cropRatios = listOf(
    CropRatio("自由", null),
    CropRatio("1:1", 1f),
    CropRatio("4:3", 4f / 3f),
    CropRatio("3:4", 3f / 4f),
    CropRatio("16:9", 16f / 9f),
    CropRatio("3:1", 3f),
)

private enum class CropDragMode {
    PAN_IMAGE,
    MOVE_CROP,
    TOP_LEFT,
    TOP_EDGE,
    TOP_RIGHT,
    RIGHT_EDGE,
    BOTTOM_RIGHT,
    BOTTOM_EDGE,
    BOTTOM_LEFT,
    LEFT_EDGE,
}

/** 可移动、可缩放、可切换长宽比的裁剪器。 */
@Composable
fun PhotoCropDialog(
    path: String,
    onCropped: () -> Unit,
    onDismiss: () -> Unit,
    onUseOriginal: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val preview = remember(path) { decodeSampled(path, 1600) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var imageOffset by remember { mutableStateOf(Offset.Zero) }
    var cropRect by remember { mutableStateOf(Rect.Zero) }
    var selectedRatio by remember { mutableStateOf(cropRatios[0]) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val viewportMaxSide = cropViewportMaxSide()

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(shape = LiXingRadius.Hero, color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("裁剪照片", style = MaterialTheme.typography.titleLarge)
                Text(
                    "双指缩放/平移照片；拖动框内移动选框，拖动四角改变大小",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (preview != null) {
                    val previewSize = IntSize(preview.width, preview.height)
                    val baseScale = if (viewport == IntSize.Zero) 1f else max(
                        viewport.width.toFloat() / preview.width,
                        viewport.height.toFloat() / preview.height,
                    )
                    val displayedWidth = preview.width * baseScale * zoom
                    val displayedHeight = preview.height * baseScale * zoom
                    val displayedLeft = (viewport.width - displayedWidth) / 2f + imageOffset.x
                    val displayedTop = (viewport.height - displayedHeight) / 2f + imageOffset.y

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 平板横屏下正方形视口不能跟着屏宽走（会高出屏幕），按屏高比例封顶。
                            .widthIn(max = viewportMaxSide)
                            .aspectRatio(1f)
                            .clip(LiXingRadius.Card)
                            .background(Color.Black)
                            .onSizeChanged { size ->
                                if (size == IntSize.Zero || size == viewport) return@onSizeChanged
                                val previous = viewport
                                viewport = size
                                imageOffset = constrainImageOffset(imageOffset, preview, size, zoom)
                                // 视口尺寸变了（旋转 / 分屏）：把已有选框按比例迁移到新坐标系，
                                // 否则选框还停留在旧尺寸的位置上，看起来就是「裁剪框错位」。
                                cropRect = if (cropRect == Rect.Zero || previous == IntSize.Zero) {
                                    centeredCropRect(
                                        viewport = size,
                                        ratio = selectedRatio.value,
                                        freeRatio = preview.width.toFloat() / preview.height,
                                    )
                                } else {
                                    scaleRect(cropRect, previous, size)
                                }
                            }
                            .pointerInput(preview, viewport) {
                                val handleRadius = 30.dp.toPx()
                                val minimumCropSize = 24.dp.toPx()
                                awaitEachGesture {
                                    val first = awaitFirstDown(requireUnconsumed = false)
                                    var mode = cropDragMode(first.position, cropRect, handleRadius)
                                    var wasMultiTouch = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break

                                        if (pressed.size >= 2) {
                                            wasMultiTouch = true
                                            val gestureZoom = event.calculateZoom().takeIf { it.isFinite() && it > 0f } ?: 1f
                                            val pan = event.calculatePan()
                                            val minimumZoom = minimumCropZoom(previewSize, viewport)
                                            val newZoom = (zoom * gestureZoom).coerceIn(minimumZoom, 8f)
                                            val appliedZoom = newZoom / zoom
                                            val viewportCenter = Offset(viewport.width / 2f, viewport.height / 2f)
                                            val focus = event.calculateCentroid()
                                            val focusFromCenter = focus - viewportCenter
                                            val requestedOffset =
                                                imageOffset * appliedZoom + focusFromCenter * (1f - appliedZoom) + pan
                                            val newOffset = constrainImageOffset(requestedOffset, preview, viewport, newZoom)
                                            zoom = newZoom
                                            imageOffset = newOffset
                                            cropRect = constrainCropRectToBounds(
                                                cropRect,
                                                visibleImageBounds(previewSize, viewport, newZoom, newOffset),
                                                selectedRatio.value,
                                            )
                                        } else {
                                            if (wasMultiTouch) mode = CropDragMode.PAN_IMAGE
                                            val change = pressed.first()
                                            val delta = change.position - change.previousPosition
                                            when (mode) {
                                                CropDragMode.PAN_IMAGE -> {
                                                    imageOffset = constrainImageOffset(
                                                        imageOffset + delta,
                                                        preview,
                                                        viewport,
                                                        zoom,
                                                    )
                                                }
                                                CropDragMode.MOVE_CROP -> {
                                                    cropRect = moveCropRect(
                                                        cropRect,
                                                        delta,
                                                        visibleImageBounds(previewSize, viewport, zoom, imageOffset),
                                                    )
                                                }
                                                else -> {
                                                    cropRect = resizeCropRectWithinBounds(
                                                        rect = cropRect,
                                                        mode = mode,
                                                        delta = delta,
                                                        bounds = visibleImageBounds(
                                                            previewSize,
                                                            viewport,
                                                            zoom,
                                                            imageOffset,
                                                        ),
                                                        ratio = selectedRatio.value,
                                                        minimumSize = minimumCropSize,
                                                    )
                                                }
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Draw in viewport pixels so the preview and crop mapping use exactly the same transform.
                        // A sized Image child is constrained back to the square parent and would distort portrait images.
                        Canvas(Modifier.matchParentSize()) {
                            drawImage(
                                image = preview.asImageBitmap(),
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(preview.width, preview.height),
                                dstOffset = IntOffset(displayedLeft.roundToInt(), displayedTop.roundToInt()),
                                dstSize = IntSize(
                                    displayedWidth.roundToInt().coerceAtLeast(1),
                                    displayedHeight.roundToInt().coerceAtLeast(1),
                                ),
                            )
                        }
                        Canvas(Modifier.matchParentSize()) {
                            if (cropRect == Rect.Zero) return@Canvas
                            val shade = Color.Black.copy(alpha = 0.58f)
                            val box = cropRect
                            drawRect(shade, Offset.Zero, Size(size.width, box.top))
                            drawRect(shade, Offset(0f, box.bottom), Size(size.width, size.height - box.bottom))
                            drawRect(shade, Offset(0f, box.top), Size(box.left, box.height))
                            drawRect(shade, Offset(box.right, box.top), Size(size.width - box.right, box.height))

                            val gridColor = Color.White.copy(alpha = 0.55f)
                            drawRect(
                                Color.White,
                                topLeft = box.topLeft,
                                size = box.size,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                            )
                            drawLine(gridColor, Offset(box.left + box.width / 3f, box.top), Offset(box.left + box.width / 3f, box.bottom), 1.dp.toPx())
                            drawLine(gridColor, Offset(box.left + box.width * 2f / 3f, box.top), Offset(box.left + box.width * 2f / 3f, box.bottom), 1.dp.toPx())
                            drawLine(gridColor, Offset(box.left, box.top + box.height / 3f), Offset(box.right, box.top + box.height / 3f), 1.dp.toPx())
                            drawLine(gridColor, Offset(box.left, box.top + box.height * 2f / 3f), Offset(box.right, box.top + box.height * 2f / 3f), 1.dp.toPx())
                            listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight).forEach { corner ->
                                drawCircle(Color.White, radius = 7.dp.toPx(), center = corner)
                                drawCircle(Color.Black.copy(alpha = 0.45f), radius = 3.dp.toPx(), center = corner)
                            }
                            val halfHandle = 12.dp.toPx()
                            val handleStroke = 5.dp.toPx()
                            drawLine(Color.White, Offset(box.center.x - halfHandle, box.top), Offset(box.center.x + halfHandle, box.top), handleStroke)
                            drawLine(Color.White, Offset(box.center.x - halfHandle, box.bottom), Offset(box.center.x + halfHandle, box.bottom), handleStroke)
                            drawLine(Color.White, Offset(box.left, box.center.y - halfHandle), Offset(box.left, box.center.y + halfHandle), handleStroke)
                            drawLine(Color.White, Offset(box.right, box.center.y - halfHandle), Offset(box.right, box.center.y + halfHandle), handleStroke)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        cropRatios.forEach { ratio ->
                            FilterChip(
                                selected = selectedRatio == ratio,
                                onClick = {
                                    selectedRatio = ratio
                                    if (ratio.value != null) {
                                        cropRect = constrainCropRectToBounds(
                                            centeredCropRect(viewport, ratio.value),
                                            visibleImageBounds(previewSize, viewport, zoom, imageOffset),
                                            ratio.value,
                                        )
                                    }
                                },
                                label = { Text(ratio.label) },
                            )
                        }
                    }
                } else {
                    Text("照片加载失败", color = MaterialTheme.colorScheme.error)
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                    onUseOriginal?.let { useOriginal ->
                        TextButton(onClick = useOriginal, enabled = !saving) { Text("使用原图") }
                    }
                    TextButton(
                        enabled = preview != null && viewport != IntSize.Zero && !saving,
                        onClick = {
                            val sourcePreview = preview ?: return@TextButton
                            saving = true
                            error = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        cropAndSave(
                                            path,
                                            sourcePreview,
                                            viewport,
                                            zoom,
                                            imageOffset,
                                            cropRect,
                                        )
                                    }
                                }
                                saving = false
                                result.onSuccess { onCropped() }
                                    .onFailure { error = it.message ?: "裁剪保存失败，请重试" }
                            }
                        },
                    ) { Text(if (saving) "保存中…" else "保存裁剪") }
                }
            }
        }
    }
}

/** 视口尺寸变化时把选框按比例迁移到新坐标系（旋转、分屏后不再错位）。 */
internal fun scaleRect(rect: Rect, from: IntSize, to: IntSize): Rect {
    if (from.width <= 0 || from.height <= 0 || to.width <= 0 || to.height <= 0) return rect
    val scaleX = to.width.toFloat() / from.width
    val scaleY = to.height.toFloat() / from.height
    return Rect(
        left = rect.left * scaleX,
        top = rect.top * scaleY,
        right = rect.right * scaleX,
        bottom = rect.bottom * scaleY,
    )
}

internal fun minimumCropZoom(previewSize: IntSize, viewport: IntSize): Float {
    if (previewSize.width <= 0 || previewSize.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return 1f
    val widthScale = viewport.width.toFloat() / previewSize.width
    val heightScale = viewport.height.toFloat() / previewSize.height
    val coverScale = max(widthScale, heightScale)
    val fitScale = min(widthScale, heightScale)
    return (fitScale / coverScale).coerceIn(0.01f, 1f)
}

/** Visible photo pixels inside the square editor, excluding any letterbox area. */
internal fun visibleImageBounds(
    previewSize: IntSize,
    viewport: IntSize,
    zoom: Float,
    imageOffset: Offset,
): Rect {
    if (previewSize.width <= 0 || previewSize.height <= 0 || viewport.width <= 0 || viewport.height <= 0) {
        return Rect.Zero
    }
    val baseScale = max(
        viewport.width.toFloat() / previewSize.width,
        viewport.height.toFloat() / previewSize.height,
    )
    val displayedWidth = previewSize.width * baseScale * zoom
    val displayedHeight = previewSize.height * baseScale * zoom
    val left = (viewport.width - displayedWidth) / 2f + imageOffset.x
    val top = (viewport.height - displayedHeight) / 2f + imageOffset.y
    return Rect(
        left = max(0f, left),
        top = max(0f, top),
        right = min(viewport.width.toFloat(), left + displayedWidth),
        bottom = min(viewport.height.toFloat(), top + displayedHeight),
    )
}

private fun constrainImageOffset(
    requested: Offset,
    bitmap: Bitmap,
    viewport: IntSize,
    zoom: Float,
): Offset {
    if (viewport == IntSize.Zero) return Offset.Zero
    val baseScale = max(
        viewport.width.toFloat() / bitmap.width,
        viewport.height.toFloat() / bitmap.height,
    )
    val maxX = ((bitmap.width * baseScale * zoom - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((bitmap.height * baseScale * zoom - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(
        requested.x.coerceIn(-maxX, maxX),
        requested.y.coerceIn(-maxY, maxY),
    )
}

private fun centeredCropRect(viewport: IntSize, ratio: Float?, freeRatio: Float = 1f): Rect {
    if (viewport == IntSize.Zero) return Rect.Zero
    val targetRatio = (ratio ?: freeRatio).coerceIn(0.05f, 20f)
    val maxWidth = viewport.width * 0.82f
    val maxHeight = viewport.height * 0.82f
    val width: Float
    val height: Float
    if (maxWidth / maxHeight > targetRatio) {
        height = maxHeight
        width = height * targetRatio
    } else {
        width = maxWidth
        height = width / targetRatio
    }
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun cropDragMode(position: Offset, rect: Rect, handleRadius: Float): CropDragMode {
    fun near(point: Offset): Boolean = (position - point).getDistance() <= handleRadius
    return when {
        near(rect.topLeft) -> CropDragMode.TOP_LEFT
        near(rect.topRight) -> CropDragMode.TOP_RIGHT
        near(rect.bottomLeft) -> CropDragMode.BOTTOM_LEFT
        near(rect.bottomRight) -> CropDragMode.BOTTOM_RIGHT
        near(Offset(rect.center.x, rect.top)) -> CropDragMode.TOP_EDGE
        near(Offset(rect.right, rect.center.y)) -> CropDragMode.RIGHT_EDGE
        near(Offset(rect.center.x, rect.bottom)) -> CropDragMode.BOTTOM_EDGE
        near(Offset(rect.left, rect.center.y)) -> CropDragMode.LEFT_EDGE
        rect.contains(position) -> CropDragMode.MOVE_CROP
        else -> CropDragMode.PAN_IMAGE
    }
}

private fun constrainCropRectToBounds(rect: Rect, bounds: Rect, ratio: Float?): Rect {
    if (rect == Rect.Zero || bounds.width <= 0f || bounds.height <= 0f) return rect
    val width: Float
    val height: Float
    if (ratio == null) {
        width = min(rect.width, bounds.width)
        height = min(rect.height, bounds.height)
    } else {
        val safeRatio = ratio.coerceAtLeast(0.01f)
        var candidateWidth = min(rect.width, bounds.width)
        var candidateHeight = candidateWidth / safeRatio
        if (candidateHeight > bounds.height) {
            candidateHeight = bounds.height
            candidateWidth = candidateHeight * safeRatio
        }
        width = candidateWidth
        height = candidateHeight
    }
    val left = (rect.center.x - width / 2f).coerceIn(bounds.left, bounds.right - width)
    val top = (rect.center.y - height / 2f).coerceIn(bounds.top, bounds.bottom - height)
    return Rect(left, top, left + width, top + height)
}

private fun moveCropRect(rect: Rect, delta: Offset, bounds: Rect): Rect {
    if (bounds.width <= 0f || bounds.height <= 0f) return rect
    val dx = delta.x.coerceIn(bounds.left - rect.left, bounds.right - rect.right)
    val dy = delta.y.coerceIn(bounds.top - rect.top, bounds.bottom - rect.bottom)
    return rect.translate(Offset(dx, dy))
}

private fun resizeCropRectWithinBounds(
    rect: Rect,
    mode: CropDragMode,
    delta: Offset,
    bounds: Rect,
    ratio: Float?,
    minimumSize: Float,
): Rect {
    if (bounds.width <= 0f || bounds.height <= 0f) return rect
    val localRect = rect.translate(-bounds.topLeft)
    val localViewport = IntSize(bounds.width.toInt().coerceAtLeast(1), bounds.height.toInt().coerceAtLeast(1))
    val effectiveMinimum = min(minimumSize, min(bounds.width, bounds.height)).coerceAtLeast(1f)
    val resized = resizeCropRect(
        rect = localRect,
        mode = mode,
        delta = delta,
        viewport = localViewport,
        ratio = ratio,
        minimumSize = effectiveMinimum,
    ).translate(bounds.topLeft)
    return constrainCropRectToBounds(resized, bounds, ratio)
}

private fun resizeCropRect(
    rect: Rect,
    mode: CropDragMode,
    delta: Offset,
    viewport: IntSize,
    ratio: Float?,
    minimumSize: Float,
): Rect {
    if (ratio == null) {
        return when (mode) {
            CropDragMode.TOP_LEFT -> Rect(
                (rect.left + delta.x).coerceIn(0f, rect.right - minimumSize),
                (rect.top + delta.y).coerceIn(0f, rect.bottom - minimumSize),
                rect.right,
                rect.bottom,
            )
            CropDragMode.TOP_RIGHT -> Rect(
                rect.left,
                (rect.top + delta.y).coerceIn(0f, rect.bottom - minimumSize),
                (rect.right + delta.x).coerceIn(rect.left + minimumSize, viewport.width.toFloat()),
                rect.bottom,
            )
            CropDragMode.BOTTOM_LEFT -> Rect(
                (rect.left + delta.x).coerceIn(0f, rect.right - minimumSize),
                rect.top,
                rect.right,
                (rect.bottom + delta.y).coerceIn(rect.top + minimumSize, viewport.height.toFloat()),
            )
            CropDragMode.BOTTOM_RIGHT -> Rect(
                rect.left,
                rect.top,
                (rect.right + delta.x).coerceIn(rect.left + minimumSize, viewport.width.toFloat()),
                (rect.bottom + delta.y).coerceIn(rect.top + minimumSize, viewport.height.toFloat()),
            )
            CropDragMode.TOP_EDGE -> Rect(
                rect.left,
                (rect.top + delta.y).coerceIn(0f, rect.bottom - minimumSize),
                rect.right,
                rect.bottom,
            )
            CropDragMode.RIGHT_EDGE -> Rect(
                rect.left,
                rect.top,
                (rect.right + delta.x).coerceIn(rect.left + minimumSize, viewport.width.toFloat()),
                rect.bottom,
            )
            CropDragMode.BOTTOM_EDGE -> Rect(
                rect.left,
                rect.top,
                rect.right,
                (rect.bottom + delta.y).coerceIn(rect.top + minimumSize, viewport.height.toFloat()),
            )
            CropDragMode.LEFT_EDGE -> Rect(
                (rect.left + delta.x).coerceIn(0f, rect.right - minimumSize),
                rect.top,
                rect.right,
                rect.bottom,
            )
            else -> rect
        }
    }

    if (mode == CropDragMode.TOP_EDGE || mode == CropDragMode.BOTTOM_EDGE) {
        val centerX = rect.center.x
        val requestedHeight = if (mode == CropDragMode.TOP_EDGE) rect.height - delta.y else rect.height + delta.y
        val verticalLimit = if (mode == CropDragMode.TOP_EDGE) rect.bottom else viewport.height - rect.top
        val horizontalLimit = 2f * min(centerX, viewport.width - centerX) / ratio
        val maximumHeight = min(verticalLimit, horizontalLimit)
        val minimumHeight = max(minimumSize, minimumSize / ratio).coerceAtMost(maximumHeight)
        val height = requestedHeight.coerceIn(minimumHeight, maximumHeight)
        val width = height * ratio
        val left = centerX - width / 2f
        return if (mode == CropDragMode.TOP_EDGE) {
            Rect(left, rect.bottom - height, left + width, rect.bottom)
        } else {
            Rect(left, rect.top, left + width, rect.top + height)
        }
    }

    if (mode == CropDragMode.LEFT_EDGE || mode == CropDragMode.RIGHT_EDGE) {
        val centerY = rect.center.y
        val requestedWidth = if (mode == CropDragMode.LEFT_EDGE) rect.width - delta.x else rect.width + delta.x
        val horizontalLimit = if (mode == CropDragMode.LEFT_EDGE) rect.right else viewport.width - rect.left
        val verticalLimit = 2f * min(centerY, viewport.height - centerY) * ratio
        val maximumWidth = min(horizontalLimit, verticalLimit)
        val minimumWidth = max(minimumSize, minimumSize * ratio).coerceAtMost(maximumWidth)
        val width = requestedWidth.coerceIn(minimumWidth, maximumWidth)
        val height = width / ratio
        val top = centerY - height / 2f
        return if (mode == CropDragMode.LEFT_EDGE) {
            Rect(rect.right - width, top, rect.right, top + height)
        } else {
            Rect(rect.left, top, rect.left + width, top + height)
        }
    }

    val anchor = when (mode) {
        CropDragMode.TOP_LEFT -> rect.bottomRight
        CropDragMode.TOP_RIGHT -> rect.bottomLeft
        CropDragMode.BOTTOM_LEFT -> rect.topRight
        CropDragMode.BOTTOM_RIGHT -> rect.topLeft
        else -> return rect
    }
    val moving = when (mode) {
        CropDragMode.TOP_LEFT -> rect.topLeft + delta
        CropDragMode.TOP_RIGHT -> rect.topRight + delta
        CropDragMode.BOTTOM_LEFT -> rect.bottomLeft + delta
        CropDragMode.BOTTOM_RIGHT -> rect.bottomRight + delta
        else -> return rect
    }
    val widthFromX = abs(moving.x - anchor.x)
    val widthFromY = abs(moving.y - anchor.y) * ratio
    val requestedWidth = if (abs(widthFromX - rect.width) >= abs(widthFromY - rect.width)) widthFromX else widthFromY
    val maximumWidth = when (mode) {
        CropDragMode.TOP_LEFT -> min(anchor.x, anchor.y * ratio)
        CropDragMode.TOP_RIGHT -> min(viewport.width - anchor.x, anchor.y * ratio)
        CropDragMode.BOTTOM_LEFT -> min(anchor.x, (viewport.height - anchor.y) * ratio)
        CropDragMode.BOTTOM_RIGHT -> min(viewport.width - anchor.x, (viewport.height - anchor.y) * ratio)
        else -> rect.width
    }
    val minimumWidth = max(minimumSize, minimumSize * ratio).coerceAtMost(maximumWidth)
    val width = requestedWidth.coerceIn(minimumWidth, maximumWidth)
    val height = width / ratio
    return when (mode) {
        CropDragMode.TOP_LEFT -> Rect(anchor.x - width, anchor.y - height, anchor.x, anchor.y)
        CropDragMode.TOP_RIGHT -> Rect(anchor.x, anchor.y - height, anchor.x + width, anchor.y)
        CropDragMode.BOTTOM_LEFT -> Rect(anchor.x - width, anchor.y, anchor.x, anchor.y + height)
        CropDragMode.BOTTOM_RIGHT -> Rect(anchor.x, anchor.y, anchor.x + width, anchor.y + height)
        else -> rect
    }
}

private fun cropAndSave(
    path: String,
    preview: Bitmap,
    viewport: IntSize,
    zoom: Float,
    imageOffset: Offset,
    cropRect: Rect,
) {
    val source = BitmapFactory.decodeFile(path) ?: error("无法读取原始照片")
    val bounds = cropBoundsInSource(
        previewSize = IntSize(preview.width, preview.height),
        sourceSize = IntSize(source.width, source.height),
        viewport = viewport,
        zoom = zoom,
        imageOffset = imageOffset,
        cropRect = cropRect,
    )
    val cropped = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)

    val original = File(path)
    val temporary = File(original.parentFile, ".${original.name}.cropping")
    try {
        temporary.outputStream().use { output ->
            check(cropped.compress(Bitmap.CompressFormat.JPEG, 100, output)) { "无法写入裁剪照片" }
        }
        if (!temporary.renameTo(original)) {
            temporary.copyTo(original, overwrite = true)
            temporary.delete()
        }
    } catch (failure: Throwable) {
        temporary.delete()
        throw failure
    }
}

internal data class PixelCrop(val left: Int, val top: Int, val width: Int, val height: Int)

/** Maps the on-screen crop rectangle back to full-resolution source pixels. */
internal fun cropBoundsInSource(
    previewSize: IntSize,
    sourceSize: IntSize,
    viewport: IntSize,
    zoom: Float,
    imageOffset: Offset,
    cropRect: Rect,
): PixelCrop {
    require(previewSize.width > 0 && previewSize.height > 0)
    require(sourceSize.width > 0 && sourceSize.height > 0)
    require(viewport.width > 0 && viewport.height > 0)
    require(zoom > 0f)
    val baseScale = max(
        viewport.width.toFloat() / previewSize.width,
        viewport.height.toFloat() / previewSize.height,
    )
    val displayedWidth = previewSize.width * baseScale * zoom
    val displayedHeight = previewSize.height * baseScale * zoom
    val left = (viewport.width - displayedWidth) / 2f + imageOffset.x
    val top = (viewport.height - displayedHeight) / 2f + imageOffset.y

    val cropX = (((cropRect.left - left) / displayedWidth) * sourceSize.width)
        .roundToInt().coerceIn(0, sourceSize.width - 1)
    val cropY = (((cropRect.top - top) / displayedHeight) * sourceSize.height)
        .roundToInt().coerceIn(0, sourceSize.height - 1)
    val cropWidth = ((cropRect.width / displayedWidth) * sourceSize.width)
        .roundToInt().coerceAtLeast(1).coerceAtMost(sourceSize.width - cropX)
    val cropHeight = ((cropRect.height / displayedHeight) * sourceSize.height)
        .roundToInt().coerceAtLeast(1).coerceAtMost(sourceSize.height - cropY)
    return PixelCrop(cropX, cropY, cropWidth, cropHeight)
}
