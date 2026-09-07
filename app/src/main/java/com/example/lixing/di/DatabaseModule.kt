package com.example.lixing.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.sync.SyncTriggerInstaller
import com.example.lixing.data.local.Migrations
import com.example.lixing.data.local.dao.DailyTaskDao
import com.example.lixing.data.local.dao.DayRecordDao
import com.example.lixing.data.local.dao.FocusSessionDao
import com.example.lixing.data.local.dao.GamificationDao
import com.example.lixing.data.local.dao.PlanDao
import com.example.lixing.data.local.dao.TaskTemplateDao
import com.example.lixing.data.local.dao.MealRecordDao
import com.example.lixing.data.local.dao.AssistantChatDao
import com.example.lixing.data.local.dao.EnglishEntryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        syncTriggers: SyncTriggerInstaller,
    ): LiXingDatabase =
        Room.databaseBuilder(context, LiXingDatabase::class.java, LiXingDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            // 外键约束默认关闭，这里显式打开，保证级联删除生效
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // 同步触发器不在 Room 的 schema 校验范围内，放在 onOpen 建：
            // 新建的库、以及从 v9 迁移上来的老库，开库时都会补齐。
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        syncTriggers.install(db)
                    }
                },
            )
            .build()

    @Provides
    fun providePlanDao(db: LiXingDatabase): PlanDao = db.planDao()

    @Provides
    fun provideTaskTemplateDao(db: LiXingDatabase): TaskTemplateDao = db.taskTemplateDao()

    @Provides
    fun provideDailyTaskDao(db: LiXingDatabase): DailyTaskDao = db.dailyTaskDao()

    @Provides
    fun provideDayRecordDao(db: LiXingDatabase): DayRecordDao = db.dayRecordDao()

    @Provides
    fun provideFocusSessionDao(db: LiXingDatabase): FocusSessionDao = db.focusSessionDao()

    @Provides
    fun provideGamificationDao(db: LiXingDatabase): GamificationDao = db.gamificationDao()

    @Provides
    fun provideMealRecordDao(db: LiXingDatabase): MealRecordDao = db.mealRecordDao()

    @Provides
    fun provideEnglishEntryDao(db: LiXingDatabase): EnglishEntryDao = db.englishEntryDao()

    @Provides
    fun provideAssistantChatDao(db: LiXingDatabase): AssistantChatDao = db.assistantChatDao()
}
