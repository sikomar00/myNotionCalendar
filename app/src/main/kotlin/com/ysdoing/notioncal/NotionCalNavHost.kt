package com.ysdoing.notioncal

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ysdoing.notioncal.feature.month.MonthRoute
import com.ysdoing.notioncal.settings.AddDatabaseRoute
import com.ysdoing.notioncal.settings.SettingsRoute

private object Routes {
    const val MONTH = "month"
    const val SETTINGS = "settings"
    const val ADD_DATABASE = "settings/add"
}

@Composable
fun NotionCalNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MONTH) {
        composable(Routes.MONTH) {
            MonthRoute(onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { nav.popBackStack() },
                onAddDatabase = { nav.navigate(Routes.ADD_DATABASE) },
            )
        }
        composable(Routes.ADD_DATABASE) {
            AddDatabaseRoute(onDone = { nav.popBackStack() })
        }
    }
}
