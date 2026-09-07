package com.example.lixing.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.lixing.data.local.dao.AssistantChatDao
import com.example.lixing.data.local.dao.DailyTaskDao
import com.example.lixing.data.local.dao.DayRecordDao
import com.example.lixing.data.local.dao.FocusSessionDao
import com.example.lixing.data.local.dao.EnglishEntryDao
import com.example.lixing.data.local.dao.GamificationDao
import com.example.lixing.data.local.dao.PlanDao
import com.example.lixing.data.local.dao.TaskTemplateDao
import com.example.lixing.data.local.entity.AchievementEntity
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.data.local.entity.CheckInStreakEntity
import com.example.lixing.data.local.entity.CommitmentEntity
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.data.local.entity.FocusSessionEntity
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.PointLedgerEntity
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.local.entity.UserProfileEntity
import com.example.lixing.data.local.entity.MealRecordEntity

/**
 * 应用数据库。
 *
 * 迁移策略：**不使用 fallbackToDestructiveMigration**。
 * 每次改动 schema 都必须：
 * 1. 提升 [VERSION]；
 * 2. 在 [com.example.lixing.data.local.Migrations] 里加一条 Migration；
 * 3. 提交 app/schemas 下新生成的 json（KSP 已配置导出）。
 * 单元测试 MigrationTest 会校验每一步迁移都能跑通。
 */
@Database(
    entities = [
        StudyPlanEntity::class,
        PhaseEntity::class,
        SubjectEntity::class,
        TimeSlotEntity::class,
        TaskTemplateEntity::class,
        DailyTaskEntity::class,
        DayRecordEntity::class,
        FocusSessionEntity::class,
        CheckInStreakEntity::class,
        PointLedgerEntity::class,
        AchievementEntity::class,
        UserProfileEntity::class,
        CommitmentEntity::class,
        MealRecordEntity::class,
        EnglishEntryEntity::class,
        AssistantConversationEntity::class,
        AssistantMessageEntity::class,
    ],
    version = LiXingDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LiXingDatabase : RoomDatabase() {

    abstract fun planDao(): PlanDao
    abstract fun taskTemplateDao(): TaskTemplateDao
    abstract fun dailyTaskDao(): DailyTaskDao
    abstract fun dayRecordDao(): DayRecordDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun gamificationDao(): GamificationDao
    abstract fun mealRecordDao(): com.example.lixing.data.local.dao.MealRecordDao
    abstract fun englishEntryDao(): EnglishEntryDao
    abstract fun assistantChatDao(): AssistantChatDao

    companion object {
        const val VERSION = 11
        const val NAME = "lixing.db"
    }
}
