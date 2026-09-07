package com.example.lixing.ui.screen.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lixing.data.local.entity.AchievementEntity
import com.example.lixing.data.local.entity.CommitmentEntity
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.PointLedgerEntity
import com.example.lixing.data.local.entity.UserProfileEntity
import com.example.lixing.data.prefs.UserPreferencesRepository
import com.example.lixing.data.repository.GamificationRepository
import com.example.lixing.data.repository.PlanRepository
import com.example.lixing.data.repository.TaskRepository
import com.example.lixing.domain.model.CommitmentStatus
import com.example.lixing.domain.model.CommitmentMetric
import com.example.lixing.domain.rules.LevelRules
import com.example.lixing.domain.settle.CommitmentProgress
import com.example.lixing.domain.time.StudyClock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** 进行中的承诺 + 它的实时进度。 */
data class ActiveCommitment(
    val commitment: CommitmentEntity,
    val progress: CommitmentProgress,
)

/**
 * 立约对话框的可编辑草稿。
 *
 * 日期用字符串暂存是刻意的：用户边打边校验会把「2026-1」这种中间态判成非法，
 * 输入体验很差。这里只在提交时解析，非法就停在对话框里提示。
 */
data class CommitmentDraft(
    val title: String = "",
    val phaseId: String? = null,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val targetRatePercent: Int = 85,
    val metric: CommitmentMetric = CommitmentMetric.COMPLETION_RATE,
    val rewardPoints: Int = 100,
    val customReward: String = "",
    val note: String = "",
    val error: String? = null,
) {
    /** 目标与奖励的合法区间。目标 0% 等于没承诺，100% 等于逼自己一天不落。 */
    val targetValid: Boolean get() = when (metric) {
        CommitmentMetric.COMPLETION_RATE -> targetRatePercent in 1..999
        CommitmentMetric.FOCUS_MINUTES -> targetRatePercent in 1..100_000
        CommitmentMetric.ACHIEVED_DAYS -> targetRatePercent in 1..10_000
    }
    val rewardValid: Boolean get() = rewardPoints in 0..9999
    val rangeValid: Boolean get() = !endDate.isBefore(startDate)
    val canSubmit: Boolean
        get() = title.isNotBlank() && targetValid && rewardValid && rangeValid
}

