package com.example.lixing.ui.screen.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 首次引导：模板 → 目标日 → 权限。 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 通知权限（Android 13+）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 结果不影响流程 */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.step) {
            0 -> StepTemplate(viewModel, state)
            1 -> StepTargetDate(viewModel, state)
            2 -> StepPermissions(
                onAskNotification = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenExactAlarm = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                },
                onOpenBattery = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(android.net.Uri.parse("package:${context.packageName}")),
                        )
                    }
                },
            )
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.step > 0) {
                OutlinedButton(
                    onClick = { viewModel.setStep(state.step - 1) },
                    shape = com.example.lixing.ui.theme.LiXingRadius.Pill,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("上一步") }
            }

            if (state.step < 2) {
                Button(
                    onClick = { viewModel.setStep(state.step + 1) },
                    shape = com.example.lixing.ui.theme.LiXingRadius.Pill,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("下一步") }
            } else {
                Button(
                    onClick = {
                        viewModel.finish()
                    },
                    enabled = !state.isWorking,
                    shape = com.example.lixing.ui.theme.LiXingRadius.Pill,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(if (state.isWorking) "正在准备…" else "开始使用") }
            }
        }
    }

    if (state.done) {
        onFinish()
    }
}

@Composable
private fun StepTemplate(
    viewModel: OnboardingViewModel,
    state: OnboardingUiState,
) {
    // 渐变欢迎卡：第一眼就建立「晨光」的视觉印象
    val gradient = com.example.lixing.ui.theme.LocalHeroGradient.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(com.example.lixing.ui.theme.LiXingRadius.Hero)
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = gradient,
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset.Infinite,
                ),
            )
            .padding(24.dp),
    ) {
        Column {
            Text("📚", style = MaterialTheme.typography.displayMedium)
            Text(
                "欢迎使用砺行",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Text(
                "把长期备考拆成「每天 / 每个时段 / 每件具体事」，到点提醒、当场打卡。",
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = state.importTemplate, onClick = { viewModel.setImportTemplate(true) })
        Text("导入「考研全程计划」模板（推荐）", style = MaterialTheme.typography.bodyLarge)
    }
    Text(
        "3 个阶段 / 6 个时段 / 4 个科目 / 30+ 任务，导入后都能改。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 40.dp),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = !state.importTemplate, onClick = { viewModel.setImportTemplate(false) })
        Text("先不导入，我自己创建", style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepTargetDate(
    viewModel: OnboardingViewModel,
    state: OnboardingUiState,
) {
    Text("目标日", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(
        "默认取今年 12 月第三个周六（考研初试）。可修改。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    var showPicker by remember { mutableStateOf(false) }

    Button(onClick = { showPicker = true }) {
        Text("目标日：${state.targetDate}")
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.targetDate
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        viewModel.setTargetDate(
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate(),
                        )
                    }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    val dday = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), state.targetDate)
    Text("距离目标日还有 $dday 天", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StepPermissions(
    onAskNotification: () -> Unit,
    onOpenExactAlarm: () -> Unit,
    onOpenBattery: () -> Unit,
) {
    Text("权限", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(
        "为了到点能叫你，需要以下权限。都不影响打卡本身，可以之后在系统设置里补。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    PermissionRow("通知", "时段提醒与催办", onAskNotification)
    PermissionRow("精确闹钟", "锁屏下也能准时提醒", onOpenExactAlarm)
    PermissionRow("忽略电池优化", "防止后台被杀、提醒丢失", onOpenBattery)
}

@Composable
private fun PermissionRow(name: String, desc: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClick) { Text("开启") }
    }
}
