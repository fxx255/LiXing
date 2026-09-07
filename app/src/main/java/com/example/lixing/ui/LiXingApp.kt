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
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevelRoutes = TopLevelDestination.entries.map { it.route }.toSet()
    val showBottomBar = currentDestination?.route in topLevelRoutes

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
