package com.example.lixing.ui.screen.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.repository.GamificationRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.seed.KaoyanSeed
import com.example.lixing.domain.seed.SeedResolver
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class PlanUiState(
    val isLoading: Boolean = false,
    val hasPlan: Boolean = false,
    val planName: String? = null,
    val startDate: LocalDate? = null,
    val targetDate: LocalDate? = null,
    val importMessage: String? = null,
    val isImporting: Boolean = false,
    // ---- 宏观总览 ----
    val phases: List<com.example.lixing.data.local.entity.PhaseEntity> = emptyList(),
    val subjects: List<com.example.lixing.data.local.entity.SubjectEntity> = emptyList(),
    val slots: List<com.example.lixing.data.local.entity.TimeSlotEntity> = emptyList(),
    val templateCount: Int = 0,
    val currentPhaseName: String? = null,
    val daysToTarget: Int = 0,
    /** 当前「学习日」（按切日点算），用于阶段进度与倒计时，避免与首页口径不一致。 */
    val today: LocalDate = LocalDate.now(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val gamification: GamificationRepository,
    private val prefsRepository: com.example.lixing.data.prefs.UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    /**
     * 「今天」必须走 StudyClock（带切日点），不能用 LocalDate.now()。
     * 否则凌晨 00:00–04:00 期间导入会把计划写到日历新的一天，
     * 而首页仍在看前一天，表现为「导入后今日无任务」。
     */
    private suspend fun clock(): StudyClock =
        StudyClock(dayStart = prefsRepository.current().dayStartTime)

    init {
        // 先把学习日取出来放进 state，后续派生值都用它，避免与首页口径不一致
        viewModelScope.launch {
            _state.update { it.copy(today = clock().today()) }
        }

        viewModelScope.launch {
            planRepository.activePlan.collect { plan ->
                val today = clock().today()
                _state.update {
                    it.copy(
                        hasPlan = plan != null,
                        planName = plan?.name,
                        startDate = plan?.startDate,
                        targetDate = plan?.targetDate,
                        today = today,
                        daysToTarget = plan?.targetDate?.let { t ->
                            java.time.temporal.ChronoUnit.DAYS.between(today, t).toInt()
                        } ?: 0,
                    )
                }
            }
        }

        viewModelScope.launch {
            planRepository.activePlan.filterNotNull()
                .flatMapLatest { planRepository.observePhases(it.id) }
                .collect { _state.update { s -> s.copy(phases = it) } }
        }
        viewModelScope.launch {
            planRepository.activePlan.filterNotNull()
                .flatMapLatest { planRepository.observeSubjects(it.id) }
                .collect { _state.update { s -> s.copy(subjects = it) } }
        }
        viewModelScope.launch {
            planRepository.activePlan.filterNotNull()
                .flatMapLatest { planRepository.observeTimeSlots(it.id) }
                .collect { _state.update { s -> s.copy(slots = it) } }
        }
        viewModelScope.launch {
            planRepository.activePlan.filterNotNull()
                .flatMapLatest { planRepository.observeTemplates(it.id) }
                .collect { _state.update { s -> s.copy(templateCount = it.size) } }
        }
        viewModelScope.launch {
            val today = clock().today()
            planRepository.activePlan.filterNotNull()
                .flatMapLatest { planRepository.observeCurrentPhase(today) }
                .collect { _state.update { s -> s.copy(currentPhaseName = it?.name) } }
        }
    }

    /** 导入内置的考研全程计划。 */
    fun importKaoyanPlan() {
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, isLoading = true) }
            try {
                // 新计划从「日历今天」开始生效，而不是学习日。
                // 这样凌晨 0–4 点导入时：旧计划仍覆盖到 4 点（首页看前一天，旧记录照常打卡），
                // 过了切日点自然切到新计划。历史记录不受影响。
                val startDate = LocalDate.now()
                val targetDate = SeedResolver.defaultTargetDate(startDate)

                val result = planRepository.importSeedPlan(
                    seed = KaoyanSeed.plan,
                    startDate = startDate,
                    targetDate = targetDate,
                    replaceExisting = true,
                )

                // 为新计划生效日物化任务
                taskRepository.materializeDay(startDate)
                gamification.ensureInitialized(startDate)

                _state.update {
                    it.copy(
                        isImporting = false,
                        importMessage = "已导入 ${result.templateCount} 个任务模板，" +
                            "${result.phaseCount} 个阶段，${result.subjectCount} 个科目。" +
                            (if (result.skippedCount > 0) "（跳过 ${result.skippedCount} 条不适用的）" else ""),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isImporting = false, importMessage = "导入失败：${e.message}") }
            }
        }
    }

    fun clearImportMessage() {
        _state.update { it.copy(importMessage = null) }
    }
}
