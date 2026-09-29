package com.example.lixing.ui.screen.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.FocusSessionEntity
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.FocusRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.model.FocusMode
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class FocusUiState(
    val isRunning: Boolean = false,
    val mode: FocusMode = FocusMode.POMODORO,
    /** 番茄钟总时长（秒）。 */
    val pomodoroSeconds: Int = 45 * 60,
    /** 已运行秒数。 */
    val elapsedSeconds: Int = 0,
    /** 番茄钟剩余秒数（正计时模式下无意义）。 */
    val remainingSeconds: Int = 0,
    val interruptions: Int = 0,
    val todayFocusMinutes: Int = 0,
    val totalFocusMinutes: Int = 0,
    val selectedTaskId: String? = null,
    val selectedTaskTitle: String? = null,
    val selectedSubjectId: String? = null,
    val selectedSubjectName: String = "未分类",
    val availableTasks: List<DailyTaskEntity> = emptyList(),
    val availableSubjects: List<SubjectEntity> = emptyList(),
)

@HiltViewModel
class FocusViewModel @Inject constructor(
    private val focusRepository: FocusRepository,
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val prefsRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FocusUiState())
    val state: StateFlow<FocusUiState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var currentSessionId: String? = null
    private var startedAt: Instant? = null
    /** ON_PAUSE followed by ON_STOP must count as one interruption. */
    private var interruptionReportedForBackground = false
    /** 中断只在用户接受并记录本次专注时落库；放弃时全部丢弃。 */
    private var pendingInterruptions = 0

    private var clock = StudyClock()

    init {
        viewModelScope.launch {
            val prefs = prefsRepository.current()
            clock = StudyClock(dayStart = prefs.dayStartTime)
            _state.update {
                it.copy(
                    pomodoroSeconds = prefs.pomodoroMinutes * 60,
                    remainingSeconds = prefs.pomodoroMinutes * 60,
                )
            }
            taskRepository.observeTasksOfDay(clock.today()).collect { tasks ->
                _state.update { it.copy(availableTasks = tasks) }
            }
        }
        viewModelScope.launch {
            planRepository.activePlan.collect { plan ->
                val subjects = plan?.let { p -> planRepository.getSubjects(p.id) }.orEmpty()
                _state.update { it.copy(availableSubjects = subjects) }
            }
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    todayFocusMinutes = focusRepository.getMinutesOfDay(clock.today()),
                    totalFocusMinutes = focusRepository.getTotalMinutes(),
                )
            }
        }
        resumeIfRunning()
    }

    /** 进程恢复时，如果数据库里有进行中的会话，接着计时。 */
    private fun resumeIfRunning() {
        viewModelScope.launch {
            val running = focusRepository.getRunningSession() ?: return@launch
            val task = running.dailyTaskId?.let { taskRepository.getTask(it) }
            currentSessionId = running.id
            startedAt = running.startedAt
            _state.update {
                it.copy(
                    isRunning = true,
                    mode = running.mode,
                    interruptions = running.interruptionCount,
                    selectedTaskId = running.dailyTaskId,
                    selectedSubjectId = running.subjectId,
                    selectedTaskTitle = task?.title,
                    selectedSubjectName = task?.subjectName ?: _state.value.availableSubjects
                        .firstOrNull { subject -> subject.id == running.subjectId }?.name ?: "未分类",
                )
            }
            startTicking()
        }
    }

    fun setPomodoroMinutes(minutes: Int) {
        _state.update {
            it.copy(
                pomodoroSeconds = minutes * 60,
                remainingSeconds = if (!it.isRunning) minutes * 60 else it.remainingSeconds,
            )
        }
    }

    fun startPomodoro() = start(FocusMode.POMODORO)

    fun startCountUp() = start(FocusMode.COUNT_UP)

    private fun start(mode: FocusMode) {
        viewModelScope.launch {
            val date = clock.today()
            val task = _state.value.selectedTaskId?.let { taskRepository.getTask(it) }
                ?.takeIf { it.date == date }
            val subjectId = task?.subjectId ?: _state.value.selectedSubjectId
                ?.takeIf { id -> _state.value.availableSubjects.any { it.id == id && !it.isArchived } }

            val sessionId = focusRepository.start(
                date = date,
                dailyTaskId = task?.id,
                subjectId = subjectId,
                mode = mode,
                plannedMinutes = _state.value.pomodoroSeconds / 60,
            )
            currentSessionId = sessionId
            startedAt = focusRepository.getSession(sessionId)?.startedAt ?: Instant.now()
            pendingInterruptions = 0
            interruptionReportedForBackground = false
            _state.update {
                it.copy(
                    isRunning = true,
                    mode = mode,
                    elapsedSeconds = 0,
                    remainingSeconds = it.pomodoroSeconds,
                    interruptions = 0,
                )
            }
            startTicking()
        }
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val start = startedAt ?: continue
                val elapsed = (Instant.now().epochSecond - start.epochSecond).toInt().coerceAtLeast(0)
                _state.update { s ->
                    val remaining = (s.pomodoroSeconds - elapsed).coerceAtLeast(0)
                    s.copy(elapsedSeconds = elapsed, remainingSeconds = remaining)
                }
                // 番茄钟走完自动结束
                if (_state.value.mode == FocusMode.POMODORO &&
                    _state.value.remainingSeconds <= 0
                ) {
                    finish(completed = true)
                    break
                }
            }
        }
    }

    /** 完成/结束计时。 */
    fun finish(completed: Boolean) {
        val sessionId = currentSessionId ?: return
        val elapsedMinutes = _state.value.elapsedSeconds / 60
        val interruptionsToCommit = pendingInterruptions
        tickJob?.cancel()
        viewModelScope.launch {
            focusRepository.finish(
                sessionId = sessionId,
                effectiveMinutes = elapsedMinutes,
                completed = completed,
            )
            focusRepository.addInterruptions(sessionId, interruptionsToCommit)
            currentSessionId = null
            startedAt = null
            pendingInterruptions = 0
            _state.update {
                it.copy(
                    isRunning = false,
                    elapsedSeconds = 0,
                    remainingSeconds = it.pomodoroSeconds,
                    todayFocusMinutes = focusRepository.getMinutesOfDay(clock.today()),
                    totalFocusMinutes = focusRepository.getTotalMinutes(),
                )
            }
        }
    }

    fun abandon() {
        val sessionId = currentSessionId ?: return
        tickJob?.cancel()
        viewModelScope.launch {
            focusRepository.abandon(sessionId)
            currentSessionId = null
            startedAt = null
            pendingInterruptions = 0
            interruptionReportedForBackground = false
            _state.update {
                it.copy(
                    isRunning = false,
                    interruptions = 0,
                    elapsedSeconds = 0,
                    remainingSeconds = it.pomodoroSeconds,
                )
            }
        }
    }

    fun selectTask(taskId: String, title: String) {
        val task = _state.value.availableTasks.firstOrNull { it.id == taskId } ?: return
        _state.update { it.copy(
            selectedTaskId = taskId, selectedTaskTitle = title,
            selectedSubjectId = task.subjectId, selectedSubjectName = task.subjectName,
        ) }
    }

    fun selectFreeSubject(subjectId: String?) {
        val subject = _state.value.availableSubjects.firstOrNull { it.id == subjectId }
        _state.update { it.copy(
            selectedTaskId = null, selectedTaskTitle = null,
            selectedSubjectId = subject?.id, selectedSubjectName = subject?.name ?: "未分类",
        ) }
    }

    /**
     * Called by the screen lifecycle when the app is no longer visible.
     * The old implementation only updated the timer from a coroutine and never
     * recorded lifecycle changes, so switching to another app always showed 0.
     */
    fun onAppBackground() {
        val sessionId = currentSessionId ?: return
        if (!_state.value.isRunning || interruptionReportedForBackground) return
        interruptionReportedForBackground = true
        pendingInterruptions++
        _state.update { it.copy(interruptions = it.interruptions + 1) }
    }

    /** Allow the next background transition to be counted separately. */
    fun onAppForeground() {
        interruptionReportedForBackground = false
    }

    override fun onCleared() {
        tickJob?.cancel()
        super.onCleared()
    }
}
