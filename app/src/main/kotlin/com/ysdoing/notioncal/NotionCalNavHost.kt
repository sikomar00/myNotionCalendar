package com.ysdoing.notioncal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ysdoing.notioncal.feature.month.MonthRoute
import com.ysdoing.notioncal.settings.AddDatabaseRoute
import com.ysdoing.notioncal.settings.SettingsRoute
import java.time.LocalDate

private object Routes {
    const val MONTH = "month"
    const val SETTINGS = "settings"
    const val ADD_DATABASE = "settings/add"
}

/** [openDate]: 위젯에서 누른 날짜. 설정 화면에 있었더라도 월 화면으로 돌아와 그 날짜를 연다. */
@Composable
fun NotionCalNavHost(openDate: LocalDate?, onOpenDateHandled: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openDate) {
        if (openDate != null) nav.popBackStack(Routes.MONTH, inclusive = false)
    }
    NavHost(navController = nav, startDestination = Routes.MONTH) {
        composable(Routes.MONTH) {
            MonthRoute(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                openDate = openDate,
                onOpenDateHandled = onOpenDateHandled,
            )
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
