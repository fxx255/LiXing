package com.example.lixing.ui.screen.plan.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.repository.PlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlanManageUiState(
    val hasPlan: Boolean = false,
    val phases: List<PhaseEntity> = emptyList(),
    val subjects: List<SubjectEntity> = emptyList(),
    val slots: List<TimeSlotEntity> = emptyList(),
    val templates: List<TaskTemplateEntity> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanManageViewModel @Inject constructor(
    private val planRepository: PlanRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlanManageUiState())
    val state: StateFlow<PlanManageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            planRepository.activePlan.collect { plan ->
                _state.update { it.copy(hasPlan = plan != null) }
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
                .collect { _state.update { s -> s.copy(templates = it) } }
        }
    }
}
