package com.example.lixing.ui.screen.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** A selectable text surface opened by long-pressing an assistant answer. */
@Composable
internal fun AssistantAnswerCopyDialog(content: String, imageCount: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val answerText = remember(content, imageCount) {
        splitFigureSegments(content, imageCount)
            .filter { it.figureIndex == null }
            .joinToString("\n\n") { it.text }
            .trim()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择并复制回答") },
        text = {
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(answerText) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("助手回答", answerText))
                onDismiss()
            }) { Text("复制全文") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
