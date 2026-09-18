package com.example.lixing.ui.screen.english

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.dictionary.DictionaryRepository
import com.example.lixing.data.dictionary.DictionaryWord
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.local.entity.EnglishReviewLogEntity
import com.example.lixing.data.prefs.UserPreferences
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.data.repository.memory
import com.example.lixing.domain.english.DueCounts
import com.example.lixing.domain.english.ReviewGrade
import com.example.lixing.domain.english.FsrsScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

data class ReviewSessionState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val current: EnglishEntryEntity? = null,
    val revealed: Boolean = false,
    val word: DictionaryWord? = null,
    val dictionaryLoading: Boolean = false,
    val dictionaryError: String? = null,
    val history: List<EnglishReviewLogEntity> = emptyList(),
    val counts: DueCounts = DueCounts(),
    val completed: Int = 0,
    val good: Int = 0, val hard: Int = 0, val again: Int = 0,
    val nextLearningAt: Long? = null,
    val now: Long = System.currentTimeMillis(),
    val error: String? = null,
    val canUndo: Boolean = false,
    val preferences: UserPreferences = UserPreferences(),
)

@HiltViewModel
class EnglishReviewViewModel @Inject constructor(
    private val repository: EnglishEntryRepository,
    private val dictionary: DictionaryRepository,
    private val prefsRepository: UserPreferencesRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewSessionState())
    val state = _state.asStateFlow()
    private val feedbackChannel = Channel<Unit>(Channel.BUFFERED)
    val feedback = feedbackChannel.receiveAsFlow()
    private val sessionId: String = savedState.get<String>("sessionId") ?: UUID.randomUUID().toString().also { savedState["sessionId"] = it }
    private var eventId = UUID.randomUUID().toString()
    private var undoLog: EnglishReviewLogEntity? = null
    private var wordJob: Job? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { prefsRepository.preferences.collect { p ->
            _state.update { it.copy(preferences = p) }
        } }
        start()
        viewModelScope.launch {
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                _state.update { it.copy(now = now) }
                val s = _state.value
                if (!s.loading && !s.saving && s.current == null && s.error == null &&
                    s.preferences.englishReviewEnabled && s.nextLearningAt?.let { it <= now } == true) start()
            }
        }
    }

    fun start() {
        if (_state.value.saving || loadJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { try { loadNext() } catch (e: Exception) { handleError(e) } }
    }

    private suspend fun loadNext(preferredId: String? = null) {
        wordJob?.cancel()
        val prefs = prefsRepository.current()
        val now = Instant.now()
        val counts = repository.dueCounts(now, prefs.englishDailyNewLimit, prefs.dayStartTime)
        val queue = if (prefs.englishReviewEnabled) repository.reviewQueue(now, prefs.englishDailyNewLimit, prefs.dayStartTime) else emptyList()
        val entry = queue.firstOrNull { it.id == preferredId } ?: queue.firstOrNull()
        val history = entry?.let { repository.history(it.id) }.orEmpty()
        val next = repository.nextLearningDue(now.toEpochMilli())
        eventId = UUID.randomUUID().toString()
        _state.update { it.copy(loading = false, saving = false, current = entry, revealed = false,
            word = null, dictionaryLoading = entry != null, dictionaryError = null, history = history,
            counts = counts, nextLearningAt = next, error = null, preferences = prefs,
            now = now.toEpochMilli(), canUndo = undoLog != null) }
        if (entry != null) wordJob = viewModelScope.launch {
            try {
                val word = dictionary.lookup(entry.content)
                _state.update { if (it.current?.id == entry.id) it.copy(word = word, dictionaryLoading = false) else it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { if (it.current?.id == entry.id) it.copy(dictionaryLoading = false, dictionaryError = "本地词典暂时无法读取，可继续使用自己的释义") else it }
            }
        }
    }

    fun reveal() { if (!_state.value.saving) _state.update { it.copy(revealed = true) } }

    fun grade(grade: ReviewGrade) {
        val s = _state.value
        val current = s.current ?: return
        if (s.saving || s.loading || !s.revealed || !s.preferences.englishReviewEnabled) return
        _state.update { it.copy(saving = true, error = null) }
        val token = eventId
        viewModelScope.launch {
            try {
                undoLog = repository.grade(current, grade, token, sessionId)
                _state.update { it.copy(completed = it.completed + 1,
                    good = it.good + if (grade == ReviewGrade.GOOD) 1 else 0,
                    hard = it.hard + if (grade == ReviewGrade.HARD) 1 else 0,
                    again = it.again + if (grade == ReviewGrade.AGAIN) 1 else 0,
                    canUndo = true, current = null, revealed = false) }
                if (s.preferences.englishHapticsEnabled) feedbackChannel.send(Unit)
                loadNext()
            } catch (e: Exception) { handleError(e) }
        }
    }

    fun undo() {
        val log = undoLog ?: return
        if (_state.value.saving || _state.value.loading) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                if (repository.undo(log.id)) _state.update { it.copy(completed = (it.completed - 1).coerceAtLeast(0),
                    good = it.good - if (log.grade == "GOOD") 1 else 0,
                    hard = it.hard - if (log.grade == "HARD") 1 else 0,
                    again = it.again - if (log.grade == "AGAIN") 1 else 0) }
                undoLog = null
                loadNext(log.entryId)
            } catch (e: Exception) { handleError(e) }
        }
    }

    fun supplement() {
        val s = _state.value
        val entry = s.current ?: return
        if (!s.preferences.englishOnlineDictionary || s.dictionaryLoading) return
        _state.update { it.copy(dictionaryLoading = true, dictionaryError = null) }
        wordJob = viewModelScope.launch {
            try {
                val word = dictionary.supplement(entry.content)
                _state.update { if (it.current?.id == entry.id) it.copy(word = word, dictionaryLoading = false,
                    dictionaryError = if (word == null) "免费词典未收录此词，可使用自己的释义" else null) else it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { if (it.current?.id == entry.id) it.copy(dictionaryLoading = false, dictionaryError = e.message ?: "联网补充失败") else it }
            }
        }
    }

    fun moveButton(x: Float, y: Float) {
        _state.update { it.copy(preferences = it.preferences.copy(englishButtonX = x, englishButtonY = y)) }
        viewModelScope.launch { prefsRepository.setEnglishButtonPosition(x, y) }
    }

    private fun handleError(e: Exception) {
        if (e is CancellationException) throw e
        _state.update { it.copy(loading = false, saving = false, error = e.message ?: "操作失败，请重试") }
    }
}
