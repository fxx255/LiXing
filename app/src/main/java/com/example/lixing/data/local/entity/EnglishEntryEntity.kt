package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.lixing.domain.english.EnglishEntryType
import java.util.UUID
import java.time.Instant

/** 用户主动记录的一条英语积累。全部内容只保存在本机和用户主动生成的版本备份中。 */
@Entity(
    tableName = "english_entry",
    indices = [
        Index("type"),
        Index("updated_at"),
    ],
)
data class EnglishEntryEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val type: EnglishEntryType,
    /** 单词、短语或完整句子的英文原文。 */
    val content: String,
    /** 用户填写的释义，不限制必须使用中文。 */
    val meaning: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = createdAt,
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
