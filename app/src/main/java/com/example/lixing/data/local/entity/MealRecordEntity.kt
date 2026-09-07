package com.example.lixing.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID
import java.time.LocalDate

/** 一次用餐记录。照片和分析结果只保存在本机。 */
@Entity(
    tableName = "meal_record",
    indices = [
        Index(value = ["date", "meal_type"], unique = true),
        Index("date"),
    ],
)
data class MealRecordEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    @ColumnInfo(name = "meal_type")
    val mealType: String,
    @ColumnInfo(name = "photo_path")
    val photoPath: String,
    @ColumnInfo(name = "food_name")
    val foodName: String,
    @ColumnInfo(name = "serving_grams")
    val servingGrams: Int,
    @ColumnInfo(name = "calories_kcal")
    val caloriesKcal: Int,
    @ColumnInfo(name = "protein_grams")
    val proteinGrams: Float,
    @ColumnInfo(name = "carbs_grams")
    val carbsGrams: Float,
    @ColumnInfo(name = "fat_grams")
    val fatGrams: Float,
    @ColumnInfo(name = "fiber_grams")
    val fiberGrams: Float,
    /** 模型原始标签，手动改名后仍保留，便于解释结果。 */
    @ColumnInfo(name = "model_label")
    val modelLabel: String,
    val confidence: Float,
    val advice: String,
    @ColumnInfo(name = "is_manually_edited")
    val isManuallyEdited: Boolean = false,
    @ColumnInfo(name = "analyzed_at")
    val analyzedAt: Instant = Instant.now(),
    /** 多端同步的 Lamport 时钟戳，由同步引擎维护；0 = 从未参与同步。 */
    @ColumnInfo(name = "sync_modified_at", defaultValue = "0")
    val syncModifiedAt: Long = 0,
)
