package com.example.lixing.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.lixing.R

/** 路由常量。集中在一处，避免各处硬编码字符串拼错。 */
object Routes {
    const val TODAY = "today"
    const val PLAN = "plan"
    const val FOCUS = "focus"
    const val STATS = "stats"
    const val MINE = "mine"

    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val PLAN_MANAGE = "plan/manage"
    const val HISTORY = "history"
    const val MEALS = "meals"
    const val ENGLISH_NOTEBOOK = "english/notebook"
    const val ENGLISH_REVIEW = "english/review"
    const val ASSISTANT = "assistant"
    const val ACHIEVEMENTS = "achievements"
    const val REPORTS = "reports"
    const val PERMISSION_GUIDE = "permission_guide"

    /** 时段编辑：slotId = -1 表示新建。 */
    const val TIME_SLOT_EDIT = "plan/slot/{slotId}"
    fun timeSlotEdit(slotId: String) = "plan/slot/$slotId"

    /** 科目编辑：subjectId = -1 表示新建。 */
    const val SUBJECT_EDIT = "plan/subject/{subjectId}"
    fun subjectEdit(subjectId: String) = "plan/subject/$subjectId"

    /** 任务模板编辑：templateId = -1 表示新建。 */
    const val TASK_TEMPLATE_EDIT = "plan/template/{templateId}"
    fun taskTemplateEdit(templateId: String) = "plan/template/$templateId"

    /** 阶段编辑。 */
    const val PHASE_EDIT = "plan/phase/{phaseId}"
    fun phaseEdit(phaseId: String) = "plan/phase/$phaseId"

    const val ARG_SLOT_ID = "slotId"
    const val ARG_SUBJECT_ID = "subjectId"
    const val ARG_TEMPLATE_ID = "templateId"
    const val ARG_PHASE_ID = "phaseId"
}

/** 底部导航的 5 个一级页面。 */
enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    TODAY(Routes.TODAY, R.string.nav_today, Icons.Filled.CheckCircle, Icons.Outlined.CheckCircle),
    PLAN(Routes.PLAN, R.string.nav_plan, Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    FOCUS(Routes.FOCUS, R.string.nav_focus, Icons.Filled.Timer, Icons.Outlined.Timer),
    STATS(Routes.STATS, R.string.nav_stats, Icons.Filled.BarChart, Icons.Outlined.BarChart),
    MINE(Routes.MINE, R.string.nav_mine, Icons.Filled.Person, Icons.Outlined.Person),
}
