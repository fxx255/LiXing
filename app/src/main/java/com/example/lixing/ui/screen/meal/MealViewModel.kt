package com.example.lixing.ui.screen.meal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.assistant.AssistantImagePrep
import com.example.lixing.data.local.entity.MealRecordEntity
import com.example.lixing.data.meal.MealVisionCalibrator
import com.example.lixing.data.repository.MealRepository
import com.example.lixing.domain.meal.FoodNutritionEstimator
import com.example.lixing.domain.meal.MealAnalyzer
import com.example.lixing.domain.meal.MealType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import java.time.LocalDate
import javax.inject.Inject

data class MealUiState(
    val date: LocalDate = LocalDate.now(),
    val records: List<MealRecordEntity> = emptyList(),
    val analyzing: MealType? = null,
    val message: String? = null,
) {
    val calories: Int get() = records.sumOf { it.caloriesKcal }
    val protein: Float get() = records.sumOf { it.proteinGrams.toDouble() }.toFloat()
    val carbs: Float get() = records.sumOf { it.carbsGrams.toDouble() }.toFloat()
    val fat: Float get() = records.sumOf { it.fatGrams.toDouble() }.toFloat()
    val fiber: Float get() = records.sumOf { it.fiberGrams.toDouble() }.toFloat()
    val mainMealsLogged: Int get() = records.count { it.mealType != MealType.SNACK.name }
}

@HiltViewModel
class MealViewModel @Inject constructor(
    private val repository: MealRepository,
    private val analyzer: MealAnalyzer,
    private val visionCalibrator: MealVisionCalibrator,
) : ViewModel() {
    private val _state = MutableStateFlow(MealUiState())
    val state: StateFlow<MealUiState> = _state.asStateFlow()
    private var observeJob: Job? = null

    init {
        observeDate(LocalDate.now())
    }

    fun previousDay() = observeDate(_state.value.date.minusDays(1))

    fun nextDay() {
        val next = _state.value.date.plusDays(1)
        if (!next.isAfter(LocalDate.now())) observeDate(next)
    }

    fun analyzePhoto(type: MealType, photoPath: String) {
        viewModelScope.launch {
            _state.update { it.copy(analyzing = type, message = null) }
            val date = _state.value.date
            val existing = repository.existing(date, type.name)
            val visionConfigured = visionCalibrator.isConfigured()
            val vision = if (visionConfigured) {
                val base64 = runCatching {
                    withContext(Dispatchers.Default) { AssistantImagePrep.encodeForVision(photoPath) }
                }.getOrNull()
                base64?.let { runCatching { visionCalibrator.calibrate(it, type) }.getOrNull() }
            } else {
                null
            }
            val result = runCatching { analyzer.analyze(photoPath, type) }
            val analysis = vision ?: result.getOrElse {
                FoodNutritionEstimator.estimate("待确认餐食", type, grams = type.defaultPortionGrams).copy(
                    advice = "图片暂未可靠识别，请点“校正”填写食物名称和大致份量。",
                )
            }
            repository.save(
                MealRecordEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    date = date,
                    mealType = type.name,
                    photoPath = photoPath,
                    foodName = analysis.foodName,
                    servingGrams = analysis.servingGrams,
                    caloriesKcal = analysis.caloriesKcal,
                    proteinGrams = analysis.proteinGrams,
                    carbsGrams = analysis.carbsGrams,
                    fatGrams = analysis.fatGrams,
                    fiberGrams = analysis.fiberGrams,
                    modelLabel = analysis.modelLabel,
                    confidence = analysis.confidence,
                    advice = analysis.advice,
                    analyzedAt = Instant.now(),
                ),
            )
            if (existing != null && existing.photoPath != photoPath) {
                File(existing.photoPath).takeIf { it.isFile }?.delete()
            }
            _state.update {
                it.copy(
                    analyzing = null,
                    message = when {
                        vision != null -> null
                        visionConfigured && result.exceptionOrNull() == null ->
                            "多模态校准失败，已用端侧估算；可点“校正”调整"
                        result.exceptionOrNull() != null ->
                            "端侧识别未完成：${result.exceptionOrNull()?.message ?: "请手动校正"}"
                        else -> null
                    },
                )
            }
        }
    }

    fun update(record: MealRecordEntity, foodName: String, servingGrams: Int) {
        viewModelScope.launch {
            val type = runCatching { MealType.valueOf(record.mealType) }.getOrDefault(MealType.LUNCH)
            val analysis = FoodNutritionEstimator.estimate(
                labelOrName = foodName,
                mealType = type,
                grams = servingGrams,
                modelLabel = record.modelLabel,
                confidence = record.confidence,
            )
            repository.save(
                record.copy(
                    foodName = foodName.ifBlank { analysis.foodName },
                    servingGrams = analysis.servingGrams,
                    caloriesKcal = analysis.caloriesKcal,
                    proteinGrams = analysis.proteinGrams,
                    carbsGrams = analysis.carbsGrams,
                    fatGrams = analysis.fatGrams,
                    fiberGrams = analysis.fiberGrams,
                    advice = analysis.advice,
                    isManuallyEdited = true,
                    analyzedAt = Instant.now(),
                ),
            )
        }
    }

    fun delete(record: MealRecordEntity) {
        viewModelScope.launch { repository.delete(record) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun showMessage(message: String) = _state.update { it.copy(message = message) }

    private fun observeDate(date: LocalDate) {
        observeJob?.cancel()
        _state.update { it.copy(date = date, records = emptyList(), message = null) }
        observeJob = viewModelScope.launch {
            repository.observeDate(date).collect { records ->
                _state.update { state -> state.copy(records = records) }
            }
        }
    }
}
