package com.example.lixing.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Downloadable dictionary content is a rebuildable local cache, not personal review data. */
@Entity(tableName = "dictionary_cache")
data class DictionaryCacheEntity(@PrimaryKey val word: String, val payload: String, val fetchedAt: Long)
