package com.example.lixing.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.dao.SlotCompletion
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.data.repository.PlanningRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.domain.study.SubjectStudyStats
import com.example.lixing.domain.planning.GoalContentStats
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDate
import javax.inject.Inject

data class StatsUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.MIN,
    /** 最近 365 天的每日记录，用于热力图。 */
    val dayRecords: List<DayRecordEntity> = emptyList(),
    /** 最近 30 天完成率，用于趋势。 */
    val recentRecords: List<DayRecordEntity> = emptyList(),
    val subjectStats: List<SubjectStudyStats> = emptyList(),
    val goalStats: List<GoalContentStats> = emptyList(),
    val slotCompletion: List<SlotCompletion> = emptyList(),
    val totalFocusMinutes: Int = 0,
    val avgCompletionRate: Float = 0f,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val planningRepository: PlanningRepository,
    private val planRepository: PlanRepository,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            planRepository.activePlan.flatMapLatest { plan ->
                if (plan == null) flowOf(emptyList()) else planningRepository.observeGoalContentStats(plan.id)
            }.collect { goals -> _state.update { it.copy(goalStats = goals) } }
        }
        viewModelScope.launch {
            val today = StudyClock(dayStart = prefs.current().dayStartTime).today()
            val yearAgo = today.minusDays(365)
            val monthAgo = today.minusDays(30)

            combine(
                taskRepository.observeDayRecordsBetween(yearAgo, today),
                taskRepository.observeSubjectStudyStats(monthAgo, today),
            ) { records, subjectStats -> records to subjectStats }.collect { (records, subjectStats) ->
                val recent = records.filter { it.date >= monthAgo }
                val slotCompletion = taskRepository.getSlotCompletion(monthAgo, today)
                val focusMinutes = taskRepository.getTotalFocusMinutes(monthAgo, today)
                val counted = recent.filter { !it.isDayOff && it.totalTasks > 0 }
                val avgRate = if (counted.isEmpty()) 0f
                else counted.map { it.completionRate }.average().toFloat()

                _state.update {
                    it.copy(
                        isLoading = false,
                        today = today,
                        dayRecords = records,
                        recentRecords = recent,
                        subjectStats = subjectStats,
                        slotCompletion = slotCompletion,
                        totalFocusMinutes = focusMinutes,
                        avgCompletionRate = avgRate,
                    )
                }
            }
        }
    }

    /** 热力图数据：date -> 完成率 0f~1f。 */
    fun heatmapValue(date: LocalDate): Float {
        val record = _state.value.dayRecords.find { it.date == date } ?: return 0f
        return if (record.isDayOff) 0f else record.completionRate
    }
}
