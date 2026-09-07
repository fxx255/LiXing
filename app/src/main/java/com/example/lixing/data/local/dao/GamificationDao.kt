package com.example.lixing.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.example.lixing.data.local.entity.AchievementEntity
import com.example.lixing.data.local.entity.CheckInStreakEntity
import com.example.lixing.data.local.entity.CommitmentEntity
import com.example.lixing.data.local.entity.PointLedgerEntity
import com.example.lixing.data.local.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** 激励系统相关读写：积分流水、连续记录、成就、档案、承诺。 */
@Dao
interface GamificationDao {

    // ---------------- PointLedger ----------------

    /**
     * 入账。带 dedupe_key 的重复记录会被唯一索引挡掉（返回 -1），
     * 这样「连续 7 天奖励」之类的一次性积分在结算重跑时不会翻倍。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedger(entry: PointLedgerEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedgers(entries: List<PointLedgerEntity>): List<Long>

    @Query("SELECT * FROM point_ledger ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun observeRecentLedger(limit: Int = 200): Flow<List<PointLedgerEntity>>

    @Query("SELECT * FROM point_ledger WHERE date = :date ORDER BY created_at ASC, id ASC")
    suspend fun getLedgerOfDay(date: LocalDate): List<PointLedgerEntity>

    @Query("SELECT COALESCE(SUM(delta), 0) FROM point_ledger WHERE date = :date")
    suspend fun getPointsOfDay(date: LocalDate): Int

    @Query("SELECT COALESCE(SUM(delta), 0) FROM point_ledger")
    suspend fun getTotalPoints(): Int

    @Query("SELECT COALESCE(SUM(delta), 0) FROM point_ledger WHERE date BETWEEN :from AND :to")
    suspend fun getPointsBetween(from: LocalDate, to: LocalDate): Int

    /** 撤销某任务的积分（取消打卡时用）。 */
    @Query("DELETE FROM point_ledger WHERE daily_task_id = :taskId")
    suspend fun deleteLedgerOfTask(taskId: String)

    @Query("DELETE FROM point_ledger WHERE dedupe_key IN (:dedupeKeys)")
    suspend fun deleteLedgerByDedupeKeys(dedupeKeys: List<String>)

    @Query("DELETE FROM point_ledger")
    suspend fun deleteAllLedger()

    // ---------------- CheckInStreak ----------------

    @Query("SELECT * FROM check_in_streak WHERE id = 1")
    suspend fun getStreak(): CheckInStreakEntity?

    @Query("SELECT * FROM check_in_streak WHERE id = 1")
    fun observeStreak(): Flow<CheckInStreakEntity?>

    @Upsert
    suspend fun upsertStreak(streak: CheckInStreakEntity)

    // ---------------- Achievement ----------------

    @Query("SELECT * FROM achievement ORDER BY sort_order, tier, id")
    fun observeAchievements(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievement ORDER BY sort_order, tier, id")
    suspend fun getAchievements(): List<AchievementEntity>

    @Query("SELECT * FROM achievement WHERE unlocked_at IS NULL")
    suspend fun getLockedAchievements(): List<AchievementEntity>

    @Query("SELECT * FROM achievement WHERE code = :code")
    suspend fun getAchievementByCode(code: String): AchievementEntity?

    @Query("SELECT COUNT(*) FROM achievement WHERE unlocked_at IS NOT NULL")
    suspend fun countUnlocked(): Int

    @Query("SELECT COUNT(*) FROM achievement")
    suspend fun countAchievements(): Int

    /** 预置成就：已存在的 code 不覆盖（保住用户已解锁的时间戳）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAchievementsIgnore(items: List<AchievementEntity>): List<Long>

    @Update
    suspend fun updateAchievement(item: AchievementEntity)

    @Update
    suspend fun updateAchievements(items: List<AchievementEntity>)

    @Query("DELETE FROM achievement")
    suspend fun deleteAllAchievements()

    // ---------------- UserProfile ----------------

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun getProfile(): UserProfileEntity?

    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Upsert
    suspend fun upsertProfile(profile: UserProfileEntity)

    // ---------------- Commitment ----------------

    @Query("SELECT * FROM commitment ORDER BY start_date DESC")
    fun observeCommitments(): Flow<List<CommitmentEntity>>

    @Query("SELECT * FROM commitment WHERE status = 'ACTIVE' ORDER BY end_date LIMIT 1")
    fun observeActiveCommitment(): Flow<CommitmentEntity?>

    /** 到期但未结算的承诺。 */
    @Query("SELECT * FROM commitment WHERE status = 'ACTIVE' AND end_date < :today")
    suspend fun getExpiredCommitments(today: LocalDate): List<CommitmentEntity>

    @Upsert
    suspend fun upsertCommitment(commitment: CommitmentEntity)

    @Query("DELETE FROM commitment WHERE id = :id")
    suspend fun deleteCommitment(id: String)

    @Query("DELETE FROM commitment")
    suspend fun deleteAllCommitments()
}
