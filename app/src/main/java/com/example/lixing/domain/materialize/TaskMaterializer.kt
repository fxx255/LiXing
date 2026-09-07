package com.example.lixing.domain.materialize

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.PhaseEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TaskTemplateEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.domain.model.TaskStatus
import java.time.LocalDate

/**
 * 任务物化：把模板展开成某一天的 [DailyTaskEntity] 列表。
 *
 * 纯函数——不写库、不读时钟。数据库层面的幂等由 (date, template_id) 唯一索引
 * 配合 INSERT OR IGNORE 保证，所以这里可以放心地重复调用。
 *
 * 快照策略：科目名/色、时段名/起止时刻都拷进任务里。用户明天改了时段时间，
 * 今天的记录仍然显示当时的要求，统计才不会失真。
 */
object TaskMaterializer {

    /**
     * @param date 目标学习日
     * @param templates 计划下全部启用的模板
     * @param slots 时段，按 id 索引
     * @param subjects 科目，按 id 索引
     * @param phases 阶段，按 id 索引
     * @param planStart 计划开始日（EVERY_N_DAYS 的兜底锚点）
     */
    fun materialize(
        date: LocalDate,
        templates: List<TaskTemplateEntity>,
        slots: Map<String, TimeSlotEntity>,
        subjects: Map<String, SubjectEntity>,
        phases: Map<String, PhaseEntity>,
        planStart: LocalDate,
    ): List<DailyTaskEntity> {
        val result = ArrayList<DailyTaskEntity>(templates.size)

        for (template in templates) {
            val slot = slots[template.timeSlotId] ?: continue
            val subject = subjects[template.subjectId] ?: continue
            // 归档的科目不再产出新任务，但历史记录仍在
            if (subject.isArchived) continue
            val phase = template.phaseId?.let { phases[it] }

            if (!TemplateEligibility.isEligible(template, slot, phase, date, planStart)) continue

            result += DailyTaskEntity(
                date = date,
                templateId = template.id,
                subjectId = subject.id,
                subjectName = subject.name,
                subjectColorArgb = subject.colorArgb,
                timeSlotId = slot.id,
                slotName = slot.name,
                slotStart = slot.startTime,
                slotEnd = slot.endTime,
                slotSortOrder = slot.sortOrder,
                slotRequiredTaskCount = slot.requiredTaskCount.coerceAtLeast(0),
                title = template.title,
                taskType = template.taskType,
                targetType = template.targetType,
                targetValue = template.targetValue.coerceAtLeast(1),
                isKeystone = template.isKeystone,
                note = template.note,
                sortOrder = template.sortOrder,
                status = TaskStatus.PENDING,
            )
        }

        // 按时段顺序、再按模板顺序排好，UI 直接用
        return result.sortedWith(
            compareBy({ it.slotSortOrder }, { it.slotStart }, { it.sortOrder }, { it.templateId }),
        )
    }
}
