package com.example.lixing.data.repository

import androidx.room.withTransaction
import com.example.lixing.data.assistant.AssistantResponseParser
import com.example.lixing.data.assistant.PendingPlanReviewPayload
import com.example.lixing.data.assistant.decodePendingReview
import com.example.lixing.data.assistant.encodePendingReview
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.dao.AssistantChatDao
import com.example.lixing.di.IoDispatcher
import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.assistant.PlanApplyResult
import com.example.lixing.domain.assistant.PlanChangeApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 一批**计划**建议的事务化执行结果（[results] 保留逐条类型化结果，供界面沿用原失败提示）。 */
sealed interface PlanReviewTransactionResult {

    /**
     * 认领成功并执行完副作用。
     *
     * - [results] 里存在 `success=true` ⇒ 部分成功：标记已消费，只保留失败项可重试；
     * - [results] 全部 `success=false` ⇒ **标记未被消费**（事务已回滚），整批可原样重试。
     */
    data class Applied(val results: List<PlanApplyResult>) : PlanReviewTransactionResult

    /** 认领成功但一条都没成功：标记未被消费（事务已回滚），整批可原样重试。 */
    data class NothingApplied(val results: List<PlanApplyResult>) : PlanReviewTransactionResult

    /** 已被别处认领过（或 CAS 失败）：不执行任何副作用，界面按「已应用过」收敛。 */
    data object AlreadyConsumed : PlanReviewTransactionResult

    /** 信封已不在 / 损坏 / 消息不属于该会话：不执行任何副作用，界面按「已失效」收敛。 */
    data object StaleEnvelope : PlanReviewTransactionResult

    /** 传入的批次不属于该会话，或所选动作不属于该信封。 */
    data class Rejected(val reason: String) : PlanReviewTransactionResult
}

/** 一批**英语积累**变更的事务化执行结果。 */
sealed interface EnglishReviewTransactionResult {

    /** 认领成功且至少一条生效；[outcome] 保留 added/updated/deleted 计数与逐条失败。 */
    data class Applied(val outcome: EnglishApplyOutcome) : EnglishReviewTransactionResult

    /** 全部为预期内的输入校验失败：**标记未被消费**（事务已回滚），整批可原样重试。 */
    data class NothingApplied(val outcome: EnglishApplyOutcome) : EnglishReviewTransactionResult

    /** 已被别处认领过（或 CAS 失败）：不执行任何副作用，界面按「已应用过」收敛。 */
    data object AlreadyConsumed : EnglishReviewTransactionResult

    /** 信封已不在 / 损坏 / 消息不属于该会话：不执行任何副作用，界面按「已应用过」收敛。 */
    data object StaleEnvelope : EnglishReviewTransactionResult

    /** 传入的批次不属于该会话，或所选动作不属于该信封。 */
    data class Rejected(val reason: String) : EnglishReviewTransactionResult
}

/** 英语逐条执行统计；[failures] 携带触发失败的**动作本体**，界面用它回填原提示文案。 */
data class EnglishApplyOutcome(
    val added: Int,
    val updated: Int,
    val deleted: Int,
    val failures: List<EnglishActionFailure> = emptyList(),
) {
    val total: Int get() = added + updated + deleted
}

/** 单条英语变更的预期内失败。 */
data class EnglishActionFailure(
    val action: EnglishEntryAction,
    val message: String,
)

/**
 * 确认批次（计划 / 英语）的**事务化**应用。
 *
 * ## 为什么不是"先 CAS 认领、再执行副作用"
 *
 * 旧路径把「置 applied 标记」与「真正改计划/写英语条目」拆成两步：
 * - 两步之间崩溃/进程被杀 ⇒ 标记已写而副作用没做（或反之，重开再执行一遍）；
 * - 两个入口并发 ⇒ CAS 之外仍有窗口让实际写入各做一次。
 *
 * 现在：一次 `database.withTransaction {}` 里完成
 * **读信封 + 核验归属 + 核验所选动作多重子集 + 判断对应 applied 标记 + CAS 认领 +
 * 实际计划/英语写入 + 最终信封**。因此：
 * - 认领失败 ⇒ 一条实际动作都不执行；
 * - 意外异常 / 取消 ⇒ SQLite 回滚，实际行与标记一起消失；
 * - 全部动作都是预期内的校验失败 ⇒ 抛 [NothingAppliedRollback] 主动回滚，标记不被消费。
 *
 * ## 边界
 *
 * 事务内部**只有本地数据库写**：恢复点由调用方在进入本服务之前创建，
 * 网络/备份一律不得进入这里。计划与英语的 applied 标记**相互独立**，
 * 认领其一绝不动另一类。
 */
