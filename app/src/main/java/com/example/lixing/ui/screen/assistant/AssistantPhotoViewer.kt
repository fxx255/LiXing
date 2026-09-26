package com.example.lixing.ui.screen.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.ui.photo.PhotoEdits
import com.example.lixing.ui.photo.decodeUprightPhoto
import com.example.lixing.ui.photo.rotatePhotoAndSave
import com.example.lixing.ui.theme.LiXingRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
@Composable
internal fun AssistantThumbnail(
    path: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val photoRevision by PhotoEdits.revision.collectAsStateWithLifecycle()
    val bitmap = remember(path, photoRevision) { decodeSampledBitmap(path, 320) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "查看照片",
            modifier = modifier.clip(LiXingRadius.Card).clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clip(LiXingRadius.Card)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("图片不可用", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun PhotoViewerDialog(paths: List<String>, initialIndex: Int, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rotating by remember { mutableStateOf(false) }
    var rotateError by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (paths.size - 1).coerceAtLeast(0)),
        pageCount = { paths.size },
    )
    Dialog(
        onDismissRequest = { if (!rotating) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = !rotating) { page ->
                ZoomablePhoto(
                    path = paths[page],
                    pageLabel = "照片大图 ${page + 1}",
                    onTapToClose = { if (!rotating) onDismiss() },
                    onSwipeDownToClose = { if (!rotating) onDismiss() },
                )
            }
            TextButton(
                enabled = !rotating && paths.isNotEmpty(),
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                onClick = {
                    val path = paths.getOrNull(pagerState.currentPage) ?: return@TextButton
                    rotating = true
                    rotateError = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { rotatePhotoAndSave(path) } }
                        rotating = false
                        result.onFailure { rotateError = it.message ?: "旋转失败，请重试" }
                    }
                },
            ) {
                Icon(Icons.Filled.RotateLeft, contentDescription = null, tint = Color.White)
                Text(if (rotating) "旋转中…" else "逆时针 90°", color = Color.White)
            }
            rotateError?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp)) }
            if (paths.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${paths.size}",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                enabled = !rotating,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

private const val VIEWER_MAX_SCALE = 5f
private const val VIEWER_DOUBLE_TAP_SCALE = 2.5f
/** 未放大时向下拖动超过这个距离就关闭查看器。 */
private const val VIEWER_DRAG_DISMISS_PX = 140f

/**
 * 可缩放的照片页：双指捏合缩放、双击放大/还原、放大后单指拖动平移，
 * 边界与缩放下限都做夹取，越界自动回弹（缩小到 1 时位移归零）。
 * 未放大时：轻点关闭、向下拖动关闭；这两种手势可以并存，不会和 Pager 横滑打架
 * （横滑由 HorizontalPager 自己消费，纵向位移才会被这里接管）。
 */
@Composable
private fun ZoomablePhoto(
    path: String,
    pageLabel: String,
    onTapToClose: () -> Unit,
    onSwipeDownToClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // 未放大时的下拉位移（用于「下拉关闭」）。放在 pointerInput 外面：
    // detectTransformGestures 是挂起函数，手势回调里读写局部变量会随重组丢失。
    var dragDown by remember { mutableFloatStateOf(0f) }
    val photoRevision by PhotoEdits.revision.collectAsStateWithLifecycle()
    val bitmap = remember(path, photoRevision) { decodeSampledBitmap(path, 2400) }

    // 换页时复位，避免上一张的缩放/位移带到下一张
    LaunchedEffect(path, photoRevision) {
        scale.snapTo(1f)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
        dragDown = 0f
    }

    fun maxOffset(currentScale: Float, dimension: Int): Float =
        (dimension * (currentScale - 1f) / 2f).coerceAtLeast(0f)

    fun clampX(value: Float, atScale: Float): Float {
        val bound = maxOffset(atScale, boxSize.width)
        return value.coerceIn(-bound, bound)
    }

    fun clampY(value: Float, atScale: Float): Float {
        val bound = maxOffset(atScale, boxSize.height)
        return value.coerceIn(-bound, bound)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            // v1.0.23 的回归：当时把手指路径拆成「未放大只挂 detectVerticalDragGestures、
            // 放大后才挂 detectTransformGestures」，人为造出「缩放死区」——
            // 未放大时没有任何人处理 zoom，双指捏合因此完全失效。
            //
            // 但不能简单地改回 detectTransformGestures：它一旦到达 touch slop 就会
            // **消费掉所有位移**，HorizontalPager 再也收不到横滑，左右翻页又废了。
            // 官方 API 也没有「按条件不消费」的开关。
            //
            // 所以这里自己写检测循环，按手势意图决定消费谁：
            // - 双指（捏合）：消费 → 缩放
            // - 单指且已放大：消费 → 平移
            // - 单指未放大、以横向为主：**不消费** → 事件下发给 HorizontalPager 翻页
            // - 单指未放大、以纵向为主：消费 → 下拉关闭
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lastCentroid = Offset.Zero
                    var totalPanX = 0f
                    var totalPanY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } /
                            pressed.size.toFloat()
                        val pan = if (lastCentroid == Offset.Zero) Offset.Zero else centroid - lastCentroid
                        lastCentroid = centroid
                        val zoom = event.calculateZoom()
                        val zooming = abs(zoom - 1f) > 0.001f
                        val multiTouch = pressed.size >= 2

                        totalPanX += pan.x
                        totalPanY += pan.y

                        // 单指、未放大、且横向占优 → 让给 Pager，什么都不做也不消费
                        if (!multiTouch && scale.value <= 1.0005f && !zooming &&
                            abs(totalPanX) > abs(totalPanY)
                        ) {
                            continue
                        }

                        val next = (scale.value * zoom).coerceIn(1f, VIEWER_MAX_SCALE)
                        if (scale.value <= 1.0005f && !multiTouch && !zooming) {
                            // 未放大的单指纵向拖动：下拉关闭
                            dragDown = (dragDown + pan.y).coerceAtLeast(0f)
                            if (dragDown > VIEWER_DRAG_DISMISS_PX) {
                                dragDown = 0f
                                onSwipeDownToClose()
                            } else {
                                // 不能在 awaitEachGesture 这个受限挂起作用域里直接调
                                // Animatable.snapTo/animateTo（它俩是挂起成员函数），
                                // 必须丢到 scope 里执行；snapTo 会打断上一次动画，天然幂等。
                                val target = dragDown
                                scope.launch { offsetY.snapTo(target) }
                            }
                        } else {
                            // 捏合或已放大：缩放 + 平移，位移夹取在边界内
                            dragDown = 0f
                            val panX = pan.x
                            val panY = pan.y
                            scope.launch {
                                scale.snapTo(next)
                                offsetX.snapTo(clampX(offsetX.value + panX, next))
                                offsetY.snapTo(clampY(offsetY.value + panY, next))
                            }
                        }
                        // 到这里说明这一支手势归我们管，消费掉避免上层/父级再处理
                        event.changes.forEach { it.consume() }
                    }
                    // 松手：下拉没到阈值就回弹
                    if (dragDown > 0f) {
                        dragDown = 0f
                        scope.launch { offsetY.animateTo(0f) }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (scale.value <= 1.0005f) onTapToClose() },
                    onDoubleTap = { tap ->
                        val current = scale.value
                        val target = if (current > 1.0005f) 1f else VIEWER_DOUBLE_TAP_SCALE
                        val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                        // 让双击点保持不动：offset2 = offset1 + (T - center - offset1) * (1 - s2/s1)
                        val rel = tap - center - Offset(offsetX.value, offsetY.value)
                        val ratio = target / current
                        val nextX = if (target == 1f) 0f else clampX(offsetX.value + rel.x * (1f - ratio), target)
                        val nextY = if (target == 1f) 0f else clampY(offsetY.value + rel.y * (1f - ratio), target)
                        scope.launch {
                            scale.animateTo(target)
                            offsetX.animateTo(nextX)
                            offsetY.animateTo(nextY)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = pageLabel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer(
                        scaleX = scale.value,
                        scaleY = scale.value,
                        translationX = offsetX.value,
                        translationY = offsetY.value,
                    ),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("图片文件不可用", color = Color.White)
        }
    }
}

internal fun decodeSampledBitmap(path: String, maxDimension: Int): android.graphics.Bitmap? =
    decodeUprightPhoto(path, maxDimension * 2)
