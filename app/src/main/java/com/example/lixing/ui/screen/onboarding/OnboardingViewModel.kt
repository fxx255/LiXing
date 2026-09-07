package com.example.lixing.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.GamificationRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.seed.KaoyanSeed
import com.example.lixing.domain.seed.SeedResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class OnboardingUiState(
    val step: Int = 0,
    val importTemplate: Boolean = true,
    val targetDate: LocalDate = LocalDate.now(),
    val isWorking: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefsRepository: UserPreferencesRepository,
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val gamification: GamificationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OnboardingUiState(targetDate = SeedResolver.defaultTargetDate(LocalDate.now())),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun setStep(step: Int) = _state.update { it.copy(step = step) }

    fun setImportTemplate(import: Boolean) = _state.update { it.copy(importTemplate = import) }

    fun setTargetDate(date: LocalDate) = _state.update { it.copy(targetDate = date) }

    /** 完成引导：按需导入模板、物化今天、标记引导完成。 */
    fun finish() {
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            try {
                val s = _state.value
                if (s.importTemplate) {
                    planRepository.importSeedPlan(
                        seed = KaoyanSeed.plan,
                        startDate = LocalDate.now(),
                        targetDate = s.targetDate,
                        replaceExisting = true,
                    )
                    taskRepository.materializeDay(LocalDate.now())
                }
                gamification.ensureInitialized(LocalDate.now())
                prefsRepository.setOnboardingDone(true)
                _state.update { it.copy(isWorking = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isWorking = false) }
            }
        }
    }
}
