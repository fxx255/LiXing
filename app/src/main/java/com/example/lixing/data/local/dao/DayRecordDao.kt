package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.lixing.data.local.entity.DayRecordEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 每日汇总读写。热力图、连续记录、趋势图都只读这张表。 */
@Dao
interface DayRecordDao {

    @Query("SELECT * FROM day_record WHERE date = :date")
    suspend fun getRecord(date: LocalDate): DayRecordEntity?

    @Query("SELECT * FROM day_record WHERE date = :date")
    fun observeRecord(date: LocalDate): Flow<DayRecordEntity?>

    @Query("SELECT * FROM day_record WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeRecordsBetween(from: LocalDate, to: LocalDate): Flow<List<DayRecordEntity>>

    @Query("SELECT * FROM day_record WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun getRecordsBetween(from: LocalDate, to: LocalDate): List<DayRecordEntity>

    @Upsert
    suspend fun upsert(record: DayRecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<DayRecordEntity>)

    /** 未结算且早于今天的记录，补结算用（比如用户几天没开 App）。 */
    @Query("SELECT * FROM day_record WHERE is_settled = 0 AND date < :today ORDER BY date")
    suspend fun getUnsettledBefore(today: LocalDate): List<DayRecordEntity>

    /** 达成天数总计。 */
    @Query("SELECT COUNT(*) FROM day_record WHERE is_achieved = 1")
    suspend fun countAchievedDays(): Int

    /** 满勤天数总计，用于成就。 */
    @Query("SELECT COUNT(*) FROM day_record WHERE is_full_day = 1")
    suspend fun countFullDays(): Int

    /** 某月的加权完成率（忽略请假日）。用于「月完成率 ≥ 90%」成就与月报。 */
    @Query(
        """
        SELECT AVG(completion_rate) FROM day_record
        WHERE date BETWEEN :from AND :to
          AND is_settled = 1
          AND is_day_off = 0
          AND total_tasks > 0
        """,
    )
    suspend fun getAverageCompletionRate(from: LocalDate, to: LocalDate): Float?

    @Query(
        """
        SELECT COALESCE(SUM(focus_minutes), 0) FROM day_record
        WHERE date BETWEEN :from AND :to
        """,
    )
    suspend fun getTotalFocusMinutes(from: LocalDate, to: LocalDate): Int

    /** 单日最高专注分钟数，用于「单日专注 4 小时」成就。 */
    @Query("SELECT COALESCE(MAX(focus_minutes), 0) FROM day_record")
    suspend fun getMaxSingleDayFocusMinutes(): Int

    /** 已结算记录里最晚的一天，用于判断补结算起点。 */
    @Query("SELECT MAX(date) FROM day_record WHERE is_settled = 1")
    suspend fun getLastSettledDate(): LocalDate?

    @Query("DELETE FROM day_record")
    suspend fun deleteAll()

    /** 只删「某日及以后」的汇总，历史保留。 */
    @Query("DELETE FROM day_record WHERE date >= :from")
    suspend fun deleteFrom(from: LocalDate)
}
