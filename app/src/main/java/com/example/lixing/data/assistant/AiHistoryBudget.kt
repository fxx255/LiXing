package com.example.lixing.data.assistant

enum class AiHistoryBudget(val maxMessages: Int, val maxChars: Int, val label: String) {
    COMPACT(12, 12_000, "1.2 万字符 / 12 条"),
    STANDARD(HISTORY_MAX_MESSAGES, HISTORY_MAX_CHARS, "2.4 万字符 / 24 条（默认）"),
    EXTENDED(48, 48_000, "4.8 万字符 / 48 条"),
    LARGE(96, 96_000, "9.6 万字符 / 96 条"),
    MAXIMUM(192, 192_000, "19.2 万字符 / 192 条"),
}