data class MineUiState(
    val profile: UserProfileEntity? = null,
    val levelProgress: Float = 0f,
    val pointsToNext: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val achievements: List<AchievementEntity> = emptyList(),
    val unlockedCount: Int = 0,
    val recentLedger: List<PointLedgerEntity> = emptyList(),
    /** 进行中的承诺，按到期时间升序——最近到期的排前面。 */
    val activeCommitments: List<ActiveCommitment> = emptyList(),
    /** 已结算的承诺，按开始日期倒序。 */
    val settledCommitments: List<CommitmentEntity> = emptyList(),
    /** 当前计划的阶段，立约时可直接选。 */
    val phases: List<PhaseEntity> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MineViewModel @Inject constructor(
    private val gamification: GamificationRepository,
    private val planRepository: PlanRepository,
    private val taskRepository: TaskRepository,
    private val prefsRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MineUiState())
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    /** 立约对话框：null = 关闭。 */
    private val _commitmentEditor = MutableStateFlow<CommitmentDraft?>(null)
    val commitmentEditor: StateFlow<CommitmentDraft?> = _commitmentEditor.asStateFlow()

    /** 切日点跟着设置走，不直接用 LocalDate.now()。 */
    private var clock = StudyClock()

    /** 当前学习日。承诺进度要跟着它重算，所以做成可观察的。 */
    private val todayFlow = MutableStateFlow(StudyClock().today())

    private fun today(): LocalDate = clock.today()

    init {
        viewModelScope.launch {
            gamification.profile.collect { profile ->
                _state.update {
                    it.copy(
                        profile = profile,
                        levelProgress = LevelRules.progressInLevel(profile?.totalPoints ?: 0),
                        pointsToNext = LevelRules.pointsToNextLevel(profile?.totalPoints ?: 0),
                    )
                }
            }
        }
        viewModelScope.launch {
            gamification.streak.collect { streak ->
                _state.update {
                    it.copy(
                        currentStreak = streak?.currentStreak ?: 0,
                        longestStreak = streak?.longestStreak ?: 0,
                    )
                }
            }
        }
        viewModelScope.launch {
            gamification.achievements.collect { list ->
                _state.update {
                    it.copy(
                        achievements = list,
                        unlockedCount = list.count { a -> a.unlockedAt != null },
                    )
                }
            }
        }
        viewModelScope.launch {
            gamification.recentLedger.collect { ledger ->
                _state.update { it.copy(recentLedger = ledger) }
            }
        }
        viewModelScope.launch {
            prefsRepository.preferences.collect { p ->
                clock = clock.withDayStart(p.dayStartTime)
                todayFlow.value = clock.today()
            }
        }
        viewModelScope.launch {
            planRepository.activePlan
                .flatMapLatest { plan ->
                    plan?.let { planRepository.observePhases(it.id) } ?: flowOf(emptyList())
                }
                .collect { phases ->
                    _state.update { it.copy(phases = phases) }
                }
        }
        observeCommitments()
    }

    /**
     * 承诺列表 + 实时进度。
     *
     * 除了承诺表本身，还要跟着「今天的 day_record」重算：打卡会即时改写
     * completion_rate，不跟着刷新的话，今日页打完卡回到这里看到的还是旧数字。
     * 进行中的承诺区间必然含今天，所以观察今天这一行就是够用的触发信号。
     *
     * 进行中的每条都要查一次区间完成率，但承诺数量是个位数，开销可以忽略。
     */
    private fun observeCommitments() {
        viewModelScope.launch {
            combine(
                gamification.commitments,
                todayFlow.flatMapLatest { taskRepository.observeDayRecord(it) },
                todayFlow,
            ) { all, _, today -> all to today }
                .collect { (all, today) ->
                    val active = all
                        .filter { it.status == CommitmentStatus.ACTIVE }
                        .sortedBy { it.endDate }
                        .map { ActiveCommitment(it, gamification.commitmentProgress(it, today)) }

                    _state.update {
                        it.copy(
                            activeCommitments = active,
                            settledCommitments = all.filter { c ->
                                c.status != CommitmentStatus.ACTIVE
                            },
                        )
                    }
                }
        }
    }

    // ---------------- 立约 ----------------

    /** 打开立约对话框。默认区间为当前阶段，没有阶段就用今天起 30 天。 */
    fun startNewCommitment() {
        viewModelScope.launch {
            val today = today()
            val plan = planRepository.getActivePlan()
            val phase = plan?.let { planRepository.getCurrentPhase(it.id, today) }

            _commitmentEditor.value = CommitmentDraft(
                title = phase?.let { "${it.name}完成率不低于 85%" } ?: "",
                phaseId = phase?.id,
                // 承诺从今天开始，不追溯已经过去的日子——回头改不了的成绩不该计入
                startDate = maxOf(today, phase?.startDate ?: today),
                endDate = phase?.endDate ?: today.plusDays(29),
            )
        }
    }

    fun updateDraft(transform: (CommitmentDraft) -> CommitmentDraft) {
        _commitmentEditor.update { it?.let(transform)?.copy(error = null) }
    }

    /** 选阶段：把区间和默认文案一起带过去。选「自定义」传 null。 */
    fun pickPhase(phaseId: String?) {
        val phase = _state.value.phases.firstOrNull { it.id == phaseId }
        val today = today()
        updateDraft { draft ->
            if (phase == null) {
                draft.copy(phaseId = null)
            } else {
                draft.copy(
                    phaseId = phase.id,
                    startDate = maxOf(today, phase.startDate),
                    endDate = phase.endDate,
                    title = draft.title.ifBlank {
                        "${phase.name}完成率不低于 ${draft.targetRatePercent}%"
                    },
                )
            }
        }
    }

    fun dismissCommitmentEditor() {
        _commitmentEditor.value = null
    }

    fun saveCommitment() {
        val draft = _commitmentEditor.value ?: return
        val invalid = when {
            draft.title.isBlank() -> "写一句承诺文案，将来回看才知道当初约的是什么。"
            !draft.targetValid -> "目标完成率要在 1% ~ 100% 之间。"
            !draft.rewardValid -> "奖励积分要在 0 ~ 9999 之间。"
            !draft.rangeValid -> "结束日期不能早于开始日期。"
            else -> null
        }
        if (invalid != null) {
            _commitmentEditor.update { it?.copy(error = invalid) }
            return
        }

        viewModelScope.launch {
            gamification.saveCommitment(
                CommitmentEntity(
                    title = draft.title.trim(),
                    phaseId = draft.phaseId,
                    startDate = draft.startDate,
                    endDate = draft.endDate,
                    targetRatePercent = draft.targetRatePercent,
                    metric = draft.metric,
                    rewardPoints = draft.rewardPoints,
                    customReward = draft.customReward.trim(),
                    note = draft.note.trim(),
                ),
            )
            _commitmentEditor.value = null
        }
    }

    /** 主动放弃。不扣分，只是如实记下没走完。 */
    fun abandonCommitment(commitment: CommitmentEntity) {
        viewModelScope.launch {
            gamification.abandonCommitment(commitment, today())
        }
    }

    /** 删除历史记录。 */
    fun deleteCommitment(id: String) {
        viewModelScope.launch { gamification.deleteCommitment(id) }
    }
}
