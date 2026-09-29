package com.example.lixing.domain.planning

import com.example.lixing.data.local.entity.DailyTaskEntity
import com.example.lixing.data.local.entity.ScheduledTaskEntity
import com.example.lixing.data.local.entity.SubjectEntity
import com.example.lixing.data.local.entity.TimeSlotEntity
import com.example.lixing.domain.model.TaskStatus
import java.time.LocalDate
import java.util.UUID

/** Combines recurring occurrences with confirmed date-specific additions and overrides. */
object DailyScheduleResolver {
    fun resolve(
        date: LocalDate,
        recurring: List<DailyTaskEntity>,
        scheduled: List<ScheduledTaskEntity>,
        slots: Map<String, TimeSlotEntity>,
        subjects: Map<String, SubjectEntity>,
    ): List<DailyTaskEntity> {
        val overrides = scheduled.filter { it.studyDate == date && it.sourceTemplateId != null }
            .associateBy { it.sourceTemplateId }
        val result = ArrayList<DailyTaskEntity>(recurring.size + scheduled.size)
        val handled = HashSet<String>()
        for (task in recurring) {
            val override = task.templateId?.let(overrides::get)
            if (override == null || override.state == "DEFAULT") result += task
            else if (override.state == "OVERRIDE") {
                handled += override.id
                fromSchedule(override, slots, subjects, task)?.let(result::add)
            } else handled += override.id
        }
        for (entry in scheduled) {
            if (entry.id in handled) continue
            if (entry.sourceTemplateId == null && entry.state == "ACTIVE" ||
                entry.sourceTemplateId != null && entry.state == "OVERRIDE"
            ) fromSchedule(entry, slots, subjects, null)?.let(result::add)
        }
        return result.sortedWith(compareBy({ it.slotSortOrder }, { it.scheduledStart ?: it.slotStart }, { it.sortOrder }, { it.id }))
    }

    private fun fromSchedule(
        entry: ScheduledTaskEntity,
        slots: Map<String, TimeSlotEntity>,
        subjects: Map<String, SubjectEntity>,
        base: DailyTaskEntity?,
    ): DailyTaskEntity? {
        val slot = slots[entry.timeSlotId] ?: return null
        val subject = subjects[entry.subjectId] ?: return null
        val id = base?.id ?: if (entry.sourceTemplateId != null) {
            UUID.nameUUIDFromBytes("lixing:daily:${entry.studyDate.toEpochDay()}:${entry.sourceTemplateId}".toByteArray(Charsets.UTF_8)).toString()
        } else UUID.nameUUIDFromBytes("lixing:dated:${entry.id}".toByteArray(Charsets.UTF_8)).toString()
        val snapshot = DailyTaskEntity(
            id = id,
            date = entry.studyDate,
            // A source template may have been disabled or deleted after this dated override
            // was confirmed. Keep the scheduled identity without inserting a broken FK.
            templateId = entry.sourceTemplateId.takeIf { base != null },
            planId = entry.planId,
            scheduleId = entry.id,
            subjectId = subject.id,
            subjectName = subject.name,
            subjectColorArgb = subject.colorArgb,
            timeSlotId = slot.id,
            slotName = slot.name,
            slotStart = slot.startTime,
            slotEnd = slot.endTime,
            slotSortOrder = slot.sortOrder,
            slotRequiredTaskCount = slot.requiredTaskCount,
            title = entry.title,
            taskType = entry.taskType,
            targetType = entry.targetType,
            targetValue = entry.targetValue,
            plannedMinutes = entry.plannedMinutes,
            contentJson = entry.contentJson,
            scheduledStart = entry.startTime,
            scheduledEnd = entry.endTime,
            baselineMinutes = entry.baselineMinutes,
            baselineValue = entry.baselineValue,
            isKeystone = base?.isKeystone ?: false,
            note = entry.note,
            sortOrder = base?.sortOrder ?: 0,
            status = TaskStatus.PENDING,
        )
        return snapshot
    }
}
