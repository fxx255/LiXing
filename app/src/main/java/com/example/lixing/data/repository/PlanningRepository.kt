package com.example.lixing.data.repository

import androidx.room.withTransaction
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.dao.FocusSessionDao
import com.example.lixing.data.local.dao.PlanningDao
import com.example.lixing.data.local.entity.LearningGoalEntity
import com.example.lixing.data.local.entity.LearningUnitEntity
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.ScheduledTaskEntity
import com.example.lixing.data.local.entity.PlanDayPolicyEntity
import com.example.lixing.data.local.entity.PlanChangeSetEntity
import com.example.lixing.data.local.entity.StudyResourceEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.materialize.TaskMaterializer
import com.example.lixing.domain.planning.DailyScheduleResolver
import com.example.lixing.domain.planning.ContentSelectionCodec
import com.example.lixing.domain.planning.GoalContentStats
import com.example.lixing.domain.planning.GoalProgressCalculator
import com.example.lixing.domain.planning.AvailabilityCodec
import com.example.lixing.domain.planning.PlanningEngine
import com.example.lixing.domain.time.StudyDayWindow
import com.example.lixing.domain.time.SlotWindow
import com.example.lixing.domain.time.StudyClock
import com.example.lixing.reminder.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Stores confirmed dated plans; execution continues through the existing DailyTask path. */
@Singleton
class PlanningRepository @Inject constructor(
    private val db: LiXingDatabase,
    private val planningDao: PlanningDao,
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val focusSessionDao: FocusSessionDao,
    private val prefs: UserPreferencesRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val reminderScheduler: ReminderScheduler? = null,
) {
    fun observeSchedules(planId: String, from: LocalDate, to: LocalDate): Flow<List<ScheduledTaskEntity>> =
        planningDao.observeScheduledTasks(planId, from, to)

    fun observeDayPolicies(planId: String, from: LocalDate, to: LocalDate): Flow<List<PlanDayPolicyEntity>> =
        planningDao.observeDayPolicies(planId, from, to)

    suspend fun saveDayPolicy(policy: PlanDayPolicyEntity) = withContext(io) {
        db.withTransaction {
            require(planRepository.getActivePlan()?.id == policy.planId) { "请先切换到该计划" }
            require(policy.studyDate >= currentDay()) { "不能改写过去的可用时间" }
            val old = planningDao.getDayPolicy(policy.planId, policy.studyDate)
            val before = snapshotFingerprint(policy.planId, listOf(policy.studyDate))
            require(old == null || old.id == policy.id) { "该日期已有可用时间设置" }
            require(old == null || !old.isLocked || old == policy) { "可用时间已锁定" }
            require(policy.maxPlannedMinutes == null || policy.maxPlannedMinutes in 0..1440) { "每日容量无效" }
            val windows = requireNotNull(AvailabilityCodec.decode(policy.windowsJson)) { "可用时间格式无效" }
            val resolved = PlanningEngine.validateWindows(policy.studyDate, prefs.current().dayStartTime, windows)
            val existing = effectiveDay(policy.planId, policy.studyDate,
                planningDao.getScheduledTasks(policy.planId, policy.studyDate))
            validateDayCapacity(existing, resolved, policy.maxPlannedMinutes)
            existing.filter { it.scheduledStart != null && it.scheduledEnd != null }.forEach { task ->
                val taskWindow = StudyDayWindow.of(task.date, requireNotNull(task.scheduledStart),
                    requireNotNull(task.scheduledEnd), prefs.current().dayStartTime)
                require(resolved.any { taskWindow.start >= it.start && taskWindow.end <= it.end }) {
                    "「${task.title}」不在新的可用时间内"
                }
            }
            planningDao.upsertDayPolicy(policy.copy(revision = (old?.revision ?: 0) + 1))
            journal(policy.planId, policy.studyDate, "SET_DAY_AVAILABILITY", policy.id, before)
        }
        refreshReminder(policy.studyDate)
    }

    suspend fun clearDayPolicy(planId: String, date: LocalDate) = withContext(io) {
        db.withTransaction {
            require(date >= currentDay()) { "不能改写过去的可用时间" }
            val old = planningDao.getDayPolicy(planId, date) ?: return@withTransaction
            val before = snapshotFingerprint(planId, listOf(date))
            require(!old.isLocked) { "可用时间已锁定" }
            planningDao.deleteDayPolicy(old.id)
            journal(planId, date, "CLEAR_DAY_AVAILABILITY", old.id, before)
        }
        refreshReminder(date)
    }

    fun observeResources(planId: String): Flow<List<StudyResourceEntity>> =
        planningDao.observeResources(planId)

    fun observeGoals(planId: String): Flow<List<LearningGoalEntity>> = planningDao.observeGoals(planId)

    fun observeGoalContentStats(planId: String): Flow<List<GoalContentStats>> = combine(
        planningDao.observeGoals(planId), planningDao.observeGoalProgressForPlan(planId),
    ) { goals, records -> GoalProgressCalculator.calculate(goals, records) }

    suspend fun getGoalContentStats(planId: String): List<GoalContentStats> = withContext(io) {
        val goals = planningDao.getGoals(planId)
        GoalProgressCalculator.calculate(goals, goals.flatMap { planningDao.getGoalProgress(it.id) })
    }

    suspend fun getSchedules(planId: String, from: LocalDate, to: LocalDate): List<ScheduledTaskEntity> =
        withContext(io) { planningDao.getScheduledTasksBetween(planId, from, to) }

    suspend fun getDayPolicy(planId: String, date: LocalDate): PlanDayPolicyEntity? =
        withContext(io) { planningDao.getDayPolicy(planId, date) }

    suspend fun getDayPolicies(planId: String, from: LocalDate, to: LocalDate): List<PlanDayPolicyEntity> =
        withContext(io) { planningDao.getDayPolicies(planId, from, to) }

    /** The same guard used by confirmation, exposed for a read-only review card. */
    suspend fun validateDatedCandidate(entry: ScheduledTaskEntity) = withContext(io) {
        validate(entry)
        ensureNoExecutionConflict(entry)
    }

    suspend fun activePlanId(): String? = planRepository.getActivePlan()?.id

    suspend fun studyDayStart(): java.time.LocalTime = prefs.current().dayStartTime

    /** Finds a visible start/end for an AI candidate before its review card is stored. */
    suspend fun proposePlacement(planId: String, date: LocalDate, slotId: String,
        minutes: Int, earlier: List<SlotWindow>, sourceTemplateId: String? = null,
        previouslyReplaced: Set<String> = emptySet(), earlierMinutes: Int = earlier.sumOf {
            it.duration.toMinutes().toInt()
        }): SlotWindow? = withContext(io) {
        val slot = planRepository.getTimeSlot(slotId)?.takeIf { it.planId == planId && it.isEnabled }
            ?: return@withContext null
        val dayStart = prefs.current().dayStartTime
        val slotWindow = StudyDayWindow.of(date, slot.startTime, slot.endTime, dayStart)
        val policy = planningDao.getDayPolicy(planId, date)
        val windows = policy?.let {
            PlanningEngine.validateWindows(date, dayStart,
                requireNotNull(AvailabilityCodec.decode(it.windowsJson)))
        }
        val occupied = planningDao.getScheduledTasks(planId, date)
            .filter { it.state in setOf("ACTIVE", "OVERRIDE") }
            .mapNotNull { item -> item.startTime?.let { start ->
                StudyDayWindow.of(date, start, requireNotNull(item.endTime), dayStart)
            } } + earlier
        val projected = effectiveDay(planId, date, planningDao.getScheduledTasks(planId, date))
        val replaced = previouslyReplaced + listOfNotNull(sourceTemplateId)
        val total = projected.filterNot { it.templateId != null && it.templateId in replaced }
            .sumOf { it.plannedMinutes ?: 0 } + earlierMinutes + minutes
        if (policy != null) {
            val capacity = minOf(PlanningEngine.capacityMinutes(requireNotNull(windows)),
                policy.maxPlannedMinutes ?: Int.MAX_VALUE)
            if (total > capacity) return@withContext null
        }
        PlanningEngine.place(minutes, PlanningEngine.availableForSlot(slotWindow, windows), occupied)
    }

    /** Captures the inputs that can make an AI dated preview stale before confirmation. */
    suspend fun snapshotFingerprint(planId: String, dates: Collection<LocalDate>): String = withContext(io) {
        val canonical = buildString {
            appendLine(planRepository.getActivePlan()?.toString().orEmpty())
            planRepository.getTemplates(planId).sortedBy { it.id }.forEach { appendLine(it) }
            planRepository.getTimeSlots(planId).sortedBy { it.id }.forEach { appendLine(it) }
            planRepository.getSubjects(planId).sortedBy { it.id }.forEach { appendLine(it) }
            planRepository.getPhases(planId).sortedBy { it.id }.forEach { appendLine(it) }
            planningDao.getResources(planId).sortedBy { it.id }.forEach { appendLine(it) }
            planningDao.getGoals(planId).sortedBy { it.id }.forEach { goal ->
                appendLine(goal)
                planningDao.getGoalProgress(goal.id).forEach { appendLine(it) }
            }
            for (date in dates.distinct().sorted()) {
                appendLine(date)
                appendLine(planningDao.getDayPolicy(planId, date))
                planningDao.getScheduledTasks(planId, date).sortedBy { it.id }.forEach { appendLine(it) }
                appendLine(taskRepository.getDayRecord(date)?.isSettled == true)
                taskRepository.getTasksOfDay(date).sortedBy { it.id }.forEach { task ->
                    val hasSources = focusSessionDao.countForTask(task.id) > 0 ||
                        planningDao.getManualTimesOfTask(task.id).isNotEmpty() ||
                        planningDao.getContentProgress(task.id).isNotEmpty()
                    if (task.status != TaskStatus.PENDING || task.checkedAt != null ||
                        task.actualValue > 0 || task.focusedMinutes > 0 || hasSources) {
                        appendLine(task)
                        appendLine(hasSources)
                    }
                }
            }
        }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun getResources(planId: String): List<StudyResourceEntity> =
        withContext(io) { planningDao.getResources(planId) }

    suspend fun getGoals(planId: String): List<LearningGoalEntity> =
        withContext(io) { planningDao.getGoals(planId) }

    /** Preview without creating execution rows for all future days. */
    suspend fun previewWeek(planId: String, from: LocalDate): Map<LocalDate, List<DailyTaskEntity>> =
        withContext(io) {
            val plan = planRepository.getActivePlan()?.takeIf { it.id == planId } ?: return@withContext emptyMap()
            val templates = planRepository.getEnabledTemplates(planId)
            val subjects = planRepository.getSubjects(planId).associateBy { it.id }
            val slots = planRepository.getTimeSlots(planId).associateBy { it.id }
            val phases = planRepository.getPhases(planId).associateBy { it.id }
            val schedules = planningDao.getScheduledTasksBetween(planId, from, from.plusDays(6)).groupBy { it.studyDate }
            (0L..6L).associate { offset ->
                val date = from.plusDays(offset)
                val recurring = if (date < plan.startDate) emptyList() else TaskMaterializer.materialize(
                    date, templates, slots, subjects, phases, plan.startDate,
                )
                date to DailyScheduleResolver.resolve(date, recurring, schedules[date].orEmpty(), slots, subjects)
            }
        }

    suspend fun previewDay(planId: String, date: LocalDate): List<DailyTaskEntity> = withContext(io) {
        effectiveDay(planId, date, planningDao.getScheduledTasks(planId, date))
    }

    suspend fun saveResource(resource: StudyResourceEntity) = withContext(io) {
        val old = planningDao.getResource(resource.id)
        require(old == null || old.planId == resource.planId && old.subjectId == resource.subjectId) {
            "已有资料不能改换计划或科目，请另建一份资料"
        }
        val subject = planRepository.getSubject(resource.subjectId)
        require(subject?.planId == resource.planId) { "学习资料的科目不属于此计划" }
        require(resource.name.isNotBlank()) { "请填写资料名称" }
        planningDao.upsertResource(resource)
    }

    suspend fun saveGoal(goal: LearningGoalEntity) = withContext(io) {
        val old = planningDao.getGoal(goal.id)
        require(old == null || old.planId == goal.planId && old.subjectId == goal.subjectId &&
            old.resourceId == goal.resourceId && old.roundKey == goal.roundKey) {
            "已有目标不能改换计划、科目、资料或轮次，请另建目标"
        }
        val subject = planRepository.getSubject(goal.subjectId)
        require(subject?.planId == goal.planId) { "目标的科目不属于此计划" }
        require(goal.resourceId == null || planningDao.getResource(goal.resourceId)?.let {
            it.planId == goal.planId && it.subjectId == goal.subjectId
        } == true) {
            "目标的学习资料不属于此计划"
        }
        require(goal.title.isNotBlank()) { "请填写学习目标" }
        val scope = ContentSelectionCodec.decode(goal.scopeJson)
        if (goal.scopeJson.isNotBlank()) require(scope != null) {
            "学习范围格式无效"
        }
        val initial = ContentSelectionCodec.decode(goal.initialProgressJson)
        require(goal.initialProgressJson.isBlank() || initial != null) { "已有进度范围格式无效" }
        require(initial == null || scope != null &&
            com.example.lixing.domain.planning.IntervalMath.remaining(initial.intervals, scope.intervals).isEmpty()) {
            "已有进度超出目标范围"
        }
        require(goal.startDate == null || goal.dueDate == null || goal.dueDate >= goal.startDate) {
            "截止日期早于开始日期"
        }
        planningDao.upsertGoal(goal)
    }

    suspend fun saveUnit(unit: LearningUnitEntity) = withContext(io) {
        require(planningDao.getResource(unit.resourceId) != null) { "学习资料不存在" }
        require(unit.rangeStart == null || unit.rangeEnd == null ||
            unit.rangeStart > 0 && unit.rangeEnd >= unit.rangeStart
        ) { "章节或题号范围无效" }
        planningDao.upsertUnit(unit)
    }

    suspend fun saveSchedule(entry: ScheduledTaskEntity, recordManualChange: Boolean = true) = withContext(io) {
        db.withTransaction {
            validate(entry)
            val before = if (recordManualChange) snapshotFingerprint(entry.planId, listOf(entry.studyDate)) else ""
            val existing = planningDao.getScheduledTask(entry.id)
            require(existing == null || !existing.isLocked || existing == entry) { "任务已锁定，请先解锁" }
            require(existing == null || existing.planId == entry.planId) { "不能更换任务所属计划" }
            require(existing == null || existing.studyDate == entry.studyDate) { "请使用移动安排操作" }
            val saved = if (existing == null) entry else entry.copy(
                baselineMinutes = existing.baselineMinutes,
                baselineValue = existing.baselineValue,
                revision = existing.revision + 1,
                createdAt = existing.createdAt,
            )
            ensureNoExecutionConflict(saved)
            planningDao.upsertScheduledTask(saved)
            if (isCurrentDay(saved.studyDate)) taskRepository.reconcileDayWithPlan(saved.studyDate)
            if (recordManualChange) journal(saved.planId, saved.studyDate, "SAVE_DATED_TASK", saved.id, before)
        }
        refreshReminder(entry.studyDate)
    }

    suspend fun cancelSchedule(id: String) = withContext(io) {
        val date = planningDao.getScheduledTask(id)?.studyDate
        db.withTransaction {
            val existing = planningDao.getScheduledTask(id) ?: return@withTransaction
            val before = snapshotFingerprint(existing.planId, listOf(existing.studyDate))
            require(!existing.isLocked) { "任务已锁定，请先解锁" }
            require(existing.studyDate >= currentDay()) { "过去的安排不可取消" }
            ensureNoExecutionConflict(existing)
            planningDao.upsertScheduledTask(existing.copy(
                state = if (existing.sourceTemplateId == null) "CANCELLED" else "SUPPRESS",
                revision = existing.revision + 1,
            ))
            if (isCurrentDay(existing.studyDate)) taskRepository.reconcileDayWithPlan(existing.studyDate)
            journal(existing.planId, existing.studyDate, "CANCEL_DATED_TASK", existing.id, before)
        }
        if (date != null) refreshReminder(date)
    }

    suspend fun restoreTemplateOccurrence(id: String) = withContext(io) {
        val date = planningDao.getScheduledTask(id)?.studyDate
        db.withTransaction {
            val existing = planningDao.getScheduledTask(id) ?: error("日期安排不存在")
            val before = snapshotFingerprint(existing.planId, listOf(existing.studyDate))
            require(existing.sourceTemplateId != null) { "这不是模板任务" }
            require(!existing.isLocked) { "任务已锁定，请先解锁" }
            ensureNoExecutionConflict(existing)
            planningDao.upsertScheduledTask(existing.copy(state = "DEFAULT", revision = existing.revision + 1))
            if (isCurrentDay(existing.studyDate)) taskRepository.reconcileDayWithPlan(existing.studyDate)
            journal(existing.planId, existing.studyDate, "RESTORE_TEMPLATE", existing.id, before)
        }
        if (date != null) refreshReminder(date)
    }

    private suspend fun validate(entry: ScheduledTaskEntity) {
        val plan = planRepository.getActivePlan()
        require(plan?.id == entry.planId) { "请先切换到该计划" }
        require(entry.studyDate >= currentDay()) { "不能改写过去的安排" }
        require(entry.studyDate >= plan.startDate) { "日期早于计划开始日" }
        val subject = planRepository.getSubject(entry.subjectId)
        require(subject?.planId == entry.planId && !subject.isArchived) { "科目不属于当前计划" }
        val slot = planRepository.getTimeSlot(entry.timeSlotId)
        require(slot?.planId == entry.planId && slot.isEnabled) { "时段不属于当前计划" }
        require(entry.title.isNotBlank() && entry.title.length <= 160) { "任务标题长度无效" }
        require(entry.targetValue in 1..9999) { "任务目标应为 1–9999" }
        require(entry.plannedMinutes == null || entry.plannedMinutes in 1..1440) { "预计时长无效" }
        require((entry.startTime == null) == (entry.endTime == null)) { "请同时填写开始和结束时间" }
        require(entry.startTime == null || entry.startTime != entry.endTime) { "时间段不能为空" }
        require(entry.state in if (entry.sourceTemplateId == null) setOf("ACTIVE", "CANCELLED")
            else setOf("OVERRIDE", "SUPPRESS", "DEFAULT")) { "任务状态无效" }
        if (entry.sourceTemplateId != null) {
            val template = planRepository.getTemplate(entry.sourceTemplateId)
                ?: throw IllegalArgumentException("原任务模板不存在")
            require(template.subjectId == entry.subjectId) { "原任务模板不属于当前科目" }
            val occurrence = TaskMaterializer.materialize(
                entry.studyDate, listOf(template),
                planRepository.getTimeSlots(entry.planId).associateBy { it.id },
                planRepository.getSubjects(entry.planId).associateBy { it.id },
                planRepository.getPhases(entry.planId).associateBy { it.id }, plan.startDate,
            )
            require(occurrence.any { it.templateId == entry.sourceTemplateId }) {
                "所选日期没有这条重复任务，无法替代"
            }
            val current = planningDao.getTemplateOccurrence(entry.planId, entry.studyDate, entry.sourceTemplateId)
            require(current == null || current.id == entry.id) { "同一天的模板任务已被细化" }
        }
        if (entry.resourceId != null) require(planningDao.getResource(entry.resourceId)?.let {
            it.planId == entry.planId && it.subjectId == entry.subjectId
        } == true) {
            "学习资料不属于此计划"
        }
        if (entry.goalId != null) require(planningDao.getGoal(entry.goalId)?.let {
            it.planId == entry.planId && it.subjectId == entry.subjectId &&
                (it.resourceId == null || it.resourceId == entry.resourceId) && it.roundKey == entry.roundKey
        } == true) {
            "学习目标不属于此计划"
        }
        val content = ContentSelectionCodec.decode(entry.contentJson)
        require(entry.contentJson.isBlank() || content != null) { "学习内容格式无效" }
        if (entry.goalId != null && content?.intervals?.isNotEmpty() == true) {
            val scope = planningDao.getGoal(entry.goalId)?.let { ContentSelectionCodec.decode(it.scopeJson) }
            require(scope == null || scope.chapter.isBlank() || content.chapter == scope.chapter) {
                "章节与学习目标不一致"
            }
            require(scope == null || scope.intervals.isEmpty() ||
                com.example.lixing.domain.planning.IntervalMath.remaining(content.intervals, scope.intervals).isEmpty()) {
                "题号范围超出学习目标"
            }
        }
        if (content != null && content.intervals.isNotEmpty() &&
            entry.targetType in setOf(TargetType.COUNT, TargetType.PAGES)
        ) require(content.quantity() == entry.targetValue) { "题号范围与目标数量不一致" }
        if (entry.state == "ACTIVE" || entry.state == "OVERRIDE") {
            val dayStart = prefs.current().dayStartTime
            val window = entry.startTime?.let { StudyDayWindow.of(entry.studyDate, it,
                requireNotNull(entry.endTime), dayStart) }
            if (window != null) {
                val slotWindow = StudyDayWindow.of(entry.studyDate, slot.startTime, slot.endTime, dayStart)
                val policy = planningDao.getDayPolicy(entry.planId, entry.studyDate)
                val dayWindows = policy?.let {
                    PlanningEngine.validateWindows(entry.studyDate, dayStart,
                        requireNotNull(AvailabilityCodec.decode(it.windowsJson)))
                }
                val availability = PlanningEngine.availableForSlot(slotWindow, dayWindows)
                require(availability.any { window.start >= it.start && window.end <= it.end }) {
                    "具体时间不在所选时段的可用窗口内"
                }
            }
            if (window != null && entry.plannedMinutes != null) {
                require(window.duration.toMinutes() == entry.plannedMinutes.toLong()) { "时间段与预计时长不一致" }
            }
            val candidates = planningDao.getScheduledTasksBetween(entry.planId,
                entry.studyDate.minusDays(1), entry.studyDate.plusDays(1))
            for (other in candidates) {
                if (other.id == entry.id || other.state !in setOf("ACTIVE", "OVERRIDE")) continue
                val otherWindow = other.startTime?.let {
                    StudyDayWindow.of(other.studyDate, it, requireNotNull(other.endTime), dayStart)
                } ?: continue
                if (window != null && window.start < otherWindow.end && otherWindow.start < window.end) {
                    error("安排与 ${other.studyDate} 的「${other.title}」时间重叠")
                }
            }
            val dayEntries = planningDao.getScheduledTasks(entry.planId, entry.studyDate)
                .filterNot { it.id == entry.id } + entry
            val effective = effectiveDay(entry.planId, entry.studyDate, dayEntries)
            val policy = planningDao.getDayPolicy(entry.planId, entry.studyDate)
            if (policy != null) {
                val windows = PlanningEngine.validateWindows(entry.studyDate, dayStart,
                    requireNotNull(AvailabilityCodec.decode(policy.windowsJson)))
                validateDayCapacity(effective, windows, policy.maxPlannedMinutes)
            }
        }
    }

    private suspend fun effectiveDay(planId: String, date: LocalDate,
        schedules: List<ScheduledTaskEntity>): List<DailyTaskEntity> {
        val plan = planRepository.getActivePlan()?.takeIf { it.id == planId } ?: return emptyList()
        val subjects = planRepository.getSubjects(planId).associateBy { it.id }
        val slots = planRepository.getTimeSlots(planId).associateBy { it.id }
        val recurring = TaskMaterializer.materialize(date, planRepository.getEnabledTemplates(planId),
            slots, subjects, planRepository.getPhases(planId).associateBy { it.id }, plan.startDate)
        return DailyScheduleResolver.resolve(date, recurring, schedules, slots, subjects)
    }

    private fun validateDayCapacity(tasks: List<DailyTaskEntity>, windows: List<com.example.lixing.domain.time.SlotWindow>,
        maximum: Int?) {
        val planned = tasks.sumOf { it.plannedMinutes ?: 0 }
        val capacity = minOf(PlanningEngine.capacityMinutes(windows), maximum ?: Int.MAX_VALUE)
        require(planned <= capacity) { "当天预计 $planned 分钟，超过可用容量 $capacity 分钟" }
    }

    private suspend fun ensureNoExecutionConflict(entry: ScheduledTaskEntity) {
        require(taskRepository.getDayRecord(entry.studyDate)?.isSettled != true) {
            "学习日已结算，不能重排"
        }
        val task = taskRepository.getTasksOfDay(entry.studyDate).firstOrNull {
            it.scheduleId == entry.id || entry.sourceTemplateId != null && it.templateId == entry.sourceTemplateId
        } ?: return
        val untouched = task.status == TaskStatus.PENDING ||
            task.status == TaskStatus.SKIPPED && task.skipReason == "PLAN_CANCELLED"
        require(untouched && task.checkedAt == null && task.actualValue == 0 &&
            task.focusedMinutes == 0 && focusSessionDao.countForTask(task.id) == 0 &&
            planningDao.getManualTimesOfTask(task.id).isEmpty() &&
            planningDao.getContentProgress(task.id).isEmpty()) { "任务已有学习记录，不能重排" }
    }

    private suspend fun currentDay(): LocalDate = StudyClock(dayStart = prefs.current().dayStartTime).today()
    private suspend fun isCurrentDay(date: LocalDate): Boolean = date == currentDay()

    private suspend fun journal(planId: String, date: LocalDate, kind: String, rowId: String,
        before: String) {
        val action = Json.encodeToString(mapOf("kind" to kind, "rowId" to rowId, "date" to date.toString()))
        planningDao.insertChangeSet(PlanChangeSetEntity(
            id = UUID.randomUUID().toString(), planId = planId, source = "MANUAL",
            actionsJson = action, selectedJson = "[0]", baseFingerprint = before,
            afterFingerprint = snapshotFingerprint(planId, listOf(date)),
            appliedAt = System.currentTimeMillis(),
        ))
    }

    private suspend fun refreshReminder(date: LocalDate) {
        if (db.inTransaction()) return // An outer assistant batch may still roll back.
        try { reminderScheduler?.scheduleFor(date) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The daily worker retries; a reminder failure must not undo a saved plan. */ }
    }
}
