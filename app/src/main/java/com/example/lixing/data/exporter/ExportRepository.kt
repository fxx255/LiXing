package com.example.lixing.data.exporter

import android.content.Context
import android.os.Environment
import com.example.lixing.data.local.dao.DailyTaskDao
import com.example.lixing.data.local.dao.FocusSessionDao
import com.example.lixing.data.local.dao.GamificationDao
import com.example.lixing.data.local.dao.PlanDao
import com.example.lixing.data.local.dao.TaskTemplateDao
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据导出 / 备份。
 *
 * 全部写到应用的私有外部目录（getExternalFilesDir），
 * 不需要存储权限，用户可以用文件管理器或 adb 看到。
 *
 * 路径结构：
 *   <app>/files/export/lixing_tasks_YYYYMMDD.csv
 *   <app>/files/export/lixing_plan_YYYYMMDD.json
 *   <app>/files/backup/lixing_backup_YYYYMMDD.json
 */
@Singleton
class ExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val dailyTaskDao: DailyTaskDao,
    private val focusSessionDao: FocusSessionDao,
    private val gamificationDao: GamificationDao,
    private val planDao: PlanDao,
    private val taskTemplateDao: TaskTemplateDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun exportDir(): File =
        File(context.getExternalFilesDir(null), "export").apply { mkdirs() }

    private fun backupDir(): File =
        File(context.getExternalFilesDir(null), "backup").apply { mkdirs() }

    private fun stamp(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

    // ---------------- CSV：打卡数据 ----------------

    /** 导出打卡数据为 CSV，返回文件路径。 */
    suspend fun exportTasksCsv(): String = withContext(io) {
        val from = LocalDate.now().minusDays(365)
        val to = LocalDate.now()
        val tasks = taskRepository.getTasksBetween(from, to)

        val sb = StringBuilder()
        sb.appendLine("日期,科目,时段,任务,类型,状态,目标,实际,是否迟到,是否补卡,专注分钟,心情")
        tasks.forEach { t ->
            sb.appendLine(
                listOf(
                    t.date.toString(),
                    csv(t.subjectName),
                    csv(t.slotName),
                    csv(t.title),
                    t.taskType.label,
                    t.status.label,
                    t.targetValue,
                    t.actualValue,
                    if (t.isLate) "是" else "否",
                    if (t.isMakeup) "是" else "否",
                    t.focusedMinutes,
                    t.mood?.label ?: "",
                ).joinToString(","),
            )
        }

        val file = File(exportDir(), "lixing_tasks_${stamp()}.csv")
        // 加 BOM 让 Excel 正确识别 UTF-8 中文
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sb.toString().toByteArray(Charsets.UTF_8))
        file.absolutePath
    }

    private fun csv(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    // ---------------- JSON：计划导出 / 导入 ----------------

    /** 把当前计划完整导出为 JSON，返回文件路径。 */
    suspend fun exportPlanJson(): String = withContext(io) {
        val plan = planRepository.getActivePlan() ?: throw IllegalStateException("没有可导出的计划")
        val phases = planRepository.getPhases(plan.id)
        val subjects = planRepository.getSubjects(plan.id)
        val slots = planRepository.getTimeSlots(plan.id)
        val templates = taskTemplateDao.getEnabledTemplates(plan.id)

        val payload = PlanPayload(
            planName = plan.name,
            startDate = plan.startDate.toString(),
            targetDate = plan.targetDate.toString(),
            phases = phases.map { PhaseDto(it.name, it.startDate.toString(), it.endDate.toString(), it.description, it.sortOrder) },
            subjects = subjects.map { SubjectDto(it.name, it.colorArgb, it.targetTotalMinutes, it.iconKey, it.sortOrder) },
            slots = slots.map { SlotDto(it.name, it.startTime.toString(), it.endTime.toString(), it.weekdayMask, it.sortOrder, it.note) },
            templates = templates.map { TemplateDto(
                subjectName = subjects.firstOrNull { s -> s.id == it.subjectId }?.name ?: "",
                slotName = slots.firstOrNull { s -> s.id == it.timeSlotId }?.name ?: "",
                title = it.title,
                taskType = it.taskType.name,
                targetType = it.targetType.name,
                targetValue = it.targetValue,
                phaseName = phases.firstOrNull { p -> p.id == it.phaseId }?.name,
                repeatRule = it.repeatRule.name,
                weekdayMask = it.weekdayMask,
                intervalDays = it.intervalDays,
                isKeystone = it.isKeystone,
                note = it.note,
                sortOrder = it.sortOrder,
            ) },
        )

        val file = File(exportDir(), "lixing_plan_${stamp()}.json")
        file.writeText(json.encodeToString(payload))
        file.absolutePath
    }

    // ---------------- 备份 ----------------

    /** 全量备份（计划 + 打卡 + 激励），返回路径。 */
    suspend fun backup(): String = withContext(io) {
        val plan = planRepository.getActivePlan()
        val tasks = taskRepository.getTasksBetween(LocalDate.now().minusDays(730), LocalDate.now())

        val backup = BackupPayload(
            version = 1,
            createdAt = System.currentTimeMillis(),
            planName = plan?.name,
            taskCount = tasks.size,
            totalPoints = gamificationDao.getTotalPoints(),
        )

        val file = File(backupDir(), "lixing_backup_${stamp()}.json")
        file.writeText(json.encodeToString(backup))
        trimBackups()
        file.absolutePath
    }

    /** 只保留最近 N 份备份。 */
    private fun trimBackups(keep: Int = 7) {
        backupDir().listFiles()
            ?.filter { it.name.startsWith("lixing_backup_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    fun exportDirPath(): String = exportDir().absolutePath
}

// ---------- JSON DTO ----------

@Serializable
data class PlanPayload(
    val planName: String = "",
    val startDate: String = "",
    val targetDate: String = "",
    val phases: List<PhaseDto> = emptyList(),
    val subjects: List<SubjectDto> = emptyList(),
    val slots: List<SlotDto> = emptyList(),
    val templates: List<TemplateDto> = emptyList(),
)

@Serializable
data class PhaseDto(
    val name: String = "",
    val start: String = "",
    val end: String = "",
    val description: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class SubjectDto(
    val name: String = "",
    val colorArgb: Int = 0,
    val targetTotalMinutes: Int = 0,
    val iconKey: String = "book",
    val sortOrder: Int = 0,
)

@Serializable
data class SlotDto(
    val name: String = "",
    val start: String = "",
    val end: String = "",
    val weekdayMask: Int = 0b1111111,
    val sortOrder: Int = 0,
    val note: String = "",
)

@Serializable
data class TemplateDto(
    val subjectName: String = "",
    val slotName: String = "",
    val title: String = "",
    val taskType: String = "CUSTOM",
    val targetType: String = "BOOLEAN",
    val targetValue: Int = 1,
    val phaseName: String? = null,
    val repeatRule: String = "DAILY",
    val weekdayMask: Int = 0b1111111,
    val intervalDays: Int = 1,
    val isKeystone: Boolean = false,
    val note: String = "",
    val sortOrder: Int = 0,
)

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val createdAt: Long = 0,
    val planName: String? = null,
    val taskCount: Int = 0,
    val totalPoints: Int = 0,
)
