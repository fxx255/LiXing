package com.example.lixing.ui.screen.english

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.domain.english.DueCounts
import com.example.lixing.domain.english.ReviewGrade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** 一轮背诵的实时状态。 */
data class ReviewSessionState(
    val loading: Boolean = true,
    /** 本轮队列（按顺序）。 */
    val queue: List<EnglishEntryEntity> = emptyList(),
    /** 当前卡片下标。 */
    val index: Int = 0,
    /** 是否已翻面（显示释义 + 三档按钮）。 */
    val revealed: Boolean = false,
    /** 本轮各档位计数，用于结束页汇总。 */
    val good: Int = 0,
    val hard: Int = 0,
    val again: Int = 0,
    /** 本轮开始时统计的待办量，用于顶部进度说明。 */
    val counts: DueCounts = DueCounts(),
) {
    val current: EnglishEntryEntity? get() = queue.getOrNull(index)
    val remaining: Int get() = (queue.size - index).coerceAtLeast(0)
    val finished: Boolean get() = !loading && index >= queue.size
    val total: Int get() = queue.size
}

@HiltViewModel
class EnglishReviewViewModel @Inject constructor(
    private val repository: EnglishEntryRepository,
    private val prefsRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewSessionState())
    val state: StateFlow<ReviewSessionState> = _state.asStateFlow()

    private var startedAt: Instant = Instant.now()

    init {
        start()
    }

    /** 开始（或重新开始）一轮背诵。 */
    fun start() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            startedAt = Instant.now()
            val limit = prefsRepository.current().englishDailyNewLimit
            val counts = repository.dueCounts(now = startedAt, dailyNewLimit = limit)
            val queue = repository.reviewQueue(now = startedAt, dailyNewLimit = limit)
            _state.value = ReviewSessionState(
                loading = false,
                queue = queue,
                index = 0,
                counts = counts,
            )
        }
    }

    /** 翻面看释义。 */
    fun reveal() {
        _state.update { it.copy(revealed = true) }
    }

    /**
     * 打分并进入下一张。
     *
     * 「忘记」的卡片 10 分钟后到期，本轮不再重复出现（避免同一张卡死循环），
     * 但当天再开一轮就会再遇到它。
     */
    fun grade(grade: ReviewGrade) {
        val current = _state.value.current ?: return
        viewModelScope.launch {
            repository.grade(current, grade, Instant.now())
            _state.update { s ->
                s.copy(
                    index = s.index + 1,
                    revealed = false,
                    good = s.good + if (grade == ReviewGrade.GOOD) 1 else 0,
                    hard = s.hard + if (grade == ReviewGrade.HARD) 1 else 0,
                    again = s.again + if (grade == ReviewGrade.AGAIN) 1 else 0,
                )
            }
        }
    }

    /** 今日上限：给用户看「今天还能新学多少」。 */
    suspend fun dailyNewLimit(): Int = prefsRepository.current().englishDailyNewLimit
}
