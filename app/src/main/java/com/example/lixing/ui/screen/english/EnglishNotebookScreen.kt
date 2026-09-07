package com.example.lixing.ui.screen.english

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.ui.theme.LiXingRadius
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val UPDATED_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnglishNotebookScreen(
    onBack: () -> Unit,
    viewModel: EnglishNotebookViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<EnglishEntryEntity?>(null) }

    message?.let { text ->
        LaunchedEffect(text) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    editor?.let { state ->
        EnglishEntryEditorDialog(
            state = state,
            onDismiss = viewModel::dismissEditor,
            onSave = viewModel::save,
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条积累？") },
            text = { Text("“${entry.content}”及其释义会从本机和之后生成的新备份中删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(entry)
                        pendingDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("英语积累") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::startAdding,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("积累一条") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = LiXingRadius.Card,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("把遇见的好词好句留下来", fontWeight = FontWeight.Bold)
                        Text(
                            "记录英文原文和你自己的释义。内容只保存在本机，也会进入你主动创建的版本备份。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::updateQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索英文或释义") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.updateQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                            }
                        }
                    } else null,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TypeFilterChip("全部", filter == null) { viewModel.selectFilter(null) }
                    EnglishEntryType.entries.forEach { type ->
                        TypeFilterChip(type.label, filter == type) { viewModel.selectFilter(type) }
                    }
                }
            }

            if (entries.isEmpty()) {
                item {
                    EmptyEnglishEntries(hasFilter = query.isNotEmpty() || filter != null)
                }
            } else {
                item {
                    Text(
                        "找到 ${entries.size} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(entries, key = { it.id }) { entry ->
                    EnglishEntryCard(
                        entry = entry,
                        onEdit = { viewModel.startEditing(entry) },
                        onDelete = { pendingDelete = entry },
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun EmptyEnglishEntries(hasFilter: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("📖", style = MaterialTheme.typography.displaySmall)
        Text(
            if (hasFilter) "没有找到匹配的内容" else "还没有英语积累",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (hasFilter) "换个关键词或查看全部类型试试。" else "点右下角“积累一条”，记下第一个单词、短语或句子。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EnglishEntryCard(
    entry: EnglishEntryEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = LiXingRadius.Card,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = LiXingRadius.Pill) {
                    Text(
                        entry.type.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
            }

            SelectionContainer {
                Text(
                    entry.content,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            SelectionContainer {
                Text(entry.meaning, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                "更新于 ${entry.updatedAt.atZone(ZoneId.systemDefault()).format(UPDATED_TIME_FORMAT)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EnglishEntryEditorDialog(
    state: EnglishEditorState,
    onDismiss: () -> Unit,
    onSave: (EnglishEntryType, String, String) -> Unit,
) {
    val existing = state.existing
    var type by remember(existing?.id, state.initialType) { mutableStateOf(state.initialType) }
    var content by remember(existing?.id) { mutableStateOf(existing?.content.orEmpty()) }
    var meaning by remember(existing?.id) { mutableStateOf(existing?.meaning.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "积累英语" else "编辑积累") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("内容类型", style = MaterialTheme.typography.labelLarge)
                EnglishEntryType.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = type == option,
                                onClick = { type = option },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.label) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(option.hint, style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(EnglishEntryRepository.MAX_CONTENT_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(type.contentLabel) },
                    placeholder = { Text(type.placeholder) },
                    minLines = if (type == EnglishEntryType.SENTENCE) 2 else 1,
                    maxLines = 5,
                    supportingText = { Text("${content.length}/${EnglishEntryRepository.MAX_CONTENT_LENGTH}") },
                )
                OutlinedTextField(
                    value = meaning,
                    onValueChange = { meaning = it.take(EnglishEntryRepository.MAX_MEANING_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("释义") },
                    placeholder = { Text("写下你理解的含义") },
                    minLines = 2,
                    maxLines = 7,
                    supportingText = { Text("${meaning.length}/${EnglishEntryRepository.MAX_MEANING_LENGTH}") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(type, content, meaning) },
                enabled = content.isNotBlank() && meaning.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val EnglishEntryType.label: String
    get() = when (this) {
        EnglishEntryType.WORD -> "单词"
        EnglishEntryType.PHRASE -> "短语"
        EnglishEntryType.SENTENCE -> "句子"
    }

private val EnglishEntryType.hint: String
    get() = when (this) {
        EnglishEntryType.WORD -> "一个词"
        EnglishEntryType.PHRASE -> "固定搭配或词组"
        EnglishEntryType.SENTENCE -> "例句或好句"
    }

private val EnglishEntryType.contentLabel: String
    get() = when (this) {
        EnglishEntryType.WORD -> "英文单词"
        EnglishEntryType.PHRASE -> "英文短语"
        EnglishEntryType.SENTENCE -> "英文句子"
    }

private val EnglishEntryType.placeholder: String
    get() = when (this) {
        EnglishEntryType.WORD -> "例如：resilient"
        EnglishEntryType.PHRASE -> "例如：in the long run"
        EnglishEntryType.SENTENCE -> "例如：Small steps add up over time."
    }
