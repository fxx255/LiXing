package com.example.lixing.ui.screen.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.GamificationRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.model.Mood
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.settle.DayStats
import com.example.lixing.domain.time.SlotState
import com.example.lixing.domain.time.SlotWindow
import com.example.lixing.domain.time.StudyClock
import com.example.lixing.domain.model.WeekdayMask
import com.example.lixing.domain.usecase.CheckInResult
import com.example.lixing.domain.usecase.CheckInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val gamification: GamificationRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val checkIn: CheckInUseCase,
    private val settleDay: com.example.lixing.domain.usecase.SettleDayUseCase,
    private val wordSource: com.example.lixing.domain.word.WordSource,
    private val wordSync: com.example.lixing.domain.usecase.WordSyncUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    /** 一次性 UI 事件（打卡成功提示、升级弹窗、成就动画）。 */
    private val _events = MutableStateFlow<TodayEvent?>(null)
    val events: StateFlow<TodayEvent?> = _events.asStateFlow()

    /** 当前打卡对话框的目标任务。null = 关闭。 */
    private val _checkInTarget = MutableStateFlow<DailyTaskEntity?>(null)
    val checkInTarget: StateFlow<DailyTaskEntity?> = _checkInTarget.asStateFlow()

    private val clock = MutableStateFlow(buildClock(java.time.LocalTime.of(4, 0)))

    /**
     * 当天任务的唯一数据源快照。
     *
     * 时钟每 30 秒要刷新时段状态（进行中/已过），但不能拿 slotSections 反向
     * flatMap 回任务列表再重组——那样每次刷新都会把列表重新展开一遍，
     * 冷启动时多个 flow 同时发射就会叠成两份（卡片翻倍的根因）。
     * 这里存一份原始列表，刷新时只重算派生值。
     */
    private var currentTasks: List<DailyTaskEntity> = emptyList()
    private var currentSlots: List<TimeSlotEntity> = emptyList()

    init {
        bootstrap()
        observe()
        observeMaimemo()
        // 每秒走一下，驱动时段状态与倒计时刷新
        startClock()
    }

    private fun buildClock(dayStart: LocalTime): StudyClock = StudyClock(dayStart = dayStart)

    private fun startClock() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                // 用缓存的原始任务列表重算，不从 UI 状态反推，避免累积叠加
                _state.update { rebuildSections(it, currentTasks, currentSlots) }
            }
        }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val prefs = prefsRepository.current()
            clock.value = buildClock(prefs.dayStartTime)
            prefsRepository.preferences.collect { p ->
                clock.value = buildClock(p.dayStartTime)
            }
        }
        viewModelScope.launch {
            // 物化 + 补结算 + 激励初始化，放后台不阻塞首帧
            val settlement = settleDay.onAppStart(clock.value)
            // 有承诺到期就提示一次。不打断打卡：只在没有其他事件排队时弹
            if (settlement.commitments.isNotEmpty() && _events.value == null) {
                _events.value = TodayEvent.CommitmentSettled(settlement.commitments)
            }
        }
    }

    private fun observe() {
        viewModelScope.launch {
            planRepository.activePlan.collect { plan ->
                _state.update { it.copy(plan = plan, hasNoPlan = plan == null, isLoading = plan == null && it.slotSections.isEmpty() && !it.hasNoPlan) }
                if (plan == null) {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }

        viewModelScope.launch {
            planRepository.activePlan
                .filterNotNull()
                .flatMapLatest { plan -> planRepository.observeCurrentPhase(clock.value.today()) }
                .collect { phase -> _state.update { it.copy(currentPhase = phase) } }
        }

        // 今天的任务
        viewModelScope.launch {
            planRepository.activePlan
                .filterNotNull()
                .flatMapLatest { taskRepository.observeTasksOfDay(clock.value.today()) }
                .collect { tasks ->
                    // 以库为唯一真相：整体替换缓存，不做累加
                    currentTasks = tasks.distinctBy { it.id }
                _state.update { rebuildSections(it, currentTasks, currentSlots) }
            }
        }

        // 今日页也要监听计划时段本身。这样新增一个暂时没有任务的时段，
        // 仍会显示它的时段卡片，而不是只能等第一条任务物化后才出现。
        viewModelScope.launch {
            planRepository.activePlan
                .filterNotNull()
                .flatMapLatest { plan -> planRepository.observeTimeSlots(plan.id) }
                .collect { slots ->
                    currentSlots = slots
                    _state.update { rebuildSections(it, currentTasks, currentSlots) }
                }
        }

        // 连续记录 + 本周方块
        viewModelScope.launch {
            gamification.streak.collect { streak ->
                _state.update {
                    it.copy(
                        currentStreak = streak?.currentStreak ?: 0,
                        longestStreak = streak?.longestStreak ?: 0,
                        rescueCardsLeft = streak?.rescueCardsLeft ?: 0,
                    )
                }
            }
        }

        // 档案：等级 / 积分 / 称号
        viewModelScope.launch {
            gamification.profile.collect { profile ->
                _state.update {
                    it.copy(
                        level = profile?.level ?: 1,
                        totalPoints = profile?.totalPoints ?: 0,
                        title = profile?.title ?: "初心者",
                    )
                }
            }
        }

        // 本周达成方块
        viewModelScope.launch {
            planRepository.activePlan
                .filterNotNull()
                .flatMapLatest {
                    val monday = clock.value.today().with(DayOfWeek.MONDAY)
                    taskRepository.observeDayRecordsBetween(monday, monday.plusDays(6))
                }
                .collect { records ->
                    val byDate = records.associateBy { it.date }
                    val flags = (0..6).map { offset ->
                        val date = clock.value.today().with(DayOfWeek.MONDAY).plusDays(offset.toLong())
                        val rec = byDate[date]
                        when {
                            date > clock.value.today() -> null
                            rec == null -> null
                            rec.isDayOff -> null
                            else -> rec.isAchieved
                        }
                    }
                    _state.update { it.copy(weekAchieved = flags) }
                }
        }
    }

    /** 把任务列表重组为时段区块。 */
    private fun rebuildSections(
        state: TodayUiState,
        tasks: List<DailyTaskEntity>,
        slots: List<TimeSlotEntity>,
    ): TodayUiState {
        val now = clock.value.nowDateTime()
        val today = clock.value.today()
        // 按 id 去重再分组：LazyColumn 的 key 是 slotId，
        // 一旦同一时段出现两个 section，Compose 会因 key 重复丢弃其中一个，
        // 表现为「整个上午凭空消失」。
        val unique = tasks.distinctBy { it.id }
        // 仅今日跳过/请假的任务保留在数据库和历史中，但不再占据今日待办列表。
        val visibleTasks = unique.filter { it.status != TaskStatus.SKIPPED }
        val bySlot = visibleTasks.groupBy { it.timeSlotId }
        val dayStats = com.example.lixing.domain.settle.DayStatsCalculator.calculate(unique)

        val activeSlots = slots.filter { slot ->
            slot.isEnabled && WeekdayMask(slot.weekdayMask).contains(today.dayOfWeek)
        }
        val sections = activeSlots.map { slot ->
            val list = bySlot[slot.id].orEmpty()
            val window = SlotWindow.of(today, slot.startTime, slot.endTime)
            val slotState = SlotState.of(window, now)
            SlotSection(
                slotId = slot.id,
                slotName = slot.name,
                start = slot.startTime,
                end = slot.endTime,
                sortOrder = slot.sortOrder,
                state = slotState,
                isCurrent = slotState == SlotState.ONGOING,
                tasks = list.sortedWith(compareBy({ it.sortOrder }, { it.id })),
                note = slot.note,
                requiredTaskCount = slot.requiredTaskCount,
            )
        }.toMutableList().apply {
            // 旧任务可能来自已删除的时段；已存在的快照仍保留，避免历史/已完成记录消失。
            val known = map { it.slotId }.toSet()
            bySlot.filterKeys { it !in known }.forEach { (slotId, list) ->
                val first = list.first()
                val window = SlotWindow.of(today, first.slotStart, first.slotEnd)
                val slotState = SlotState.of(window, now)
                add(SlotSection(slotId, first.slotName, first.slotStart, first.slotEnd, first.slotSortOrder,
                    slotState, slotState == SlotState.ONGOING,
                    list.sortedWith(compareBy({ it.sortOrder }, { it.id })),
                    requiredTaskCount = first.slotRequiredTaskCount))
            }
        }.sortedWith(
            compareBy({ !it.isCurrent }, { it.sortOrder }, { it.start }),
        )

        val daysToTarget = state.plan?.let {
            ChronoUnit.DAYS.between(today, it.targetDate).toInt()
        } ?: 0

        return state.copy(
            today = today,
            slotSections = sections,
            stats = dayStats,
            daysToTarget = daysToTarget,
            isLoading = false,
            hasNoPlan = state.hasNoPlan,
        )
    }

    // ---------------- 交互 ----------------

    /** 点击打卡按钮：BOOLEAN 一键完成，量化类型弹出输入。 */
    fun onCheckInClick(task: DailyTaskEntity) {
        viewModelScope.launch {
            if (task.status.isEngaged) {
                // 已完成的任务：长按撤销，点击则打开编辑
                _checkInTarget.value = task
                return@launch
            }
            if (task.targetType.isQuantified) {
                _checkInTarget.value = task
            } else {
                doCheckIn(task.id, 1)
            }
        }
    }

    /** 长按：开始专注。这里先走打卡弹窗占位，专注计时在 Focus 模块接上。 */
    fun onTaskLongPress(task: DailyTaskEntity) {
        _checkInTarget.value = task
    }

    fun submitCheckIn(taskId: String, value: Int, note: String? = null, photo: String? = null) {
        doCheckIn(taskId, value, note, photo)
    }

    fun revokeCheckIn(taskId: String) {
        viewModelScope.launch {
            checkIn.revoke(taskId, clock.value)
            _checkInTarget.value = null
        }
    }

    private fun doCheckIn(taskId: String, value: Int, note: String? = null, photo: String? = null) {
        viewModelScope.launch {
            val result = checkIn(taskId, value, clock.value, note = note, photo = photo)
            _checkInTarget.value = null
            when (result) {
                is CheckInResult.Success -> {
                    _events.value = TodayEvent.CheckInSuccess(result)
                }

                is CheckInResult.MakeupRejected -> {
                    _events.value = TodayEvent.MakeupRejected(result)
                }

                CheckInResult.TaskNotFound -> Unit
            }
        }
    }

    fun saveReflection(mood: Mood?, text: String) {
        viewModelScope.launch {
            taskRepository.saveReflection(clock.value.today(), mood, text)
        }
    }

    fun dismissCheckIn() {
        _checkInTarget.value = null
    }

    fun consumeEvent() {
        _events.value = null
    }

    // ---------------- 墨墨背单词 ----------------

    /** 监听墨墨开关：打开时拉一次今日进度。 */
    private fun observeMaimemo() {
        viewModelScope.launch {
            prefsRepository.preferences.collect { p ->
                _state.update { it.copy(maimemoEnabled = p.maimemoEnabled) }
                if (p.maimemoEnabled) refreshMaimemo()
            }
        }
    }

    /** 拉取墨墨今日进度（只读，不写任务）。 */
    fun refreshMaimemo() {
        viewModelScope.launch {
            if (!wordSource.isConfigured()) return@launch
            try {
                val p = wordSync.peekProgress()
                _state.update { it.copy(maimemoProgress = p, maimemoMessage = null) }
            } catch (e: Exception) {
                _state.update { it.copy(maimemoMessage = e.message ?: e::class.java.simpleName) }
            }
        }
    }

    /** 一键把墨墨进度同步进今日单词任务。 */
    fun syncWords() {
        viewModelScope.launch {
            _state.update { it.copy(maimemoSyncing = true) }
            try {
                val result = wordSync(clock.value)
                _state.update {
                    it.copy(
                        maimemoSyncing = false,
                        maimemoProgress = result.progress,
                        maimemoMessage = "已同步 ${result.updatedTasks} 个单词任务，+${result.pointsGained} 分",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(maimemoSyncing = false, maimemoMessage = e.message) }
            }
        }
    }
}

/** 一次性 UI 事件。 */
sealed interface TodayEvent {
    data class CheckInSuccess(val result: CheckInResult.Success) : TodayEvent
    data class MakeupRejected(val result: CheckInResult.MakeupRejected) : TodayEvent

    /** 启动补结算时有承诺到期。一次弹一条，用户确认后再弹下一条。 */
    data class CommitmentSettled(
        val outcomes: List<com.example.lixing.domain.settle.CommitmentOutcome>,
    ) : TodayEvent
}
