package com.example.lixing.ui.screen.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.settle.DayStats
import com.example.lixing.domain.settle.DayStatsCalculator
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 一天的历史条目：汇总 + 按时段分组的任务。 */
data class HistoryDay(
    val date: LocalDate,
    val record: DayRecordEntity?,
    val stats: DayStats,
    /** 按时段名分组，保持时段先后顺序。 */
    val bySlot: List<Pair<String, List<DailyTaskEntity>>>,
) {
    /** 有详细记录或照片的任务数，用来提示「这天有料可看」。 */
    val detailCount: Int
        get() = bySlot.sumOf { (_, list) ->
            list.count { !it.checkinNote.isNullOrBlank() || !it.checkinPhoto.isNullOrBlank() }
        }
}

data class HistoryUiState(
    val isLoading: Boolean = true,
    val days: List<HistoryDay> = emptyList(),
    /** 展开的日期，null = 全部折叠。 */
    val expanded: LocalDate? = null,
    /** 已加载的天数范围（往前推多少天）。 */
    val loadedDays: Int = 30,
)

/**
 * 历史回顾。
 *
 * 只读展示过去每天的打卡详情（含文字记录与照片），
 * 让「详细记录」真正有用——可以回看哪天做了什么题、错在哪。
 * 换计划不影响这里：历史任务带快照字段，旧计划的记录照原样显示。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val prefsRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        load(30)
    }

    /** 加载最近 days 天（含今天）的历史，按日期倒序。 */
    fun load(days: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, loadedDays = days) }

            val clock = StudyClock(dayStart = prefsRepository.current().dayStartTime)
            val today = clock.today()
            val from = today.minusDays((days - 1).toLong())

            val tasks = taskRepository.getTasksBetween(from, today)
            val records = taskRepository.getDayRecordsBetween(from, today).associateBy { it.date }

            val byDate = tasks.groupBy { it.date }
            val result = byDate.keys.sortedDescending().map { date ->
                val dayTasks = byDate[date].orEmpty()
                // 按时段顺序分组
                val grouped = dayTasks
                    .sortedWith(compareBy({ it.slotSortOrder }, { it.slotStart }, { it.sortOrder }))
                    .groupBy { it.slotName }
                    .map { (slot, list) -> slot to list }

                HistoryDay(
                    date = date,
                    record = records[date],
                    stats = DayStatsCalculator.calculate(dayTasks),
                    bySlot = grouped,
                )
            }

            _state.update { it.copy(isLoading = false, days = result) }
        }
    }

    fun toggleExpand(date: LocalDate) {
        _state.update { it.copy(expanded = if (it.expanded == date) null else date) }
    }

    /** 再往前加载 30 天。 */
    fun loadMore() = load(_state.value.loadedDays + 30)
}
