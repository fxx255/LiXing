package com.example.lixing.domain.english

/** 英语积累内容的类型。使用稳定的 name 存入数据库，展示文案只留在 UI 层。 */
enum class EnglishEntryType {
    WORD,
    PHRASE,
    SENTENCE,
}
