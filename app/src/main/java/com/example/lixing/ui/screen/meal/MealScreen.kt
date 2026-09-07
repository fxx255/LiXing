package com.example.lixing.ui.screen.meal

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.data.local.entity.MealRecordEntity
import com.example.lixing.domain.meal.MealType
import com.example.lixing.ui.photo.importPhoto
import com.example.lixing.ui.screen.today.dialog.PhotoThumb
import com.example.lixing.ui.theme.LiXingRadius
import com.example.lixing.ui.util.ScreenOrientationGuard
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val DATE_FORMAT = DateTimeFormatter.ofPattern("M 月 d 日 EEEE", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealScreen(
    onBack: () -> Unit,
    viewModel: MealViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // 等相机回写的临时文件：存路径 + 餐次名并用 rememberSaveable，
    // 旋转屏幕 / 进程被回收后重建也不会丢（普通 remember 会丢，照片就找不回来了）。
    var pendingPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMealType by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAlbumType by remember { mutableStateOf<MealType?>(null) }
    var editing by remember { mutableStateOf<MealRecordEntity?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingPhotoPath
        val typeName = pendingMealType
        pendingPhotoPath = null
        pendingMealType = null
        if (success && path != null && typeName != null) {
            viewModel.analyzePhoto(MealType.valueOf(typeName), path)
        } else if (path != null) {
            File(path).delete()
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val type = pendingAlbumType
        pendingAlbumType = null
        if (uri != null && type != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        importPhoto(
                            context = context,
                            source = uri,
                            directory = mealPhotoDirectory(context),
                            filePrefix = "meal_${type.name.lowercase()}_album",
                        )
                    }
                }
                imported.onSuccess { viewModel.analyzePhoto(type, it.absolutePath) }
                    .onFailure { viewModel.showMessage("相册照片导入失败：${it.message ?: "请重试"}") }
            }
        }
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    editing?.let { record ->
        EditMealDialog(
            record = record,
            onDismiss = { editing = null },
            onSave = { name, grams ->
                viewModel.update(record, name, grams)
                editing = null
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("每日用餐与健康分析") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                DatePickerRow(
                    date = state.date,
                    canNext = state.date < LocalDate.now(),
                    onPrevious = viewModel::previousDay,
                    onNext = viewModel::nextDay,
                )
            }
            item { PrivacyHint() }
            item { DailyNutritionCard(state) }

            MealType.entries.forEach { type ->
                item(key = type.name) {
                    val record = state.records.firstOrNull { it.mealType == type.name }
                    MealCard(
                        type = type,
                        record = record,
                        analyzing = state.analyzing == type,
                        enabled = state.analyzing == null,
                        onCapture = {
                            val file = createMealPhotoFile(context, type)
                            pendingPhotoPath = file.absolutePath
                            pendingMealType = type.name
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            // 相机普遍声明竖屏且不受用户方向锁定约束，先把方向锁在当前方向，
                            // 回到前台时由 MainActivity.onResume 解除。
                            ScreenOrientationGuard.armBeforeExternalCapture(context)
                            runCatching { takePicture.launch(uri) }
                                .onFailure { pendingPhotoPath = null; pendingMealType = null; file.delete() }
                        },
                        onPickFromAlbum = {
                            pendingAlbumType = type
                            runCatching {
                                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }.onFailure {
                                pendingAlbumType = null
                                viewModel.showMessage("无法打开系统相册")
                            }
                        },
                        onEdit = { record?.let { editing = it } },
                        onReanalyze = { record?.let { viewModel.analyzePhoto(type, it.photoPath) } },
                        onDelete = { record?.let(viewModel::delete) },
                    )
                }
            }
            item {
                Text(
                    "说明：菜品由 APK 内置的端侧视觉模型识别，照片不会上传。热量和营养是基于菜品类别与默认份量的估算，不替代营养师或医疗建议。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DatePickerRow(
    date: LocalDate,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天") }
        Text(
            if (date == LocalDate.now()) "今天 · ${date.format(DATE_FORMAT)}" else date.format(DATE_FORMAT),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "后一天")
        }
    }
}

@Composable
private fun PrivacyHint() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = LiXingRadius.Card) {
        Text(
            "🔒 端侧离线分析 · 照片和结果只保存在本机",
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun DailyNutritionCard(state: MealUiState) {
    val summary = when {
        state.mainMealsLogged < 3 -> "已记录 ${state.mainMealsLogged}/3 顿正餐，记录完整后建议会更准确。"
        state.calories > 2400 -> "今日估算能量偏高，下一餐可优先蔬菜、优质蛋白并减少油炸主食。"
        state.protein < 60f -> "今日蛋白质偏少，可补充蛋、奶、豆制品、鱼虾或瘦肉。"
        state.fiber < 25f -> "今日膳食纤维偏少，建议增加蔬菜、全谷物或一份水果。"
        else -> "今日三餐整体较均衡，继续保持清淡烹调和规律饮水。"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("今日营养估算", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${state.calories} 千卡", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                NutritionValue("蛋白质", state.protein)
                NutritionValue("碳水", state.carbs)
                NutritionValue("脂肪", state.fat)
                NutritionValue("纤维", state.fiber)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NutritionValue(label: String, grams: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${grams.roundToInt()}g", fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MealCard(
    type: MealType,
    record: MealRecordEntity?,
    analyzing: Boolean,
    enabled: Boolean,
    onCapture: () -> Unit,
    onPickFromAlbum: () -> Unit,
    onEdit: () -> Unit,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${type.emoji} ${type.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (record != null) {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "校正") }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
                }
            }
            if (record == null) {
                Text("拍一张餐食照片，模型会在本机识别并估算营养。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PhotoThumb(record.photoPath, Modifier.size(92.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(record.foodName, fontWeight = FontWeight.SemiBold)
                        Text("约 ${record.servingGrams}g · ${record.caloriesKcal} 千卡")
                        if (!record.isManuallyEdited) {
                            Text(
                                "照片不能准确测克重，当前按默认份量估算",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text(
                            "蛋白 ${record.proteinGrams.roundToInt()}g · 碳水 ${record.carbsGrams.roundToInt()}g · 脂肪 ${record.fatGrams.roundToInt()}g",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (record.confidence > 0f) {
                            Text(
                                "模型：${record.modelLabel} · ${(record.confidence * 100).roundToInt()}%${if (record.isManuallyEdited) " · 已校正" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Text("建议：${record.advice}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onCapture, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                if (analyzing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("端侧分析中…")
                } else {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (record == null) "拍照分析" else "重新拍摄并分析")
                }
            }
            OutlinedButton(onClick = onPickFromAlbum, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (record == null) "从相册选择并分析" else "从相册更换并分析")
            }
            if (record != null) {
                OutlinedButton(onClick = onReanalyze, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("重新分析现有照片")
                }
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("校正食物与份量") }
            }
        }
    }
}

@Composable
private fun EditMealDialog(
    record: MealRecordEntity,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    var name by remember(record.id) { mutableStateOf(record.foodName) }
    var grams by remember(record.id) { mutableStateOf(record.servingGrams.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校正识别结果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("模型只负责菜品分类，克重和烹调油请按实际情况修正。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("食物名称") }, singleLine = true)
                OutlinedTextField(
                    grams,
                    { grams = it.filter(Char::isDigit).take(4) },
                    label = { Text("估计份量（克）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), grams.toIntOrNull()?.coerceIn(20, 2000) ?: record.servingGrams) },
                enabled = name.isNotBlank(),
            ) { Text("重新计算") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun createMealPhotoFile(context: Context, type: MealType): File {
    val dir = mealPhotoDirectory(context).apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
    return File(dir, "meal_${type.name.lowercase()}_$stamp.jpg")
}

private fun mealPhotoDirectory(context: Context): File {
    val root = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.filesDir
    return File(root, "meals")
}
