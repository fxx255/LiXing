package com.example.lixing.data.repository

import com.example.lixing.data.local.dao.DailyTaskDao
import com.example.lixing.data.local.dao.DayRecordDao
import com.example.lixing.data.local.dao.SlotCompletion
import com.example.lixing.data.local.dao.SubjectMinutes
import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.DayRecordEntity
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.materialize.TaskMaterializer
import com.example.lixing.domain.materialize.DailyTaskReconciler
import com.example.lixing.domain.model.Mood
import com.example.lixing.domain.model.TaskStatus
import com.example.lixing.domain.settle.DayStats
import com.example.lixing.domain.settle.DayStatsCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当日任务与每日汇总的读写。
 *
 * 打卡、结算这些跨表的业务编排放在 [com.example.lixing.domain.usecase] 里，
 * 这一层只负责「怎么存怎么取」，保持职责清晰。
 */
@Singleton
class TaskRepository @Inject constructor(
    private val dailyTaskDao: DailyTaskDao,
    private val dayRecordDao: DayRecordDao,
    private val planRepository: PlanRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    // ---------------- 读 ----------------

    fun observeTasksOfDay(date: LocalDate): Flow<List<DailyTaskEntity>> =
        dailyTaskDao.observeTasksOfDay(date)

    fun observeTasksBetween(from: LocalDate, to: LocalDate): Flow<List<DailyTaskEntity>> =
        dailyTaskDao.observeTasksBetween(from, to)

    fun observeTask(id: String): Flow<DailyTaskEntity?> = dailyTaskDao.observeTask(id)

    suspend fun getTask(id: String): DailyTaskEntity? = withContext(io) { dailyTaskDao.getTask(id) }

    suspend fun getTasksOfDay(date: LocalDate): List<DailyTaskEntity> =
        withContext(io) { dailyTaskDao.getTasksOfDay(date) }

    suspend fun getTasksOfSlot(date: LocalDate, slotId: String): List<DailyTaskEntity> =
        withContext(io) { dailyTaskDao.getTasksOfSlot(date, slotId) }

    suspend fun getTasksBetween(from: LocalDate, to: LocalDate): List<DailyTaskEntity> =
        withContext(io) { dailyTaskDao.getTasksBetween(from, to) }

    fun observeDayRecord(date: LocalDate): Flow<DayRecordEntity?> = dayRecordDao.observeRecord(date)

    fun observeDayRecordsBetween(from: LocalDate, to: LocalDate): Flow<List<DayRecordEntity>> =
        dayRecordDao.observeRecordsBetween(from, to)

    suspend fun getDayRecord(date: LocalDate): DayRecordEntity? =
        withContext(io) { dayRecordDao.getRecord(date) }

    suspend fun getDayRecordsBetween(from: LocalDate, to: LocalDate): List<DayRecordEntity> =
        withContext(io) { dayRecordDao.getRecordsBetween(from, to) }

    suspend fun getSubjectMinutes(from: LocalDate, to: LocalDate): List<SubjectMinutes> =
        withContext(io) { dailyTaskDao.getSubjectMinutes(from, to) }

    suspend fun getSlotCompletion(from: LocalDate, to: LocalDate): List<SlotCompletion> =
        withContext(io) {
            val tasks = dailyTaskDao.getTasksBetween(from, to)
            tasks.groupBy { it.timeSlotId to it.slotName }.map { (key, slotTasks) ->
                val dayRates = slotTasks.groupBy { it.date }.map { (_, dayTasks) ->
                    val active = dayTasks.filter { it.status != TaskStatus.SKIPPED }
                    if (active.isEmpty()) null else {
                        val required = active.first().slotRequiredTaskCount
                        if (required > 0) {
                            (active.count { it.status.isEngaged }.toFloat() / required).coerceIn(0f, 1f)
                        } else {
                            active.count { it.status == TaskStatus.DONE }.toFloat() / active.size
                        }
                    }
                }.filterNotNull()
                SlotCompletion(key.first, key.second, dayRates.size, 0,
                    dayRates.average().toFloat().takeIf { dayRates.isNotEmpty() })
            }.sortedBy { it.slotId }
        }

    suspend fun getTotalFocusMinutes(from: LocalDate, to: LocalDate): Int =
        withContext(io) { dayRecordDao.getTotalFocusMinutes(from, to) }

    /** 当天实时统计。首页进度环用这个，不等结算。 */
    suspend fun computeStats(date: LocalDate): DayStats =
        withContext(io) { DayStatsCalculator.calculate(dailyTaskDao.getTasksOfDay(date)) }

    // ---------------- 物化 ----------------

    /**
     * 为某天物化任务。幂等——(date, template_id) 唯一索引 + INSERT OR IGNORE，
     * 重复调用不会产生重复任务，所以「每次打开 App 都调一次」是安全的。
     *
     * @return 实际新插入的任务数
     */
    suspend fun materializeDay(date: LocalDate): Int = withContext(io) {
        val tasks = buildTasksForDay(date)
        if (tasks.isEmpty()) return@withContext 0

        val before = dailyTaskDao.getTasksOfDay(date).count()
        dailyTaskDao.insertIgnore(tasks)
        val inserted = dailyTaskDao.getTasksOfDay(date).count() - before

        // 有新任务就确保 day_record 存在，后续结算和统计都依赖它
        if (inserted > 0 && dayRecordDao.getRecord(date) == null) {
            dayRecordDao.upsert(DayRecordEntity(date = date, totalTasks = tasks.size))
        }
        inserted
    }

    /**
     * 按最新计划刷新一个尚未结算的学习日。计划编辑页只对“今天”调用它：
     * 更新快照字段并保留打卡进度；历史记录仍然冻结，不受影响。
     */
    suspend fun reconcileDayWithPlan(date: LocalDate) = withContext(io) {
        val record = dayRecordDao.getRecord(date)
        if (record?.isSettled == true) return@withContext

        val existing = dailyTaskDao.getTasksOfDay(date)
        val expected = buildTasksForDay(date)
        val changes = DailyTaskReconciler.reconcile(existing, expected)

        changes.deleteIds.forEach { dailyTaskDao.deleteById(it) }
        if (changes.updates.isNotEmpty()) dailyTaskDao.updateAll(changes.updates)
        if (changes.inserts.isNotEmpty()) dailyTaskDao.insertIgnore(changes.inserts)

        val taskCount = dailyTaskDao.getTasksOfDay(date).count { it.status != TaskStatus.SKIPPED }
        if (record != null || taskCount > 0) {
            dayRecordDao.upsert((record ?: DayRecordEntity(date = date)).copy(totalTasks = taskCount))
        }
    }

    suspend fun removePendingTemplate(date: LocalDate, templateId: String) = withContext(io) {
        dailyTaskDao.deletePendingTemplate(date, templateId)
    }

    suspend fun removePendingSlot(date: LocalDate, slotId: String) = withContext(io) {
        dailyTaskDao.deletePendingSlot(date, slotId)
    }

    suspend fun removePendingSubject(date: LocalDate, subjectId: String) = withContext(io) {
        dailyTaskDao.deletePendingSubject(date, subjectId)
    }

    private suspend fun buildTasksForDay(date: LocalDate): List<DailyTaskEntity> {
        val plan = planRepository.getActivePlan() ?: return emptyList()

        // 计划开始之前不生成任务。否则「全程」任务会漏到计划生效日之前。
        if (date < plan.startDate) return emptyList()

        val templates = planRepository.getEnabledTemplates(plan.id)
        if (templates.isEmpty()) return emptyList()

        return TaskMaterializer.materialize(
            date = date,
            templates = templates,
            slots = planRepository.getTimeSlots(plan.id).associateBy { it.id },
            subjects = planRepository.getSubjects(plan.id).associateBy { it.id },
            phases = planRepository.getPhases(plan.id).associateBy { it.id },
            planStart = plan.startDate,
        )
    }

    /**
     * 物化今天以及最近若干天（补上用户没开 App 的那几天）。
     * 只补 [backfillDays] 天，太久以前的没有补的意义，还会拖慢启动。
     */
    suspend fun materializeRecent(today: LocalDate, backfillDays: Int = 3): Int = withContext(io) {
        var total = 0
        for (offset in backfillDays downTo 0) {
            total += materializeDay(today.minusDays(offset.toLong()))
        }
        total
    }

    // ---------------- 写 ----------------

    suspend fun updateTask(task: DailyTaskEntity) = withContext(io) { dailyTaskDao.update(task) }

    suspend fun updateTasks(tasks: List<DailyTaskEntity>) =
        withContext(io) { dailyTaskDao.updateAll(tasks) }

    suspend fun addFocusedMinutes(taskId: String, minutes: Int) =
        withContext(io) { dailyTaskDao.addFocusedMinutes(taskId, minutes) }

    /** 把待做任务标记为漏卡。当天结算时调用。 */
    suspend fun markPendingAsMissed(date: LocalDate) =
        withContext(io) { dailyTaskDao.markPendingAsMissed(date) }

    suspend fun markDayAsSkipped(date: LocalDate) =
        withContext(io) { dailyTaskDao.markDayAsSkipped(date) }

    suspend fun unmarkDaySkipped(date: LocalDate) =
        withContext(io) { dailyTaskDao.unmarkDaySkipped(date) }

    suspend fun markSlotAsSkipped(date: LocalDate, slotId: String) =
        withContext(io) { dailyTaskDao.markSlotAsSkipped(date, slotId) }

    /** 保存当日复盘。同时写进 day_record 和当天所有任务。 */
    suspend fun saveReflection(date: LocalDate, mood: Mood?, reflection: String?) =
        withContext(io) {
            dailyTaskDao.updateDayReflection(date, mood?.name, reflection)
            val record = dayRecordDao.getRecord(date) ?: DayRecordEntity(date = date)
            dayRecordDao.upsert(record.copy(mood = mood, reflection = reflection))
        }

    suspend fun upsertDayRecord(record: DayRecordEntity) = withContext(io) { dayRecordDao.upsert(record) }

    /** 未结算且已过去的日子，补结算用。 */
    suspend fun getUnsettledBefore(today: LocalDate): List<DayRecordEntity> =
        withContext(io) { dayRecordDao.getUnsettledBefore(today) }

    suspend fun getLastSettledDate(): LocalDate? = withContext(io) { dayRecordDao.getLastSettledDate() }

    /** 整天请假标记（不改任务状态，由调用方决定是否连带标记任务）。 */
    suspend fun setDayOff(date: LocalDate, isDayOff: Boolean) = withContext(io) {
        val record = dayRecordDao.getRecord(date) ?: DayRecordEntity(date = date)
        dayRecordDao.upsert(record.copy(isDayOff = isDayOff))
    }

    suspend fun countAllCheckIns(): Int = withContext(io) { dailyTaskDao.countAllCheckIns() }

    /**
     * 去重自愈。清掉历史遗留的同名重复任务（跨导入批次 template_id 变了，
     * 幂等索引挡不住）。保留规则：优先留已完成/部分完成的，其次留 actualValue 高的，
     * 再次留 id 最小的。这样不会丢打卡成绩与照片。
     */
    suspend fun dedupeTasks(): Int = withContext(io) {
        val all = dailyTaskDao.getAllForDedupe()
        // 按 (date, title, slot_name) 分组 —— 不能按 time_slot_id，
        // 因为旧计划导入的 slot ID 和新计划不同，同名时段会认不出是重复。
        val groups = all.groupBy { Triple(it.date, it.title, it.slotName) }
        val toDelete = ArrayList<String>()
        for ((_, tasks) in groups) {
            if (tasks.size <= 1) continue
            // 优先保留有实际完成量的，其余删除
            val best = tasks.maxWithOrNull(compareBy(
                { if (it.status == com.example.lixing.domain.model.TaskStatus.DONE ||
                         it.status == com.example.lixing.domain.model.TaskStatus.PARTIAL) 0 else 1 },
                { it.actualValue },
                { it.id }, // UUID 无大小语义，仅保证排序确定
            )) ?: continue
            toDelete.addAll(tasks.filter { it.id != best.id }.map { it.id })
        }
        toDelete.forEach { dailyTaskDao.deleteById(it) }
        toDelete.size
    }

    suspend fun clearAll() = withContext(io) {
        dailyTaskDao.deleteAll()
        dayRecordDao.deleteAll()
    }
}
