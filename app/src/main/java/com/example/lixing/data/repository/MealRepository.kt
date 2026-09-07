package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.MealRecordDao
import com.example.lixing.data.local.entity.MealRecordEntity
import com.example.lixing.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MealRepository @Inject constructor(
    private val dao: MealRecordDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observeDate(date: LocalDate): Flow<List<MealRecordEntity>> = dao.observeDate(date)

    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<MealRecordEntity>> =
        dao.observeBetween(from, to)

    suspend fun save(record: MealRecordEntity): Long = withContext(io) { dao.upsert(record) }

    suspend fun existing(date: LocalDate, mealType: String): MealRecordEntity? =
        withContext(io) { dao.get(date, mealType) }

    suspend fun delete(record: MealRecordEntity) = withContext(io) {
        dao.delete(record)
        File(record.photoPath).takeIf { it.isFile }?.delete()
    }
}
