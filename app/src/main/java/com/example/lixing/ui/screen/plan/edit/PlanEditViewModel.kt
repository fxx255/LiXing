package com.example.lixing.ui.screen.plan.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskType
import com.example.lixing.domain.model.WeekdayMask
import com.example.lixing.domain.time.StudyClock
import com.example.lixing.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/**
 * 计划编辑器 ViewModel。同时服务阶段/科目/时段/任务模板四类编辑页，
 * 由路由参数里的 id 决定加载哪个（-1 = 新建）。
 *
 * 表单字段用可空的 StateFlow 存，保存时组装成实体写库。
 */
@HiltViewModel
class PlanEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val prefsRepository: UserPreferencesRepository,
) : ViewModel() {

    // UUID 主键后，编辑参数从导航字符串读入；"-1" 表示新建
    private val slotId: String? = savedStateHandle.get<String>(Routes.ARG_SLOT_ID)
        ?.takeIf { it.isNotBlank() && it != "-1" }
    private val subjectId: String? = savedStateHandle.get<String>(Routes.ARG_SUBJECT_ID)
        ?.takeIf { it.isNotBlank() && it != "-1" }
    private val templateId: String? = savedStateHandle.get<String>(Routes.ARG_TEMPLATE_ID)
        ?.takeIf { it.isNotBlank() && it != "-1" }
    private val phaseId: String? = savedStateHandle.get<String>(Routes.ARG_PHASE_ID)
        ?.takeIf { it.isNotBlank() && it != "-1" }

    // ---------- 通用 ----------
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()
    fun setName(v: String) { _name.value = v }

    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()
    fun setNote(v: String) { _note.value = v }

    // ---------- 时段 ----------
    // 表单必须保存“正在输入的文本”。若直接绑定 LocalTime，删掉一个字符时中间值
    // 无法解析，受控输入框会立刻弹回旧时间，导致用户实际上无法修改。
    private val _slotStartText = MutableStateFlow("00:00")
    val slotStartText: StateFlow<String> = _slotStartText.asStateFlow()
    fun setSlotStartText(v: String) { _slotStartText.value = v; _slotTimeError.value = null }

    private val _slotEndText = MutableStateFlow("00:00")
    val slotEndText: StateFlow<String> = _slotEndText.asStateFlow()
    fun setSlotEndText(v: String) { _slotEndText.value = v; _slotTimeError.value = null }

    private val _slotTimeError = MutableStateFlow<String?>(null)
    val slotTimeError: StateFlow<String?> = _slotTimeError.asStateFlow()

    private val _slotRequiredTaskCount = MutableStateFlow(0)
    val slotRequiredTaskCount: StateFlow<Int> = _slotRequiredTaskCount.asStateFlow()
    fun setSlotRequiredTaskCount(v: Int) { _slotRequiredTaskCount.value = v.coerceAtLeast(0) }

    private val _weekdays = MutableStateFlow(WeekdayMask.EVERY_DAY)
    val weekdays: StateFlow<WeekdayMask> = _weekdays.asStateFlow()
    fun toggleWeekday(day: java.time.DayOfWeek) { _weekdays.value = _weekdays.value.toggle(day) }

    // ---------- 科目 ----------
    private val _colorArgb = MutableStateFlow(0xFF3D7BE0.toInt())
    val colorArgb: StateFlow<Int> = _colorArgb.asStateFlow()
    fun setColor(v: Int) { _colorArgb.value = v }

    // ---------- 任务模板 ----------
    private val _subjects = MutableStateFlow<List<SubjectEntity>>(emptyList())
    val subjects: StateFlow<List<SubjectEntity>> = _subjects.asStateFlow()

    private val _slots = MutableStateFlow<List<TimeSlotEntity>>(emptyList())
    val slots: StateFlow<List<TimeSlotEntity>> = _slots.asStateFlow()

    private val _phases = MutableStateFlow<List<PhaseEntity>>(emptyList())
    val phases: StateFlow<List<PhaseEntity>> = _phases.asStateFlow()

    private val _subjectIdSel = MutableStateFlow<String?>(null)
    val subjectIdSel: StateFlow<String?> = _subjectIdSel.asStateFlow()
    fun setSubject(v: String) { _subjectIdSel.value = v }

    private val _slotIdSel = MutableStateFlow<String?>(null)
    val slotIdSel: StateFlow<String?> = _slotIdSel.asStateFlow()
    fun setSlot(v: String) { _slotIdSel.value = v }

    private val _phaseIdSel = MutableStateFlow<String?>(null)
    val phaseIdSel: StateFlow<String?> = _phaseIdSel.asStateFlow()
    fun setPhase(v: String?) { _phaseIdSel.value = v }

    private val _taskType = MutableStateFlow(TaskType.CUSTOM)
    val taskType: StateFlow<TaskType> = _taskType.asStateFlow()
    fun setTaskType(v: TaskType) { _taskType.value = v }

    private val _targetType = MutableStateFlow(TargetType.BOOLEAN)
    val targetType: StateFlow<TargetType> = _targetType.asStateFlow()
    fun setTargetType(v: TargetType) { _targetType.value = v }

    private val _targetValue = MutableStateFlow(1)
    val targetValue: StateFlow<Int> = _targetValue.asStateFlow()
    fun setTargetValue(v: Int) { _targetValue.value = v.coerceAtLeast(1) }

    private val _isKeystone = MutableStateFlow(false)
    val isKeystone: StateFlow<Boolean> = _isKeystone.asStateFlow()
    fun setKeystone(v: Boolean) { _isKeystone.value = v }

    private val _repeatRule = MutableStateFlow(RepeatRule.DAILY)
    val repeatRule: StateFlow<RepeatRule> = _repeatRule.asStateFlow()
    fun setRepeatRule(v: RepeatRule) { _repeatRule.value = v }

    // ---------- 阶段 ----------
    private val _phaseStart = MutableStateFlow(java.time.LocalDate.now())
    val phaseStart: StateFlow<java.time.LocalDate> = _phaseStart.asStateFlow()
    fun setPhaseStart(v: java.time.LocalDate) { _phaseStart.value = v }

    private val _phaseEnd = MutableStateFlow(java.time.LocalDate.now().plusMonths(3))
    val phaseEnd: StateFlow<java.time.LocalDate> = _phaseEnd.asStateFlow()
    fun setPhaseEnd(v: java.time.LocalDate) { _phaseEnd.value = v }

    private var activePlanId: String? = null

    init {
        viewModelScope.launch {
            activePlanId = planRepository.getActivePlan()?.id

            // 任务模板编辑需要的下拉数据
            activePlanId?.let { pid ->
                _subjects.value = planRepository.getSubjects(pid)
                _slots.value = planRepository.getTimeSlots(pid)
                _phases.value = planRepository.getPhases(pid)
            }

            load()
        }
    }

    private suspend fun load() {
        if (slotId != null) planRepository.getTimeSlot(slotId)?.let {
            _name.value = it.name
            _slotStartText.value = formatTime(it.startTime)
            _slotEndText.value = formatTime(it.endTime)
            _slotRequiredTaskCount.value = it.requiredTaskCount
            _weekdays.value = WeekdayMask(it.weekdayMask); _note.value = it.note
        }
        if (subjectId != null) planRepository.getSubject(subjectId)?.let {
            _name.value = it.name; _colorArgb.value = it.colorArgb; _note.value = it.iconKey
        }
        if (phaseId != null) planRepository.getPhase(phaseId)?.let {
            _name.value = it.name; _phaseStart.value = it.startDate; _phaseEnd.value = it.endDate; _note.value = it.description
        }
        if (templateId != null) planRepository.getTemplate(templateId)?.let {
            _name.value = it.title; _subjectIdSel.value = it.subjectId; _slotIdSel.value = it.timeSlotId
            _taskType.value = it.taskType; _targetType.value = it.targetType
            _targetValue.value = it.targetValue; _isKeystone.value = it.isKeystone
            _repeatRule.value = it.repeatRule; _phaseIdSel.value = it.phaseId; _note.value = it.note
        }
    }

    // ---------- 保存 ----------

    fun saveSlot(onDone: () -> Unit) = viewModelScope.launch {
        val planId = activePlanId ?: return@launch
        val start = EditableTimeParser.parse(_slotStartText.value)
        val end = EditableTimeParser.parse(_slotEndText.value)
        if (start == null || end == null) {
            _slotTimeError.value = "请输入有效时间（00:00–23:59）"
            return@launch
        }
        val old = if (slotId != null) planRepository.getTimeSlot(slotId) else null
        planRepository.upsertTimeSlot(
            (old ?: TimeSlotEntity(
                planId = planId,
                name = _name.value.ifBlank { "新时段" },
                startTime = start,
                endTime = end,
                sortOrder = 99,
            )).copy(
                name = _name.value.ifBlank { "新时段" },
                startTime = start,
                endTime = end,
                weekdayMask = _weekdays.value.value,
                note = _note.value,
                requiredTaskCount = _slotRequiredTaskCount.value,
            ),
        )
        refreshToday()
        onDone()
    }

    fun saveSubject(onDone: () -> Unit) = viewModelScope.launch {
        val planId = activePlanId ?: return@launch
        val old = if (subjectId != null) planRepository.getSubject(subjectId) else null
        planRepository.upsertSubject(
            (old ?: SubjectEntity(
                planId = planId,
                name = _name.value.ifBlank { "新科目" },
                colorArgb = _colorArgb.value,
                sortOrder = 99,
            )).copy(
                name = _name.value.ifBlank { "新科目" },
                colorArgb = _colorArgb.value,
            ),
        )
        refreshToday()
        onDone()
    }

    fun savePhase(onDone: () -> Unit) = viewModelScope.launch {
        val planId = activePlanId ?: return@launch
        val old = if (phaseId != null) planRepository.getPhase(phaseId) else null
        planRepository.upsertPhase(
            (old ?: PhaseEntity(
                planId = planId,
                name = _name.value.ifBlank { "新阶段" },
                startDate = _phaseStart.value,
                endDate = _phaseEnd.value,
                sortOrder = 99,
            )).copy(
                name = _name.value.ifBlank { "新阶段" },
                startDate = _phaseStart.value,
                endDate = _phaseEnd.value,
                description = _note.value,
            ),
        )
        refreshToday()
        onDone()
    }

    fun saveTemplate(onDone: () -> Unit) = viewModelScope.launch {
        val subj = _subjectIdSel.value ?: _subjects.value.firstOrNull()?.id ?: return@launch
        val slot = _slotIdSel.value ?: _slots.value.firstOrNull()?.id ?: return@launch
        val old = if (templateId != null) planRepository.getTemplate(templateId) else null
        planRepository.upsertTemplate(
            (old ?: TaskTemplateEntity(
                subjectId = subj,
                timeSlotId = slot,
                title = _name.value.ifBlank { "新任务" },
                sortOrder = 99,
            )).copy(
                subjectId = subj,
                timeSlotId = slot,
                title = _name.value.ifBlank { "新任务" },
                taskType = _taskType.value,
                targetType = _targetType.value,
                targetValue = _targetValue.value,
                repeatRule = _repeatRule.value,
                weekdayMask = _weekdays.value.value,
                phaseId = _phaseIdSel.value,
                isKeystone = _isKeystone.value,
                note = _note.value,
            ),
        )
        refreshToday()
        onDone()
    }

    // ---------- 删除 ----------

    fun deleteSlot(onDone: () -> Unit) = viewModelScope.launch {
        val today = studyToday()
        val id = slotId
        if (id != null) {
            taskRepository.removePendingSlot(today, id)
            planRepository.getTimeSlot(id)?.let { planRepository.deleteTimeSlot(it) }
        }
        taskRepository.reconcileDayWithPlan(today)
        onDone()
    }

    fun deleteSubject(onDone: () -> Unit) = viewModelScope.launch {
        val today = studyToday()
        val id = subjectId
        if (id != null) {
            taskRepository.removePendingSubject(today, id)
            planRepository.getSubject(id)?.let { planRepository.deleteSubject(it) }
        }
        taskRepository.reconcileDayWithPlan(today)
        onDone()
    }

    fun deletePhase(onDone: () -> Unit) = viewModelScope.launch {
        val id = phaseId
        if (id != null) {
            planRepository.getPhase(id)?.let { planRepository.deletePhase(it) }
            refreshToday()
        }
        onDone()
    }

    fun deleteTemplate(onDone: () -> Unit) = viewModelScope.launch {
        val today = studyToday()
        val id = templateId
        if (id != null) {
            taskRepository.removePendingTemplate(today, id)
            planRepository.getTemplate(id)?.let { planRepository.deleteTemplate(it) }
        }
        taskRepository.reconcileDayWithPlan(today)
        onDone()
    }

    private suspend fun refreshToday() {
        taskRepository.reconcileDayWithPlan(studyToday())
    }

    private suspend fun studyToday() =
        StudyClock(dayStart = prefsRepository.current().dayStartTime).today()

    private fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)
}

/** 兼容手机输入法产生的全角冒号、全角数字以及冒号两侧空格。 */
internal object EditableTimeParser {
    private val timePattern = Regex("""^(\d{1,2})\s*:\s*(\d{1,2})$""")

    fun parse(text: String): LocalTime? {
        val normalized = buildString(text.length) {
            text.trim().forEach { c ->
                when {
                    c == '：' -> append(':')
                    c.isDigit() -> append(Character.digit(c, 10))
                    else -> append(c)
                }
            }
        }
        val match = timePattern.matchEntire(normalized) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }
}
