package com.example.lixing.ui.screen.plan.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.ScheduledTaskEntity
import com.example.lixing.data.local.entity.StudyResourceEntity
import com.example.lixing.data.local.entity.LearningGoalEntity
import com.example.lixing.data.local.entity.PlanDayPolicyEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.PlanningRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import com.example.lixing.domain.planning.ContentInterval
import com.example.lixing.domain.planning.ContentSelection
import com.example.lixing.domain.planning.ContentSelectionCodec
import com.example.lixing.domain.planning.GoalContentStats
import com.example.lixing.domain.planning.AvailabilityWindow
import com.example.lixing.domain.planning.AvailabilityCodec
import com.example.lixing.domain.time.SlotWindow
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import javax.inject.Inject

data class DailyScheduleUiState(
    val planId: String? = null,
    val dayStart: LocalTime = LocalTime.MIDNIGHT,
    val selectedDate: LocalDate = LocalDate.now(),
    val tasksByDate: Map<LocalDate, List<DailyTaskEntity>> = emptyMap(),
    val scheduled: List<ScheduledTaskEntity> = emptyList(),
    val dayPolicies: List<PlanDayPolicyEntity> = emptyList(),
    val subjects: List<SubjectEntity> = emptyList(),
    val slots: List<TimeSlotEntity> = emptyList(),
    val templates: List<TaskTemplateEntity> = emptyList(),
    val resources: List<StudyResourceEntity> = emptyList(),
    val goals: List<LearningGoalEntity> = emptyList(),
    val goalStats: List<GoalContentStats> = emptyList(),
    val isSaving: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DailyScheduleViewModel @Inject constructor(
    private val plans: PlanRepository,
    private val planning: PlanningRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val _state = MutableStateFlow(DailyScheduleUiState())
    val state: StateFlow<DailyScheduleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            selectedDate.value = StudyClock(dayStart = prefs.current().dayStartTime).today()
        }
        viewModelScope.launch {
            combine(plans.activePlan.filterNotNull(), selectedDate) { plan, date -> plan to date }
                .flatMapLatest { (plan, date) ->
                    val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    combine(
                        planning.observeSchedules(plan.id, start, start.plusDays(6)).combine(
                            planning.observeDayPolicies(plan.id, start, start.plusDays(6))) { schedules, policies ->
                            schedules to policies
                        },
                        plans.observeTemplates(plan.id),
                        plans.observeSubjects(plan.id),
                        plans.observeTimeSlots(plan.id),
                        combine(planning.observeResources(plan.id), planning.observeGoals(plan.id),
                            planning.observeGoalContentStats(plan.id)) { resources, goals, stats ->
                            Triple(resources, goals, stats)
                        },
                    ) { scheduleData, templates, subjects, slots, catalog ->
                        val preview = planning.previewWeek(plan.id, start)
                        DailyScheduleUiState(
                            planId = plan.id,
                            dayStart = prefs.current().dayStartTime,
                            selectedDate = date,
                            tasksByDate = preview,
                            scheduled = scheduleData.first,
                            dayPolicies = scheduleData.second,
                            subjects = subjects,
                            slots = slots,
                            templates = templates,
                            resources = catalog.first,
                            goals = catalog.second,
                            goalStats = catalog.third,
                            isSaving = _state.value.isSaving,
                            message = _state.value.message,
                        )
                    }
                }.collect { latest -> _state.value = latest }
        }
    }

    fun selectDate(date: LocalDate) { selectedDate.value = date }
    fun shiftDay(days: Long) { selectedDate.value = selectedDate.value.plusDays(days) }
    fun clearMessage() { _state.update { it.copy(message = null) } }

    fun saveDayPolicy(date: LocalDate, windows: List<AvailabilityWindow>, maxMinutes: Int?, reason: String) {
        viewModelScope.launch {
            runCatching {
                val planId = requireNotNull(_state.value.planId)
                val existing = _state.value.dayPolicies.firstOrNull { it.studyDate == date }
                planning.saveDayPolicy(PlanDayPolicyEntity(
                    id = existing?.id ?: UUID.nameUUIDFromBytes("lixing:day-policy:$planId:$date".toByteArray()).toString(),
                    planId = planId, studyDate = date, windowsJson = AvailabilityCodec.encode(windows),
                    maxPlannedMinutes = maxMinutes, reason = reason.trim(),
                    isLocked = existing?.isLocked ?: false,
                ))
            }.onSuccess { _state.update { it.copy(message = "已保存 $date 的可用时间") } }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "保存可用时间失败") } }
        }
    }

    fun clearDayPolicy(date: LocalDate) {
        viewModelScope.launch {
            runCatching { planning.clearDayPolicy(requireNotNull(_state.value.planId), date) }
                .onSuccess { _state.update { it.copy(message = "已恢复周期可用时间") } }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "恢复可用时间失败") } }
        }
    }

    fun save(
        date: LocalDate,
        subjectId: String,
        slotId: String,
        title: String,
        resourceName: String,
        chapter: String,
        questionFirst: Int?,
        questionLast: Int?,
        start: LocalTime?,
        end: LocalTime?,
        estimatedMinutes: Int?,
        sourceTemplateId: String?,
        goalId: String? = null,
        editingId: String? = null,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, message = null) }
            try {
                val planId = requireNotNull(_state.value.planId)
                val goal = goalId?.let { id ->
                    _state.value.goals.firstOrNull { it.id == id && it.subjectId == subjectId }
                        ?: error("所选学习目标不属于当前科目")
                }
                require((questionFirst == null) == (questionLast == null)) { "请同时填写起止题号" }
                val interval = if (questionFirst != null && questionLast != null) {
                    ContentInterval(questionFirst, questionLast)
                } else null
                val goalResource = goal?.resourceId?.let { id ->
                    _state.value.resources.firstOrNull { it.id == id }
                        ?: error("学习目标关联的资料不存在")
                }
                require(goalResource == null || resourceName.isBlank() ||
                    resourceName.trim() == goalResource.name) { "资料与学习目标不一致" }
                val resource = goalResource ?: resourceName.trim().takeIf { it.isNotEmpty() }?.let { name ->
                    _state.value.resources.firstOrNull { it.subjectId == subjectId && it.name == name }
                        ?: StudyResourceEntity(planId = planId, subjectId = subjectId, name = name).also {
                            planning.saveResource(it)
                        }
                }
                val content = if (resource != null || chapter.isNotBlank() || interval != null) {
                    ContentSelection(
                        resourceName = resource?.name.orEmpty(),
                        edition = resource?.edition.orEmpty(),
                        chapter = chapter.trim(),
                        kind = if (interval == null) "UNIT" else "QUESTION",
                        intervals = listOfNotNull(interval),
                        roundKey = goal?.roundKey ?: "FIRST",
                    )
                } else null
                val minutes = estimatedMinutes ?: if (start != null && end != null) {
                    SlotWindow.of(date, start, end).duration.toMinutes().toInt()
                } else null
                val existing = editingId?.let { id -> _state.value.scheduled.firstOrNull { it.id == id } }
                    ?: sourceTemplateId?.let { template ->
                    _state.value.scheduled.firstOrNull {
                        it.studyDate == date && it.sourceTemplateId == template
                    }
                }
                require(existing == null || existing.studyDate == date) { "不能直接改变已确认安排的日期" }
                require(existing == null || existing.sourceTemplateId == sourceTemplateId) {
                    "不能在编辑时改变新增/替代类型"
                }
                val id = existing?.id ?: if (sourceTemplateId != null) {
                    UUID.nameUUIDFromBytes("lixing:override:$planId:$date:$sourceTemplateId".toByteArray()).toString()
                } else UUID.randomUUID().toString()
                planning.saveSchedule(ScheduledTaskEntity(
                    id = id,
                    planId = planId,
                    studyDate = date,
                    sourceTemplateId = sourceTemplateId,
                    subjectId = subjectId,
                    timeSlotId = slotId,
                    resourceId = resource?.id,
                    goalId = goal?.id,
                    roundKey = goal?.roundKey ?: "FIRST",
                    title = title.trim().ifBlank { content?.displayText().orEmpty() },
                    taskType = if (interval != null) TaskType.PRACTICE else TaskType.LECTURE,
                    targetType = if (interval != null) TargetType.COUNT else TargetType.BOOLEAN,
                    targetValue = interval?.size ?: 1,
                    contentJson = content?.let(ContentSelectionCodec::encode).orEmpty(),
                    plannedMinutes = minutes,
                    startTime = start,
                    endTime = end,
                    state = if (sourceTemplateId != null) "OVERRIDE" else "ACTIVE",
                ))
                _state.update { it.copy(message = "已保存 $date 的具体安排") }
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "保存安排失败") }
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    fun saveResource(resource: StudyResourceEntity) {
        viewModelScope.launch {
            runCatching { planning.saveResource(resource) }
                .onSuccess { _state.update { it.copy(message = "已保存学习资料") } }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "保存资料失败") } }
        }
    }

    fun saveGoal(goal: LearningGoalEntity) {
        viewModelScope.launch {
            runCatching { planning.saveGoal(goal) }
                .onSuccess { _state.update { it.copy(message = "已保存学习目标") } }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "保存目标失败") } }
        }
    }

    fun cancel(scheduleId: String) {
        viewModelScope.launch {
            runCatching { planning.cancelSchedule(scheduleId) }
                .onSuccess { _state.update { it.copy(message = "已取消安排") } }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "取消失败") } }
        }
    }

    fun restore(scheduleId: String) {
        viewModelScope.launch {
            runCatching { planning.restoreTemplateOccurrence(scheduleId) }
                .onSuccess { _state.update { it.copy(message = "已恢复重复任务") } }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "恢复失败") } }
        }
    }
}
