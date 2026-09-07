package com.example.lixing.ui.screen.today.dialog

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.ui.photo.importPhoto
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.util.ScreenOrientationGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 量化任务的打卡输入：数量（步进/滑块）+ 详细记录（文字 + 拍照）。
 *
 * 详情是可选的：可以只填数量就打卡，也可以附上「做了什么题、错在哪」的文字
 * 和一张错题/笔记照片，供回看与复盘。
 */
@Composable
fun CheckInDialog(
    task: DailyTaskEntity,
    onDismiss: () -> Unit,
    onSubmit: (value: Int, note: String?, photo: String?) -> Unit,
    onRevoke: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDone = task.status.isEngaged
    val target = task.targetValue.coerceAtLeast(1)

    var value by remember(task.id) { mutableIntStateOf(if (isDone) task.actualValue else target) }
    var note by remember(task.id) { mutableStateOf(task.checkinNote.orEmpty()) }
    // 多张照片
    var photos by remember(task.id) { mutableStateOf(decodePhotos(task.checkinPhoto)) }
    var photoError by remember(task.id) { mutableStateOf<String?>(null) }

    // 拍照：先建好文件拿 FileProvider URI，相机写入后追加本地路径。
    // 用路径 + rememberSaveable：旋转屏幕 / 进程被回收重建后仍能取回照片（普通 remember 会丢）。
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingPhotoPath?.let { File(it) }
        if (success) file?.absolutePath?.let { photos = photos + it }
        else file?.delete()
        pendingPhotoPath = null
    }
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    runCatching {
                        importPhoto(
                            context = context,
                            source = uri,
                            directory = checkInPhotoDirectory(context),
                            filePrefix = "checkin_album",
                        ).absolutePath
                    }
                }
            }
            val imported = results.mapNotNull(Result<String>::getOrNull)
            photos = photos + imported
            photoError = if (imported.size == results.size) null else "部分照片导入失败，请重试"
        }
    }

    val useStepper = target <= 5
    val maxValue = max(target + 1, (target * 1.5f).roundToInt())

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = LiXingRadius.Hero,
        title = { Text(task.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (isDone) "已完成 · 可修改或撤销" else "目标 $target ${task.targetType.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (useStepper) {
                    StepperRow(
                        value = value,
                        unit = task.targetType.unit,
                        onMinus = { value = (value - 1).coerceAtLeast(0) },
                        onPlus = { value = value + 1 },
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$value",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = task.targetType.unit,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { value = it.roundToInt() },
                        valueRange = 0f..maxValue.toFloat(),
                        steps = (maxValue - 1).coerceAtLeast(0),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    QuickChips(target = target, maxValue = maxValue, onPick = { value = it })
                }

                // ---- 详细记录：文字 ----
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("详细记录（可选）") },
                    placeholder = { Text("如：做了 1000 题第 3 节，错 5 道，错因粗算") },
                    modifier = Modifier.fillMaxWidth().height(84.dp),
                    maxLines = 3,
                )

                // ---- 详细记录：拍照（多张）+ 预览 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = {
                            val file = createPhotoFile(context)
                            pendingPhotoPath = file.absolutePath
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            // 相机普遍声明竖屏且不受用户方向锁定约束，先把方向锁在当前方向，
                            // 回到前台时由 MainActivity.onResume 解除。
                            ScreenOrientationGuard.armBeforeExternalCapture(context)
                            runCatching { takePicture.launch(uri) }.onFailure {
                                pendingPhotoPath = null
                                file.delete()
                                photoError = "无法打开相机"
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) { Icon(Icons.Filled.CameraAlt, contentDescription = "拍照") }

                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            photoError = null
                            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "从相册选择") }

                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (photos.isNotEmpty()) "已附 ${photos.size} 张 · 点图预览/裁剪"
                        else "拍照或从相册选择（可多张）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (photos.isNotEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 多张缩略图，每张可预览/旋转/删除
                PhotoStrip(
                    paths = photos,
                    onRemove = { p -> photos = photos.filterNot { it == p } },
                )
                photoError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(value, note, if (photos.isEmpty()) "" else encodePhotos(photos)) }) {
                Text(if (isDone) "更新" else "打卡", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (isDone) {
                    TextButton(onClick = onRevoke) {
                        Text("撤销", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 在应用私有外部目录建照片文件，返回 File。 */
private fun createPhotoFile(context: Context): File {
    val dir = checkInPhotoDirectory(context).apply { mkdirs() }
    val name = "checkin_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".jpg"
    return File(dir, name)
}

private fun checkInPhotoDirectory(context: Context): File =
    context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir

@Composable
private fun StepperRow(
    value: Int,
    unit: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledIconButton(
            onClick = onMinus,
            enabled = value > 0,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { Icon(Icons.Filled.Remove, contentDescription = "减少") }

        Box(modifier = Modifier.width(110.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$value",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }

        FilledIconButton(
            onClick = onPlus,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) { Icon(Icons.Filled.Add, contentDescription = "增加") }
    }
}

@Composable
private fun QuickChips(target: Int, maxValue: Int, onPick: (Int) -> Unit) {
    val candidates = buildList {
        add((target * 0.5f).roundToInt())
        add((target * 0.75f).roundToInt())
        add(target)
        if (maxValue > target) add(maxValue)
    }.filter { it > 0 }.distinct().sorted()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        candidates.forEach { v ->
            AssistChip(
                onClick = { onPick(v) },
                shape = LiXingRadius.Pill,
                label = {
                    Text(
                        when {
                            v == target -> "目标 $v"
                            v > target -> "超额 $v"
                            else -> "$v"
                        },
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = if (v == target) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
