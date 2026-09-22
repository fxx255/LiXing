package com.example.lixing.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.lixing.ui.navigation.LiXingNavHost
import com.example.lixing.ui.navigation.Routes
import com.example.lixing.ui.navigation.TopLevelDestination

/**
 * 应用外壳：底部导航 + NavHost。
 * 底部栏只在一级页面显示，进入二级页面自动隐藏，给内容更多纵向空间。
 */
@Composable
fun LiXingApp(
    startDestination: String = Routes.TODAY,
    navController: NavHostController = rememberNavController(),
    /**
     * 从通知点进来时要直达的**一次性导航事件**。
     *
     * 为什么用「事件 + 自增 id」而不是一个 route 字符串：
     * - 同一 route 连续点两次，`LaunchedEffect(route)` 不会重跑（值没变）；
     * - 目标还要带上**具体会话 id**，否则只到助手页、仍停在默认会话。
     *
     * 由 [com.example.lixing.MainActivity] 解析 intent 后传进来，在这里消费一次。
     */
    notificationNavigation: com.example.lixing.MainActivity.NotificationNavigation? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevelRoutes = TopLevelDestination.entries.map { it.route }.toSet()
    val showBottomBar = currentDestination?.route in topLevelRoutes

    // 通知点进来的导航：**按事件 id 触发**。
    // 用 id 而不是 route 作为 key：连续两次点击同一条 route 时，
    // route 值没变、LaunchedEffect 不会重跑，用户会以为「点了没反应」。
    androidx.compose.runtime.LaunchedEffect(notificationNavigation?.id) {
        val navigation = notificationNavigation ?: return@LaunchedEffect
        if (navigation.route == Routes.TODAY) return@LaunchedEffect
        runCatching {
            // 带会话 id 时走带参路由，助手页据此打开对应会话。
            val target = if (navigation.route == Routes.ASSISTANT && !navigation.conversationId.isNullOrBlank()) {
                Routes.assistantConversation(navigation.conversationId!!)
            } else {
                navigation.route
            }
            navController.navigate(target) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == dest.route } == true
                        val label = stringResource(dest.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(dest.route) {
                                        // 一级页面之间是平级切换：回到图起点、不叠栈、复用已有实例
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = label,
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        LiXingNavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}
