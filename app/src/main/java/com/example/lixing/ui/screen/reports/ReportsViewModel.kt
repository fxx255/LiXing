package com.example.lixing.ui.screen.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.repository.GamificationRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.report.PeriodReport
import com.example.lixing.domain.report.ReportGenerator
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import javax.inject.Inject

data class ReportsUiState(
    val isLoading: Boolean = true,
    val week: PeriodReport? = null,
    val lastWeek: PeriodReport? = null,
    val month: PeriodReport? = null,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val gamification: GamificationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportsUiState())
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    private val clock = StudyClock()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val today = clock.today()
            val thisMonday = today.with(DayOfWeek.MONDAY)

            // 本周
            val weekRecords = taskRepository.getDayRecordsBetween(thisMonday, today)
            val weekSlots = taskRepository.getSlotCompletion(thisMonday, today)
            val weekSubjects = taskRepository.getSubjectMinutes(thisMonday, today)

            // 上周（环比）
            val lastMonday = thisMonday.minusWeeks(1)
            val lastWeekRecords = taskRepository.getDayRecordsBetween(lastMonday, thisMonday.minusDays(1))
            val lastWeekSlots = taskRepository.getSlotCompletion(lastMonday, thisMonday.minusDays(1))
            val lastWeekSubjects = taskRepository.getSubjectMinutes(lastMonday, thisMonday.minusDays(1))

            // 本月
            val monthStart = today.withDayOfMonth(1)
            val monthRecords = taskRepository.getDayRecordsBetween(monthStart, today)
            val monthSlots = taskRepository.getSlotCompletion(monthStart, today)
            val monthSubjects = taskRepository.getSubjectMinutes(monthStart, today)

            val points = gamification.getPointsBetween(thisMonday, today)

            _state.update {
                it.copy(
                    isLoading = false,
                    week = ReportGenerator.generate("本周", weekRecords, weekSlots, weekSubjects, lastWeekRecords, points),
                    lastWeek = ReportGenerator.generate("上周", lastWeekRecords, lastWeekSlots, lastWeekSubjects),
                    month = ReportGenerator.generate("本月", monthRecords, monthSlots, monthSubjects),
                )
            }
        }
    }
}
