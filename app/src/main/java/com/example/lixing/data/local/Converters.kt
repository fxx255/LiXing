package com.example.lixing.data.local

import androidx.room.TypeConverter
import com.example.lixing.domain.model.AchievementCondition
import com.example.lixing.domain.model.CommitmentStatus
import com.example.lixing.domain.model.CommitmentMetric
import com.example.lixing.domain.model.FocusMode
import com.example.lixing.domain.model.Mood
import com.example.lixing.domain.model.PointReason
import com.example.lixing.domain.model.RepeatRule
import com.example.lixing.domain.model.TargetType
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.model.TaskType
import com.example.lixing.domain.english.EnglishEntryType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Room 类型转换。
 *
 * 约定：
 * - LocalDate 存为 epochDay（Long），可直接排序、做区间查询。
 * - LocalTime 存为「当天第几秒」（Int），同样可直接比较。
 * - Instant 存为 epochMilli（Long）。
 * - 枚举存 name（String），配合 ProGuard keep 规则保证混淆后仍能反解。
 *   存 name 而不是 ordinal，是为了以后往枚举中间插值不会错位。
 */
class Converters {

    @TypeConverter
    fun localDateToLong(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun longToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun localTimeToInt(value: LocalTime?): Int? = value?.toSecondOfDay()

    @TypeConverter
    fun intToLocalTime(value: Int?): LocalTime? =
        value?.let { LocalTime.ofSecondOfDay(it.toLong()) }

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun taskTypeToString(value: TaskType?): String? = value?.name

    @TypeConverter
    fun stringToTaskType(value: String?): TaskType? =
        value?.let { runCatching { TaskType.valueOf(it) }.getOrDefault(TaskType.CUSTOM) }

    @TypeConverter
    fun targetTypeToString(value: TargetType?): String? = value?.name

    @TypeConverter
    fun stringToTargetType(value: String?): TargetType? =
        value?.let { runCatching { TargetType.valueOf(it) }.getOrDefault(TargetType.BOOLEAN) }

    @TypeConverter
    fun taskStatusToString(value: TaskStatus?): String? = value?.name

    @TypeConverter
    fun stringToTaskStatus(value: String?): TaskStatus? =
        value?.let { runCatching { TaskStatus.valueOf(it) }.getOrDefault(TaskStatus.PENDING) }

    @TypeConverter
    fun repeatRuleToString(value: RepeatRule?): String? = value?.name

    @TypeConverter
    fun stringToRepeatRule(value: String?): RepeatRule? =
        value?.let { runCatching { RepeatRule.valueOf(it) }.getOrDefault(RepeatRule.DAILY) }

    @TypeConverter
    fun pointReasonToString(value: PointReason?): String? = value?.name

    @TypeConverter
    fun stringToPointReason(value: String?): PointReason? =
        value?.let { runCatching { PointReason.valueOf(it) }.getOrDefault(PointReason.MANUAL_ADJUST) }

    @TypeConverter
    fun moodToString(value: Mood?): String? = value?.name

    @TypeConverter
    fun stringToMood(value: String?): Mood? =
        value?.let { runCatching { Mood.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun conditionToString(value: AchievementCondition?): String? = value?.name

    @TypeConverter
    fun stringToCondition(value: String?): AchievementCondition? =
        value?.let { runCatching { AchievementCondition.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun focusModeToString(value: FocusMode?): String? = value?.name

    @TypeConverter
    fun stringToFocusMode(value: String?): FocusMode? =
        value?.let { runCatching { FocusMode.valueOf(it) }.getOrDefault(FocusMode.POMODORO) }

    @TypeConverter
    fun commitmentStatusToString(value: CommitmentStatus?): String? = value?.name

    @TypeConverter
    fun stringToCommitmentStatus(value: String?): CommitmentStatus? =
        value?.let { runCatching { CommitmentStatus.valueOf(it) }.getOrDefault(CommitmentStatus.ACTIVE) }

    @TypeConverter
    fun commitmentMetricToString(value: CommitmentMetric?): String? = value?.name

    @TypeConverter
    fun stringToCommitmentMetric(value: String?): CommitmentMetric? =
        value?.let { runCatching { CommitmentMetric.valueOf(it) }.getOrDefault(CommitmentMetric.COMPLETION_RATE) }

    @TypeConverter
    fun englishEntryTypeToString(value: EnglishEntryType?): String? = value?.name

    @TypeConverter
    fun stringToEnglishEntryType(value: String?): EnglishEntryType? =
        value?.let { runCatching { EnglishEntryType.valueOf(it) }.getOrDefault(EnglishEntryType.WORD) }
}
