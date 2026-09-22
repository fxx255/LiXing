package com.example.lixing.data

import android.content.Context
import androidx.room.Room
import com.example.lixing.data.assistant.AssistantResponseParser
import com.example.lixing.data.assistant.PendingPlanReviewPayload
import com.example.lixing.data.assistant.decodePendingReview
import com.example.lixing.data.assistant.encodePendingReview
import com.example.lixing.data.local.LiXingDatabase
import com.example.lixing.data.local.dao.EnglishEntryDao
import com.example.lixing.data.local.entity.AssistantConversationEntity
import com.example.lixing.data.local.entity.AssistantMessageEntity
import com.example.lixing.data.local.entity.EnglishEntryEntity
import com.example.lixing.data.local.entity.StudyPlanEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.data.repository.AssistantReviewTransactionRepository
import com.example.lixing.data.repository.EnglishApplyOutcome
import com.example.lixing.data.repository.EnglishEntryRepository
import com.example.lixing.data.repository.EnglishReviewTransactionResult
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.PlanReviewTransactionResult
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.assistant.EnglishEntryAction
import com.example.lixing.domain.assistant.PlanAction
import com.example.lixing.domain.assistant.PlanApplyResult
import com.example.lixing.domain.assistant.PlanChangeApplier
import com.example.lixing.domain.english.EnglishEntryType
import com.example.lixing.domain.model.TargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 确认批次的**真实 Room 事务**测试。
 *
 * 不是「CAS 字符串往返」也不是 mock：信封按真实 JSON 编码、动作从信封重新解析
 * （与 VM 的恢复路径一致），每一步都落在内存数据库里的真实计划行 / 英语行上；
 * 并发用两个服务实例，写后失败用「写完第一行再抛」的 applier/DAO，
 * 最后回头查库验证实际行与 applied 标记。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AssistantReviewTransactionTest {
    private val today: LocalDate = LocalDate.of(2026, 8, 9)

    private lateinit var database: LiXingDatabase
    private lateinit var planRepository: PlanRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var applier: PlanChangeApplier
    private lateinit var service: AssistantReviewTransactionRepository
    /** 第二个实例：模拟「另一个入口」（别的 VM / 重开页面）同时确认同一批。 */
    private lateinit var otherService: AssistantReviewTransactionRepository

    private lateinit var slot: TimeSlotEntity
    private lateinit var template: TaskTemplateEntity

    /** 写完第一行之后才抛的异常，用来验证事务把已写行一并回滚。 */
    private object BoomAfterWrite : RuntimeException("写后异常")

    @Before
    fun setUp() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context
        database = Room.inMemoryDatabaseBuilder(context, LiXingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        planRepository = PlanRepository(
            database.planDao(),
            database.taskTemplateDao(),
            database.dailyTaskDao(),
            database.dayRecordDao(),
            database.focusSessionDao(),
            Dispatchers.Unconfined,
        )
        taskRepository = TaskRepository(
            database.dailyTaskDao(),
            database.dayRecordDao(),
            planRepository,
            Dispatchers.Unconfined,
        )
        applier = PlanChangeApplier(planRepository, taskRepository)
        service = newService(applier, database.englishEntryDao())
        otherService = newService(applier, database.englishEntryDao())

        val planId = planRepository.createPlan(
            StudyPlanEntity(
                name = "测试计划",
                startDate = LocalDate.of(2026, 8, 1),
                targetDate = LocalDate.of(2026, 12, 19),
            ),
        )
        val subject = SubjectEntity(planId = planId, name = "数学", colorArgb = 0xFF3366.toInt())
            .let { it.copy(id = planRepository.upsertSubject(it)) }
        slot = planRepository.getTimeSlot(
            planRepository.upsertTimeSlot(
                TimeSlotEntity(
                    planId = planId,
                    name = "上午",
                    startTime = LocalTime.of(8, 0),
                    endTime = LocalTime.of(11, 30),
                ),
            ),
        )!!
        template = planRepository.getTemplate(
            planRepository.upsertTemplate(
                TaskTemplateEntity(
                    subjectId = subject.id,
                    timeSlotId = slot.id,
                    title = "660 题",
                    targetType = TargetType.MINUTES,
                    targetValue = 120,
                ),
            ),
        )!!
    }

    @After
    fun tearDown() = database.close()

    private fun newService(
        applier: PlanChangeApplier,
        englishDao: EnglishEntryDao,
    ): AssistantReviewTransactionRepository =
        AssistantReviewTransactionRepository(
            database = database,
            chatDao = database.assistantChatDao(),
            englishEntryRepository = EnglishEntryRepository(englishDao, database, Dispatchers.Unconfined),
            applier = applier,
            io = Dispatchers.Unconfined,
        )

    // ---------------- 计划：并发只生效一次 ----------------

    @Test
    fun `两个服务实例并发确认同一批计划只落一次实际修改`() = runTest {
        val batch = seedPlanBatch(updateSlotJson())
        val selected = batch.planActions

        val results = coroutineScope {
            val a = async { service.applyPlan(batch.conversationId, batch.messageId, selected, today, Int.MAX_VALUE) }
            val b = async { otherService.applyPlan(batch.conversationId, batch.messageId, selected, today, Int.MAX_VALUE) }
            listOf(a.await(), b.await())
        }

        // 只有一个赢：另一个拿到 AlreadyConsumed，不再执行任何动作。
        assertEquals(1, results.count { it is PlanReviewTransactionResult.Applied })
        assertEquals(1, results.count { it is PlanReviewTransactionResult.AlreadyConsumed })

        // 实际行只被改一次（9:00 生效，不是叠加两次）。
        val updated = planRepository.getTimeSlot(slot.id)!!
        assertEquals(LocalTime.of(9, 0), updated.startTime)
        assertEquals(LocalTime.of(11, 0), updated.endTime)
        assertTrue(envelopeOf(batch.messageId).applied)
    }

    @Test
    fun `已应用过的批次再确认不重复执行`() = runTest {
        val batch = seedPlanBatch(updateTemplateJson(targetValue = 90))
        val selected = batch.planActions

        val first = service.applyPlan(batch.conversationId, batch.messageId, selected, today, Int.MAX_VALUE)
        assertTrue(first is PlanReviewTransactionResult.Applied)
        assertEquals(90, planRepository.getTemplate(template.id)!!.targetValue)

        val second = otherService.applyPlan(batch.conversationId, batch.messageId, selected, today, Int.MAX_VALUE)
        assertTrue(second is PlanReviewTransactionResult.AlreadyConsumed)
        assertEquals(90, planRepository.getTemplate(template.id)!!.targetValue)
    }

    // ---------------- 计划：写后异常 / 取消整体回滚 ----------------

    @Test
    fun `写入后抛异常回滚实际行与已应用标记`() = runTest {
        val batch = seedPlanBatch(updateSlotJson(), updateTemplateJson(targetValue = 90))

        val failing = newService(
            applier = object : PlanChangeApplier(planRepository, taskRepository) {
                override suspend fun applyStrict(
                    actions: List<PlanAction>,
                    today: LocalDate,
                    dayOffLimit: Int,
                ): List<PlanApplyResult> {
                    // 第一条已经真的写进库了；此刻抛出，整批必须回滚。
                    super.applyStrict(listOf(actions.first()), today, dayOffLimit)
                    throw BoomAfterWrite
                }
            },
            englishDao = database.englishEntryDao(),
        )

        val thrown = runCatching {
            failing.applyPlan(batch.conversationId, batch.messageId, batch.planActions, today, Int.MAX_VALUE)
        }.exceptionOrNull()

        assertTrue("意外异常必须穿透事务", thrown === BoomAfterWrite)
        assertEquals(LocalTime.of(8, 0), planRepository.getTimeSlot(slot.id)!!.startTime)
        assertEquals(120, planRepository.getTemplate(template.id)!!.targetValue)
        assertFalse(envelopeOf(batch.messageId).applied)
    }

    @Test
    fun `取消回滚实际行与已应用标记`() = runTest {
        val batch = seedPlanBatch(updateSlotJson(), updateTemplateJson(targetValue = 90))

        val cancelling = newService(
            applier = object : PlanChangeApplier(planRepository, taskRepository) {
                override suspend fun applyStrict(
                    actions: List<PlanAction>,
                    today: LocalDate,
                    dayOffLimit: Int,
                ): List<PlanApplyResult> {
                    super.applyStrict(listOf(actions.first()), today, dayOffLimit)
                    throw CancellationException("页面销毁/用户取消")
                }
            },
            englishDao = database.englishEntryDao(),
        )

        val thrown = runCatching {
            cancelling.applyPlan(batch.conversationId, batch.messageId, batch.planActions, today, Int.MAX_VALUE)
        }.exceptionOrNull()

        assertTrue("CancellationException 必须原样抛出", thrown is CancellationException)
        assertEquals(LocalTime.of(8, 0), planRepository.getTimeSlot(slot.id)!!.startTime)
        assertEquals(120, planRepository.getTemplate(template.id)!!.targetValue)
        assertFalse(envelopeOf(batch.messageId).applied)
    }

    // ---------------- 计划：归属与动作核验 ----------------

    @Test
    fun `错误的会话 id 不认领也不写任何行`() = runTest {
        val batch = seedPlanBatch(updateSlotJson())
        val other = AssistantConversationEntity(
            title = "别的会话",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        database.assistantChatDao().insertConversation(other)

        val result = service.applyPlan(other.id, batch.messageId, batch.planActions, today, Int.MAX_VALUE)
        assertTrue(result is PlanReviewTransactionResult.StaleEnvelope)
        assertEquals(LocalTime.of(8, 0), planRepository.getTimeSlot(slot.id)!!.startTime)
        assertFalse(envelopeOf(batch.messageId).applied)
    }

    @Test
    fun `不属于该信封的动作被拒绝且不写任何行`() = runTest {
        val batch = seedPlanBatch(updateSlotJson())
        // 一条"看起来合法"但根本不在这批信封里的动作。
        val foreign = updateTemplateJson(targetValue = 90)

        val result = service.applyPlan(
            batch.conversationId,
            batch.messageId,
            AssistantResponseParser.parseActionsJson("[$foreign]"),
            today,
            Int.MAX_VALUE,
        )
        assertTrue(result is PlanReviewTransactionResult.Rejected)
        assertEquals(LocalTime.of(8, 0), planRepository.getTimeSlot(slot.id)!!.startTime)
        assertEquals(120, planRepository.getTemplate(template.id)!!.targetValue)
        assertFalse(envelopeOf(batch.messageId).applied)
    }

    @Test
    fun `信封里重复的同内容动作勾选两次仍可执行`() = runTest {
        val batch = seedPlanBatch(updateTemplateJson(targetValue = 110), updateTemplateJson(targetValue = 110))

        val result = service.applyPlan(batch.conversationId, batch.messageId, batch.planActions, today, Int.MAX_VALUE)
        assertTrue(result is PlanReviewTransactionResult.Applied)
        assertEquals(110, planRepository.getTemplate(template.id)!!.targetValue)
        assertTrue(envelopeOf(batch.messageId).applied)
    }

    // ---------------- 计划：部分失败 / 全部失败 ----------------

    @Test
    fun `部分失败仍逐条反馈且其余有效动作生效`() = runTest {
        val batch = seedPlanBatch(
            updateSlotJson(),
            updateTimeSlotJson(slotId = "不存在的时段", startTime = "09:00"),
        )

        val result = service.applyPlan(batch.conversationId, batch.messageId, batch.planActions, today, Int.MAX_VALUE)
        assertTrue(result is PlanReviewTransactionResult.Applied)
        val failures = (result as PlanReviewTransactionResult.Applied).results.filterNot { it.success }
        assertEquals(1, failures.size)

        // 有效动作确实生效，标记被消费（用户不需要重试整批）。
        assertEquals(LocalTime.of(9, 0), planRepository.getTimeSlot(slot.id)!!.startTime)
        assertTrue(envelopeOf(batch.messageId).applied)
    }

    @Test
    fun `全部失败不消费标记也不写行`() = runTest {
        val batch = seedPlanBatch(updateTimeSlotJson(slotId = "不存在的时段", startTime = "09:00"))

        val result = service.applyPlan(batch.conversationId, batch.messageId, batch.planActions, today, Int.MAX_VALUE)
        assertTrue(result is PlanReviewTransactionResult.NothingApplied)
        assertEquals(
            1,
            (result as PlanReviewTransactionResult.NothingApplied).results.filterNot { it.success }.size,
        )

        assertEquals(LocalTime.of(8, 0), planRepository.getTimeSlot(slot.id)!!.startTime)
        assertFalse(envelopeOf(batch.messageId).applied)
    }

    // ---------------- 英语 ----------------

    @Test
    fun `英语确认写入真实行且计划标记不受影响`() = runTest {
        val batch = seedEnglishBatch(addEnglishJson("ubiquitous", "无处不在的"))

        val result = service.applyEnglish(batch.conversationId, batch.messageId, batch.englishActions)
        assertTrue(result is EnglishReviewTransactionResult.Applied)
        val outcome = (result as EnglishReviewTransactionResult.Applied).outcome
        assertEquals(1, outcome.added)
        assertEquals(0, outcome.updated)
        assertEquals(0, outcome.deleted)

        val stored = database.englishEntryDao().observe("", null).first()
        assertEquals(1, stored.size)
        assertEquals("ubiquitous", stored.single().content)

        val payload = envelopeOf(batch.messageId)
        assertTrue(payload.englishApplied)
        assertFalse("计划标记必须独立", payload.applied)
    }

    @Test
    fun `计划与英语标记互相独立`() = runTest {
        val batch = seedMixedBatch(updateSlotJson(), addEnglishJson("resilient", "有韧性的"))

        service.applyPlan(batch.conversationId, batch.messageId, batch.planActions, today, Int.MAX_VALUE)
        assertTrue(envelopeOf(batch.messageId).applied)
        assertFalse(envelopeOf(batch.messageId).englishApplied)

        service.applyEnglish(batch.conversationId, batch.messageId, batch.englishActions)
        assertTrue(envelopeOf(batch.messageId).applied)
        assertTrue(envelopeOf(batch.messageId).englishApplied)
        assertEquals("resilient", database.englishEntryDao().observe("", null).first().single().content)
    }

    @Test
    fun `英语写入后异常回滚真实行与标记`() = runTest {
        val batch = seedEnglishBatch(addEnglishJson("rollback", "回滚"))
        val failing = newService(
            applier = applier,
            englishDao = FailingEnglishDao(database.englishEntryDao()),
        )

        val thrown = runCatching {
            failing.applyEnglish(batch.conversationId, batch.messageId, batch.englishActions)
        }.exceptionOrNull()

        assertTrue(thrown === BoomAfterWrite)
        assertTrue("英语行必须随事务回滚", database.englishEntryDao().observe("", null).first().isEmpty())
        assertFalse(envelopeOf(batch.messageId).englishApplied)
    }

    @Test
    fun `英语全部失败不消费标记`() = runTest {
        // 目标条目不存在：这是信封里会出现的合法动作，但执行时逐条失败。
        val batch = seedEnglishBatch(deleteEnglishJson("不存在的条目"))

        val result = service.applyEnglish(batch.conversationId, batch.messageId, batch.englishActions)
        assertTrue(result is EnglishReviewTransactionResult.NothingApplied)
        assertEquals(1, (result as EnglishReviewTransactionResult.NothingApplied).outcome.failures.size)
        assertTrue(database.englishEntryDao().observe("", null).first().isEmpty())
        assertFalse(envelopeOf(batch.messageId).englishApplied)
    }

    @Test
    fun `英语部分失败保留其余有效条目`() = runTest {
        val batch = seedEnglishBatch(
            addEnglishJson("valid", "有效的"),
            deleteEnglishJson("不存在的条目"),
        )

        val result = service.applyEnglish(batch.conversationId, batch.messageId, batch.englishActions)
        assertTrue(result is EnglishReviewTransactionResult.Applied)
        val outcome = (result as EnglishReviewTransactionResult.Applied).outcome
        assertEquals(1, outcome.added)
        assertEquals(1, outcome.failures.size)

        val stored = database.englishEntryDao().observe("", null).first()
        assertEquals(1, stored.size)
        assertEquals("valid", stored.single().content)
        assertTrue(envelopeOf(batch.messageId).englishApplied)
    }

    @Test
    fun `英语动作不属于信封时被拒绝`() = runTest {
        val batch = seedEnglishBatch(addEnglishJson("ubiquitous", "无处不在的"))
        val foreign = AssistantResponseParser.parseEnglishActionsJson(
            "[" + addEnglishJson("foreign", "不相干") + "]",
        )

        val result = service.applyEnglish(batch.conversationId, batch.messageId, foreign)
        assertTrue(result is EnglishReviewTransactionResult.Rejected)
        assertTrue(database.englishEntryDao().observe("", null).first().isEmpty())
        assertFalse(envelopeOf(batch.messageId).englishApplied)
    }

    // ---------------- 确认围栏（VM 侧围栏所消费的 DB 事实） ----------------

    /**
     * VM 在事务返回后重查「会话 + 批次归属」再写 UI；这里验证它所依据的
     * **数据库事实**：同一个 batch id 换一个会话查不到、信封仍是旧内容、
     * 不同批次的信封互不影响。围栏本身是纯 UI 判定，此处只负责给它喂真实输入。
     */
    @Test
    fun `批次归属事实与信封隔离支撑确认围栏`() = runTest {
        val rawA = updateSlotJson()
        val rawB = updateTemplateJson(targetValue = 90)
        val batchA = seedPlanBatch(rawA)
        val batchB = seedPlanBatch(rawB)

        // 同一 messageId 换会话查询必须返回 null（VM 围栏的 stale 依据之一）。
        assertNull(
            database.assistantChatDao().getPendingReviewInConversation(batchB.conversationId, batchA.messageId),
        )
        // 各批次信封仍归各自会话，且互未串写。
        assertTrue(envelopeOf(batchA.messageId).applied.not())
        assertTrue(envelopeOf(batchB.messageId).applied.not())

        // 窄范围 CAS 在错误会话下必须是 0 行（不会把另一个会话的批次认领掉）。
        val claimed = database.assistantChatDao().compareAndSetPendingReviewInConversation(
            conversationId = batchB.conversationId,
            messageId = batchA.messageId,
            expected = encodePendingReview(PendingPlanReviewPayload(actionsJson = rawA)),
            next = "never",
        )
        assertEquals(0, claimed)
        assertFalse(envelopeOf(batchA.messageId).applied)
        // batchA 的信封仍是它自己的原始内容（expected 不匹配 ⇒ 未被改动）；
        // actionsJson 本身是 JSON 数组（seedBatch 用 [..] 包裹）。
        assertEquals("[$rawA]", envelopeOf(batchA.messageId).actionsJson)
        assertEquals("[$rawB]", envelopeOf(batchB.messageId).actionsJson)
    }

    /**
     * **编译期穷尽性检查**：计划/英语结果各 5 个变体都必须被 VM 的 `when` 消费。
     * 新增变体而漏改 VM 时，这里（和 VM 的 when）会一起编译失败。
     */
    @Test
    fun `计划与英语结果变体保持穷尽`() {
        val planVariants: List<PlanReviewTransactionResult> = listOf(
            PlanReviewTransactionResult.Applied(emptyList()),
            PlanReviewTransactionResult.NothingApplied(emptyList()),
            PlanReviewTransactionResult.AlreadyConsumed,
            PlanReviewTransactionResult.StaleEnvelope,
            PlanReviewTransactionResult.Rejected("x"),
        )
        val englishVariants: List<EnglishReviewTransactionResult> = listOf(
            EnglishReviewTransactionResult.Applied(EnglishApplyOutcome(0, 0, 0)),
            EnglishReviewTransactionResult.NothingApplied(EnglishApplyOutcome(0, 0, 0)),
            EnglishReviewTransactionResult.AlreadyConsumed,
            EnglishReviewTransactionResult.StaleEnvelope,
            EnglishReviewTransactionResult.Rejected("x"),
        )
        // 用 when 表达式强制穷尽（缺分支 = 编译失败），并核对逐条 typed 结果保留。
        planVariants.forEach { variant ->
            val typedFailures: List<PlanApplyResult> = when (variant) {
                is PlanReviewTransactionResult.Applied -> variant.results
                is PlanReviewTransactionResult.NothingApplied -> variant.results
                is PlanReviewTransactionResult.Rejected -> emptyList()
                PlanReviewTransactionResult.AlreadyConsumed -> emptyList()
                PlanReviewTransactionResult.StaleEnvelope -> emptyList()
            }
            assertNotNull(typedFailures)
        }
        englishVariants.forEach { variant ->
            val outcome: EnglishApplyOutcome? = when (variant) {
                is EnglishReviewTransactionResult.Applied -> variant.outcome
                is EnglishReviewTransactionResult.NothingApplied -> variant.outcome
                is EnglishReviewTransactionResult.Rejected -> null
                EnglishReviewTransactionResult.AlreadyConsumed -> null
                EnglishReviewTransactionResult.StaleEnvelope -> null
            }
            if (variant is EnglishReviewTransactionResult.Applied ||
                variant is EnglishReviewTransactionResult.NothingApplied
            ) {
                assertNotNull("Applied/NothingApplied 必须保留 added/updated/deleted 统计", outcome)
            }
        }
    }

    // ---------------- 辅助 ----------------

    private data class SeededBatch(
        val conversationId: String,
        val messageId: String,
        val planActions: List<PlanAction>,
        val englishActions: List<EnglishEntryAction>,
    )

    private suspend fun envelopeOf(messageId: String): PendingPlanReviewPayload =
        requireNotNull(decodePendingReview(database.assistantChatDao().getPendingReview(messageId)!!))

    private suspend fun seedPlanBatch(vararg planJson: String): SeededBatch =
        seedBatch(planJson.toList(), emptyList())

    private suspend fun seedEnglishBatch(vararg englishJson: String): SeededBatch =
        seedBatch(emptyList(), englishJson.toList())

    private suspend fun seedMixedBatch(planJson: String, englishJson: String): SeededBatch =
        seedBatch(listOf(planJson), listOf(englishJson))

    private suspend fun seedBatch(planJson: List<String>, englishJson: List<String>): SeededBatch {
        val conversation = AssistantConversationEntity(
            title = "带方案的会话",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        database.assistantChatDao().insertConversation(conversation)
        val message = AssistantMessageEntity(
            conversationId = conversation.id,
            role = "assistant",
            content = "方案如下",
            pendingReview = encodePendingReview(
                PendingPlanReviewPayload(
                    actionsJson = planJson.joinToString(prefix = "[", postfix = "]"),
                    englishActionsJson = englishJson.joinToString(prefix = "[", postfix = "]"),
                ),
            ),
        )
        database.assistantChatDao().insertMessage(message)
        // 关键：与 VM 相同 —— 从落库信封重新解析出**类型化动作**再传给事务服务。
        val payload = requireNotNull(decodePendingReview(message.pendingReview))
        return SeededBatch(
            conversationId = conversation.id,
            messageId = message.id,
            planActions = AssistantResponseParser.parseActionsJson(payload.actionsJson),
            englishActions = AssistantResponseParser.parseEnglishActionsJson(payload.englishActionsJson),
        )
    }

    private fun updateSlotJson(): String = updateTimeSlotJson(slot.id, "09:00", "11:00")

    private fun updateTimeSlotJson(slotId: String, startTime: String, endTime: String? = null): String =
        buildString {
            append("""{"kind":"UPDATE_TIME_SLOT","slotId":"$slotId","startTime":"$startTime"""")
            if (endTime != null) append(""","endTime":"$endTime"""")
            append("}")
        }

    private fun updateTemplateJson(targetValue: Int): String =
        """{"kind":"UPDATE_TASK_TEMPLATE","templateId":"${template.id}","targetValue":$targetValue}"""

    private fun addEnglishJson(content: String, meaning: String): String =
        """{"kind":"ADD_ENGLISH_ENTRY","type":"WORD","content":"$content","meaning":"$meaning"}"""

    private fun deleteEnglishJson(id: String): String =
        """{"kind":"DELETE_ENGLISH_ENTRY","id":"$id"}"""

    /** 英语 DAO 包装：第一次 upsert 成功后抛异常，验证事务把已写行一并回滚。 */
    private class FailingEnglishDao(
        private val delegate: EnglishEntryDao,
    ) : EnglishEntryDao by delegate {
        private val written = AtomicBoolean(false)

        override suspend fun upsert(entry: EnglishEntryEntity) {
            delegate.upsert(entry)
            if (written.compareAndSet(false, true)) throw BoomAfterWrite
        }
    }
}
