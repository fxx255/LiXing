package com.example.lixing.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.lixing.ui.screen.english.EnglishReviewScreen
import com.example.lixing.ui.screen.placeholder.PlaceholderScreen
import com.example.lixing.ui.screen.plan.PlanScreen
import com.example.lixing.ui.screen.plan.manage.PlanManageScreen
import com.example.lixing.ui.screen.plan.edit.TimeSlotEditScreen
import com.example.lixing.ui.screen.plan.edit.SubjectEditScreen
import com.example.lixing.ui.screen.plan.edit.TaskTemplateEditScreen
import com.example.lixing.ui.screen.plan.edit.PhaseEditScreen
import com.example.lixing.ui.screen.today.TodayScreen
import com.example.lixing.ui.screen.focus.FocusScreen
import com.example.lixing.ui.screen.mine.MineScreen
import com.example.lixing.ui.screen.onboarding.OnboardingScreen
import com.example.lixing.ui.screen.stats.StatsScreen
import com.example.lixing.ui.screen.history.HistoryScreen
import com.example.lixing.ui.screen.settings.SettingsScreen
import com.example.lixing.ui.screen.reports.ReportsScreen
import com.example.lixing.ui.screen.meal.MealScreen
import com.example.lixing.ui.screen.english.EnglishNotebookScreen
import com.example.lixing.ui.screen.assistant.AssistantScreen

/**
 * 全局导航图。一级页面之间用淡入淡出（避免底部导航切换时的横向抖动），
 * 二级页面用横向滑入，符合层级感。
 */
@Composable
fun LiXingNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(180)) },
    ) {
        // ---- 一级页面（占位，后续步骤逐个替换为真实实现） ----
        composable(Routes.TODAY) {
            TodayScreen(
                onNavigateToPlan = { navController.navigate(Routes.PLAN) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenMeals = { navController.navigate(Routes.MEALS) },
                onOpenEnglish = { navController.navigate(Routes.ENGLISH_NOTEBOOK) },
                onOpenAssistant = { navController.navigate(Routes.ASSISTANT) },
            )
        }
        composable(Routes.PLAN) {
            PlanScreen(onOpenManage = { navController.navigate(Routes.PLAN_MANAGE) })
        }
        composable(Routes.FOCUS) {
            FocusScreen(
                onOpenEnglish = { navController.navigate(Routes.ENGLISH_NOTEBOOK) },
                onOpenAssistant = { navController.navigate(Routes.ASSISTANT) },
            )
        }
        composable(Routes.STATS) { StatsScreen() }
        composable(Routes.MINE) {
            MineScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenReports = { navController.navigate(Routes.REPORTS) },
            )
        }

        // ---- 二级页面 ----
        secondLevel(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        secondLevel(Routes.ACHIEVEMENTS) { PlaceholderScreen("成就") }
        secondLevel(Routes.REPORTS) {
            ReportsScreen(onBack = { navController.popBackStack() })
        }
        secondLevel(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
        secondLevel(Routes.MEALS) {
            MealScreen(onBack = { navController.popBackStack() })
        }
        secondLevel(Routes.ENGLISH_NOTEBOOK) {
            EnglishNotebookScreen(
                onBack = { navController.popBackStack() },
                onOpenReview = { navController.navigate(Routes.ENGLISH_REVIEW) },
            )
        }
        secondLevel(Routes.ENGLISH_REVIEW) {
            EnglishReviewScreen(
                onBack = { navController.popBackStack() },
                onOpenNotebook = {
                    navController.navigate(Routes.ENGLISH_NOTEBOOK) {
                        popUpTo(Routes.ENGLISH_NOTEBOOK) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
        secondLevel(Routes.ASSISTANT) {
            AssistantScreen(onBack = { navController.popBackStack() })
        }
        secondLevel(Routes.PERMISSION_GUIDE) { PlaceholderScreen("权限引导") }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(Routes.TODAY) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        secondLevel(Routes.PLAN_MANAGE) {
            PlanManageScreen(
                onEditPhase = { navController.navigate(Routes.phaseEdit(it)) },
                onEditSubject = { navController.navigate(Routes.subjectEdit(it)) },
                onEditSlot = { navController.navigate(Routes.timeSlotEdit(it)) },
                onEditTemplate = { navController.navigate(Routes.taskTemplateEdit(it)) },
            )
        }

        secondLevelWithLongArg(Routes.TIME_SLOT_EDIT, Routes.ARG_SLOT_ID) {
            TimeSlotEditScreen(onBack = { navController.popBackStack() })
        }
        secondLevelWithLongArg(Routes.SUBJECT_EDIT, Routes.ARG_SUBJECT_ID) {
            SubjectEditScreen(onBack = { navController.popBackStack() })
        }
        secondLevelWithLongArg(Routes.TASK_TEMPLATE_EDIT, Routes.ARG_TEMPLATE_ID) {
            TaskTemplateEditScreen(onBack = { navController.popBackStack() })
        }
        secondLevelWithLongArg(Routes.PHASE_EDIT, Routes.ARG_PHASE_ID) {
            PhaseEditScreen(onBack = { navController.popBackStack() })
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.secondLevel(
    route: String,
    content: @Composable () -> Unit,
) {
    composable(
        route = route,
        enterTransition = { slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220)) },
        exitTransition = { slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(220)) },
    ) { content() }
}

private fun androidx.navigation.NavGraphBuilder.secondLevelWithLongArg(
    route: String,
    argName: String,
    content: @Composable (String) -> Unit,
) {
    composable(
        route = route,
        arguments = listOf(navArgument(argName) { type = NavType.StringType }),
        enterTransition = { slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220)) },
        exitTransition = { slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(220)) },
    ) { entry ->
        content(entry.arguments?.getString(argName) ?: "-1")
    }
}
