package com.example.lixing.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.dao.SlotCompletion
import com.example.lixing.data.local.dao.SubjectMinutes
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class StatsUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.MIN,
    /** 最近 365 天的每日记录，用于热力图。 */
    val dayRecords: List<DayRecordEntity> = emptyList(),
    /** 最近 30 天完成率，用于趋势。 */
    val recentRecords: List<DayRecordEntity> = emptyList(),
    val subjectMinutes: List<SubjectMinutes> = emptyList(),
    val slotCompletion: List<SlotCompletion> = emptyList(),
    val totalFocusMinutes: Int = 0,
    val avgCompletionRate: Float = 0f,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private val clock = StudyClock()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val today = clock.today()
            val yearAgo = today.minusDays(365)
            val monthAgo = today.minusDays(30)

            taskRepository.observeDayRecordsBetween(yearAgo, today).collect { records ->
                val recent = records.filter { it.date >= monthAgo }
                val subjectMinutes = taskRepository.getSubjectMinutes(monthAgo, today)
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
                        subjectMinutes = subjectMinutes,
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