@Singleton
class AssistantReviewTransactionRepository @Inject constructor(
    private val database: LiXingDatabase,
    private val chatDao: AssistantChatDao,
    private val englishEntryRepository: EnglishEntryRepository,
    private val applier: PlanChangeApplier,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * 事务化应用一批**计划**建议。
     *
     * @param conversationId 批次所属会话（防切页/串会话认领错批次）。
     * @param messageId 批次标识（固定消息 id）。
     * @param selected 用户勾选的**类型化动作**；必须是该信封动作多重集的子集。
     */
    suspend fun applyPlan(
        conversationId: String,
        messageId: String,
        selected: List<PlanAction>,
        today: LocalDate,
        dayOffLimit: Int,
    ): PlanReviewTransactionResult = withContext(io) {
        runPlanTransaction(conversationId, messageId, selected, today, dayOffLimit)
    }

    /** 事务化应用一批**英语积累**变更。 */
    suspend fun applyEnglish(
        conversationId: String,
        messageId: String,
        selected: List<EnglishEntryAction>,
    ): EnglishReviewTransactionResult = withContext(io) {
        runEnglishTransaction(conversationId, messageId, selected)
    }

    // ---------------- 计划 ----------------

    private suspend fun runPlanTransaction(
        conversationId: String,
        messageId: String,
        selected: List<PlanAction>,
        today: LocalDate,
        dayOffLimit: Int,
    ): PlanReviewTransactionResult = transactionCatching {
        val (before, payload) = loadEnvelope(conversationId, messageId)
            ?: return@transactionCatching PlanReviewTransactionResult.StaleEnvelope
        if (payload.applied) return@transactionCatching PlanReviewTransactionResult.AlreadyConsumed
        if (!isSubMultiset(selected, AssistantResponseParser.parseActionsJson(payload.actionsJson))) {
            return@transactionCatching PlanReviewTransactionResult.Rejected("所选修改不属于这一批方案")
        }

        // 认领只置本类标记，保留另一类与勾选状态；失败 ⇒ 绝不执行实际动作。
        if (!claim(conversationId, messageId, before, payload.copy(applied = true))) {
            return@transactionCatching PlanReviewTransactionResult.AlreadyConsumed
        }

        // 严格模式：预期内的校验失败是返回值；意外异常/取消直接抛出 ⇒ 整个事务回滚。
        val results = applier.applyStrict(selected, today, dayOffLimit)
        if (results.none { it.success }) {
            throw NothingAppliedRollback(PlanReviewTransactionResult.NothingApplied(results))
        }
        PlanReviewTransactionResult.Applied(results)
    }

    // ---------------- 英语 ----------------

    private suspend fun runEnglishTransaction(
        conversationId: String,
        messageId: String,
        selected: List<EnglishEntryAction>,
    ): EnglishReviewTransactionResult = transactionCatching {
        val (before, payload) = loadEnvelope(conversationId, messageId)
            ?: return@transactionCatching EnglishReviewTransactionResult.StaleEnvelope
        if (payload.englishApplied) {
            return@transactionCatching EnglishReviewTransactionResult.AlreadyConsumed
        }
        if (!isSubMultiset(selected, AssistantResponseParser.parseEnglishActionsJson(payload.englishActionsJson))) {
            return@transactionCatching EnglishReviewTransactionResult.Rejected("所选英语变更不属于这一批方案")
        }

        if (!claim(conversationId, messageId, before, payload.copy(englishApplied = true))) {
            return@transactionCatching EnglishReviewTransactionResult.AlreadyConsumed
        }

        val outcome = writeEnglish(selected)
        if (outcome.total == 0) {
            throw NothingAppliedRollback(EnglishReviewTransactionResult.NothingApplied(outcome))
        }
        EnglishReviewTransactionResult.Applied(outcome)
    }

    /**
     * 逐条执行英语副作用，复用 [EnglishEntryRepository]（保留其输入校验与
     * 背诵记忆字段的"库内重读"语义）。
     *
     * 只有**预期内的输入校验失败**记为逐条失败（空内容/超长/目标条目已不存在）；
     * 数据库等意外异常原样抛出，让整个事务回滚。
     */
    private suspend fun writeEnglish(selected: List<EnglishEntryAction>): EnglishApplyOutcome {
        var added = 0
        var updated = 0
        var deleted = 0
        val failures = mutableListOf<EnglishActionFailure>()
        selected.forEach { action ->
            // 目标不存在：预期的逐条失败（与旧 VM 行为一致），不能当基础设施故障回滚整批。
            val targetId = when (action) {
                is EnglishEntryAction.Update -> action.id
                is EnglishEntryAction.Delete -> action.id
                else -> null
            }
            if (targetId != null && englishEntryRepository.get(targetId) == null) {
                failures += EnglishActionFailure(action, "要${if (action is EnglishEntryAction.Update) "修改" else "删除"}的条目已不存在")
                return@forEach
            }
            // 输入校验（IllegalArgumentException：空内容/超长/空释义）逐条捕获；
            // 除此之外（DB/IO/取消）必须穿透。
            val failure = runCatching {
                when (action) {
                    is EnglishEntryAction.Add -> {
                        englishEntryRepository.save(null, action.type, action.content, action.meaning)
                        added++
                    }

                    is EnglishEntryAction.Update -> {
                        val existing = requireNotNull(englishEntryRepository.get(action.id))
                        englishEntryRepository.save(
                            existing,
                            action.type ?: existing.type,
                            action.content ?: existing.content,
                            action.meaning ?: existing.meaning,
                        )
                        updated++
                    }

                    is EnglishEntryAction.Delete -> {
                        val existing = requireNotNull(englishEntryRepository.get(action.id))
                        englishEntryRepository.delete(existing)
                        deleted++
                    }
                }
            }.exceptionOrNull()
            if (failure is IllegalArgumentException) {
                failures += EnglishActionFailure(action, failure.message ?: "输入不合法")
            } else if (failure != null) {
                throw failure
            }
        }
        return EnglishApplyOutcome(added, updated, deleted, failures)
    }

    // ---------------- 事务骨架 ----------------

    /**
     * 读取「该会话下这条消息」的信封。
     *
     * 归属不符（消息不在该会话/已删除）或信封为空 ⇒ null，调用方按"不可消费"处理。
     */
    private suspend fun loadEnvelope(
        conversationId: String,
        messageId: String,
    ): Pair<String, PendingPlanReviewPayload>? {
        val raw = chatDao.getPendingReviewInConversation(conversationId, messageId) ?: return null
        val payload = decodePendingReview(raw) ?: return null
        return raw to payload
    }

    /**
     * 事务内的 CAS 认领：把「已应用」标记与实际写入放进同一事务。
     *
     * 返回 false ⇒ 调用方**绝不**执行任何实际动作。
     */
    private suspend fun claim(
        conversationId: String,
        messageId: String,
        expected: String,
        payload: PendingPlanReviewPayload,
    ): Boolean = chatDao.compareAndSetPendingReviewInConversation(
        conversationId = conversationId,
        messageId = messageId,
        expected = expected,
        next = encodePendingReview(payload),
    ) == 1

    /**
     * `withTransaction` + 把「全部失败」的内部回滚信号转换成正常返回值。
     *
     * 为什么用异常做回滚信号：Room 的事务成功与否只看块内是否抛出，
     * 没有"放弃事务但正常返回"的入口，所以只能抛。
     * `NothingAppliedRollback` 只在本类事务内出现，绝不外泄。
     */
    private suspend fun <R> transactionCatching(
        block: suspend () -> R,
    ): R = try {
        database.withTransaction { block() }
    } catch (e: NothingAppliedRollback) {
        @Suppress("UNCHECKED_CAST")
        e.result as R
    }

    /** 「全部失败 ⇒ 回滚认领」的内部信号；携带要返回给调用方的结果。 */
    private class NothingAppliedRollback(val result: Any) :
        RuntimeException("no action applied")

    /**
     * [selected] 是否是 [all] 的**多重子集**。
     *
     * 不去重：模型确实可能给出两条内容相同的独立动作（例如两个时段各改一次），
     * 用户勾选两次就必须执行两次；按集合比较会把这类合法批次判成"不属于信封"。
     */
    private fun <T> isSubMultiset(selected: List<T>, all: List<T>): Boolean {
        if (selected.isEmpty()) return true
        val remaining = all.toMutableList()
        selected.forEach { item ->
            val index = remaining.indexOf(item)
            if (index < 0) return false
            remaining.removeAt(index)
        }
        return true
    }
}
