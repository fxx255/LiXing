package com.example.lixing.ui.screen.english

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.domain.english.EnglishEntryType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EnglishEditorState(
    val existing: EnglishEntryEntity? = null,
    val initialType: EnglishEntryType = existing?.type ?: EnglishEntryType.WORD,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EnglishNotebookViewModel @Inject constructor(
    private val repository: EnglishEntryRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow<EnglishEntryType?>(null)
    val filter: StateFlow<EnglishEntryType?> = _filter.asStateFlow()

    private val _editor = MutableStateFlow<EnglishEditorState?>(null)
    val editor: StateFlow<EnglishEditorState?> = _editor.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val entries: StateFlow<List<EnglishEntryEntity>> = combine(_query, _filter) { query, type ->
        query to type
    }.flatMapLatest { (query, type) ->
        repository.observe(query, type)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun selectFilter(value: EnglishEntryType?) {
        _filter.value = value
    }

    fun startAdding() {
        _editor.value = EnglishEditorState(initialType = _filter.value ?: EnglishEntryType.WORD)
    }

    fun startEditing(entry: EnglishEntryEntity) {
        _editor.value = EnglishEditorState(existing = entry)
    }

    fun dismissEditor() {
        _editor.value = null
    }

    fun save(type: EnglishEntryType, content: String, meaning: String) {
        val target = _editor.value ?: return
        viewModelScope.launch {
            runCatching { repository.save(target.existing, type, content, meaning) }
                .onSuccess {
                    _editor.value = null
                    _message.value = if (target.existing == null) "已加入英语积累" else "修改已保存"
                }
                .onFailure { _message.value = it.message ?: "保存失败，请重试" }
        }
    }

    fun delete(entry: EnglishEntryEntity) {
        viewModelScope.launch {
            runCatching { repository.delete(entry) }
                .onSuccess { _message.value = "已删除" }
                .onFailure { _message.value = it.message ?: "删除失败，请重试" }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
