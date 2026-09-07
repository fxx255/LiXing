package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.example.lixing.data.local.entity.MealRecordEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface MealRecordDao {
    @Query("SELECT * FROM meal_record WHERE date = :date ORDER BY meal_type")
    fun observeDate(date: LocalDate): Flow<List<MealRecordEntity>>

    @Query("SELECT * FROM meal_record WHERE date BETWEEN :from AND :to ORDER BY date, meal_type")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<MealRecordEntity>>

    @Query("SELECT * FROM meal_record WHERE date = :date AND meal_type = :mealType LIMIT 1")
    suspend fun get(date: LocalDate, mealType: String): MealRecordEntity?

    @Upsert
    suspend fun upsert(record: MealRecordEntity): Long

    @Delete
    suspend fun delete(record: MealRecordEntity)

    @Query("DELETE FROM meal_record")
    suspend fun deleteAll()
}
