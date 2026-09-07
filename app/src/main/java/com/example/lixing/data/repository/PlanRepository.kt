package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.DailyTaskDao
import com.example.lixing.data.local.dao.DayRecordDao
import com.example.lixing.data.local.dao.FocusSessionDao
import com.example.lixing.data.local.dao.PlanDao
import com.example.lixing.data.local.dao.TaskTemplateDao
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.model.WeekdayMask
import com.example.lixing.domain.seed.SeedPlan
import com.example.lixing.domain.seed.SeedResolver
import com.example.lixing.ui.theme.SubjectPalette
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计划、阶段、科目、时段、任务模板的读写门面。
 *
 * 所有 suspend 方法都切到 IO；Flow 直接由 Room 提供（Room 自己就在 IO 线程上查）。
 */
@Singleton
class PlanRepository @Inject constructor(
    private val planDao: PlanDao,
    private val templateDao: TaskTemplateDao,
    private val dailyTaskDao: DailyTaskDao,
    private val dayRecordDao: DayRecordDao,
    private val focusSessionDao: FocusSessionDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    // ---------------- 读 ----------------

    val activePlan: Flow<StudyPlanEntity?> = planDao.observeActivePlan()

    val allPlans: Flow<List<StudyPlanEntity>> = planDao.observeAllPlans()

    suspend fun getActivePlan(): StudyPlanEntity? = withContext(io) { planDao.getActivePlan() }

    suspend fun hasAnyPlan(): Boolean = withContext(io) { planDao.countPlans() > 0 }

    fun observePhases(planId: String): Flow<List<PhaseEntity>> = planDao.observePhases(planId)

    fun observeSubjects(planId: String): Flow<List<SubjectEntity>> = planDao.observeSubjects(planId)

    fun observeTimeSlots(planId: String): Flow<List<TimeSlotEntity>> = planDao.observeTimeSlots(planId)

    fun observeTemplates(planId: String): Flow<List<TaskTemplateEntity>> =
        templateDao.observeTemplates(planId)

    /** 当前阶段。计划为空时发 null，不抛异常。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeCurrentPhase(date: LocalDate): Flow<PhaseEntity?> =
        activePlan.flatMapLatest { plan ->
            if (plan == null) flowOf(null) else planDao.observeCurrentPhase(plan.id, date)
        }

    suspend fun getCurrentPhase(planId: String, date: LocalDate): PhaseEntity? =
        withContext(io) { planDao.getCurrentPhase(planId, date) }

    suspend fun getPhases(planId: String): List<PhaseEntity> = withContext(io) { planDao.getPhases(planId) }

    suspend fun getSubjects(planId: String): List<SubjectEntity> =
        withContext(io) { planDao.getSubjects(planId) }

    suspend fun getTimeSlots(planId: String): List<TimeSlotEntity> =
        withContext(io) { planDao.getTimeSlots(planId) }

    suspend fun getEnabledTemplates(planId: String): List<TaskTemplateEntity> =
        withContext(io) { templateDao.getEnabledTemplates(planId) }

    suspend fun getSubject(id: String): SubjectEntity? = withContext(io) { planDao.getSubject(id) }

    suspend fun getTimeSlot(id: String): TimeSlotEntity? = withContext(io) { planDao.getTimeSlot(id) }

    suspend fun getTemplate(id: String): TaskTemplateEntity? =
        withContext(io) { templateDao.getTemplate(id) }

    suspend fun getPhase(id: String): PhaseEntity? = withContext(io) { planDao.getPhase(id) }

    // ---------------- 写 ----------------

    suspend fun createPlan(plan: StudyPlanEntity): String = withContext(io) {
        if (plan.isActive) planDao.deactivateAllPlans()
        planDao.insertPlan(plan)
        plan.id
    }

    suspend fun updatePlan(plan: StudyPlanEntity) = withContext(io) { planDao.updatePlan(plan) }

    suspend fun activatePlan(planId: String) = withContext(io) { planDao.activatePlan(planId) }

    suspend fun deletePlan(plan: StudyPlanEntity) = withContext(io) { planDao.deletePlan(plan) }

    suspend fun upsertPhase(phase: PhaseEntity): String = withContext(io) {
        planDao.upsertPhase(phase)
        phase.id
    }

    suspend fun deletePhase(phase: PhaseEntity) = withContext(io) { planDao.deletePhase(phase) }

    suspend fun upsertSubject(subject: SubjectEntity): String =
        withContext(io) {
            planDao.upsertSubject(subject)
            subject.id
        }

    suspend fun deleteSubject(subject: SubjectEntity) = withContext(io) { planDao.deleteSubject(subject) }

    suspend fun upsertTimeSlot(slot: TimeSlotEntity): String =
        withContext(io) {
            planDao.upsertTimeSlot(slot)
            slot.id
        }

    suspend fun deleteTimeSlot(slot: TimeSlotEntity) = withContext(io) { planDao.deleteTimeSlot(slot) }

    suspend fun upsertTemplate(template: TaskTemplateEntity): String =
        withContext(io) {
            templateDao.upsertTemplate(template)
            template.id
        }

    suspend fun deleteTemplate(template: TaskTemplateEntity) =
        withContext(io) { templateDao.deleteTemplate(template) }

    suspend fun setTemplateEnabled(id: String, enabled: Boolean) =
        withContext(io) { templateDao.setEnabled(id, enabled) }

    // ---------------- 种子导入 ----------------

    /**
     * 导入种子模板，返回新计划 id。
     *
     * 顺序：计划 → 阶段 → 时段 → 科目 → 任务模板。
     * 前四步拿到的 id 用来解析任务模板里的名字引用；名字对不上的任务会被跳过
     * （不静默失败，返回值里带上跳过数量供 UI 提示）。
     *
     * @param replaceExisting true 时先清空所有已有计划（连带级联删除阶段/科目/时段/模板）
     */
    suspend fun importSeedPlan(
        seed: SeedPlan,
        startDate: LocalDate,
        targetDate: LocalDate,
        planName: String = seed.name,
        replaceExisting: Boolean = false,
    ): ImportResult = withContext(io) {
        if (replaceExisting) {
            // 换计划 ≠ 抹掉过去。旧计划只停用不删除（历史任务靠快照字段仍可读，
            // 且物化只认 active 计划，旧模板不会再生成任务）；
            // 只清掉「新计划生效日及以后」的任务与汇总，避免与新模板叠成重复项。
            // startDate 之前的打卡记录、详细记录、照片、专注记录全部原样保留。
            dailyTaskDao.deleteFrom(startDate)
            dayRecordDao.deleteFrom(startDate)
        }

        planDao.deactivateAllPlans()
        val plan = StudyPlanEntity(
            name = planName,
            startDate = startDate,
            targetDate = targetDate,
            isActive = true,
            note = seed.description,
        )
        planDao.insertPlan(plan)
        val planId = plan.id

        // 阶段：解析相对日期，落在计划区间外的直接丢掉
        val resolved = SeedResolver.resolvePhaseRanges(seed.phases, startDate, targetDate)
        val phaseIdByName = HashMap<String, String>()
        resolved.filterNotNull().forEach { rp ->
            val phase = PhaseEntity(
                planId = planId,
                name = rp.seed.name,
                startDate = rp.startDate,
                endDate = rp.endDate,
                description = rp.seed.description,
                sortOrder = rp.seed.sortOrder,
            )
            planDao.upsertPhase(phase)
            phaseIdByName[rp.seed.name] = phase.id
        }

        // 时段
        val slotIdByName = HashMap<String, String>()
        seed.timeSlots.forEach { s ->
            val slot = TimeSlotEntity(
                planId = planId,
                name = s.name,
                startTime = s.start,
                endTime = s.end,
                weekdayMask = WeekdayMask.of(s.days).value,
                sortOrder = s.sortOrder,
                note = s.note,
            )
            planDao.upsertTimeSlot(slot)
            slotIdByName[s.name] = slot.id
        }

        // 科目
        val subjectIdByName = HashMap<String, String>()
        seed.subjects.forEach { s ->
            val color = SubjectPalette[s.paletteIndex % SubjectPalette.size]
            val subject = SubjectEntity(
                planId = planId,
                name = s.name,
                colorArgb = color.toArgbInt(),
                targetTotalMinutes = s.targetHours * 60,
                iconKey = s.iconKey,
                sortOrder = s.sortOrder,
            )
            planDao.upsertSubject(subject)
            subjectIdByName[s.name] = subject.id
        }

        // 任务模板
        var skipped = 0
        val templates = ArrayList<TaskTemplateEntity>(seed.tasks.size)
        seed.tasks.forEach { t ->
            val subjectId = subjectIdByName[t.subjectName]
            val slotId = slotIdByName[t.slotName]
            if (subjectId == null || slotId == null) {
                skipped++
                return@forEach
            }
            // 绑定了阶段但该阶段被裁掉了（比如 9 月才开始备考，基础阶段不存在）→ 跳过
            val phaseId = t.phaseName?.let { name ->
                phaseIdByName[name] ?: run { skipped++; return@forEach }
            }
            templates += TaskTemplateEntity(
                subjectId = subjectId,
                timeSlotId = slotId,
                title = t.title,
                taskType = t.taskType,
                targetType = t.targetType,
                targetValue = t.targetValue,
                repeatRule = t.repeatRule,
                weekdayMask = WeekdayMask.of(t.days).value,
                intervalDays = t.intervalDays,
                anchorDate = startDate,
                phaseId = phaseId,
                isKeystone = t.isKeystone,
                note = t.note,
                sortOrder = t.sortOrder,
            )
        }
        templateDao.insertTemplates(templates)

        ImportResult(
            planId = planId,
            phaseCount = phaseIdByName.size,
            slotCount = slotIdByName.size,
            subjectCount = subjectIdByName.size,
            templateCount = templates.size,
            skippedCount = skipped,
        )
    }

    data class ImportResult(
        val planId: String,
        val phaseCount: Int,
        val slotCount: Int,
        val subjectCount: Int,
        val templateCount: Int,
        val skippedCount: Int,
    )
}

/** Compose 的 Color 转 ARGB Int。放在这里避免 data 层直接依赖 UI 类型。 */
private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255f + 0.5f).toInt(),
        (red * 255f + 0.5f).toInt(),
        (green * 255f + 0.5f).toInt(),
        (blue * 255f + 0.5f).toInt(),
    )
