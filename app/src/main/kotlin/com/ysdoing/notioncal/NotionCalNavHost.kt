package com.ysdoing.notioncal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ysdoing.notioncal.core.data.AppFailure
import com.ysdoing.notioncal.feature.diagnostics.BatteryGuideCard
import com.ysdoing.notioncal.feature.diagnostics.DiagnosticsRoute
import com.ysdoing.notioncal.feature.month.MonthRoute
import com.ysdoing.notioncal.feature.month.SearchRoute
import com.ysdoing.notioncal.settings.AddDatabaseRoute
import com.ysdoing.notioncal.settings.SettingsRoute
import java.time.LocalDate

private object Routes {
    const val MONTH = "month"
    const val SETTINGS = "settings"
    const val ADD_DATABASE = "settings/add"
    const val DIAGNOSTICS = "diagnostics"
    const val SEARCH = "search"
}

/**
 * [openDate]: 위젯에서 누른 날짜. 설정 화면에 있었더라도 월 화면으로 돌아와 그 날짜를 연다.
 * [safeMode]: 앞선 실행이 연달아 첫 화면 전에 끝났다. 월 화면 대신 진단 화면으로 시작한다(PF-10).
 * [failure]: 실행 중 DB를 열지 못하면 진단 화면으로 보낸다(PF-10).
 */
@Composable
fun NotionCalNavHost(
    openDate: LocalDate?,
    onOpenDateHandled: () -> Unit,
    safeMode: Boolean,
    failure: AppFailure?,
) {
    val nav = rememberNavController()
    // MV-10: 검색 결과를 누르면 월 화면으로 돌아와 그 날짜를 연다(위젯 날짜와 같은 길).
    var searchDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(openDate) {
        if (openDate != null) nav.popBackStack(Routes.MONTH, inclusive = false)
    }
    LaunchedEffect(failure) {
        if (failure != null && nav.currentDestination?.route != Routes.DIAGNOSTICS) {
            nav.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true }
        }
    }
    NavHost(navController = nav, startDestination = if (safeMode) Routes.DIAGNOSTICS else Routes.MONTH) {
        composable(Routes.MONTH) {
            MonthRoute(
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                openDate = openDate ?: searchDate,
                onOpenDateHandled = {
                    if (openDate != null) onOpenDateHandled() else searchDate = null
                },
                onOpenSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } },
                topBanner = { BatteryGuideCard() },
            )
        }
        composable(Routes.SEARCH) {
            SearchRoute(
                onBack = { nav.popBackStack() },
                onPick = { event ->
                    searchDate = event.startDate
                    nav.popBackStack(Routes.MONTH, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { nav.popBackStack() },
                onAddDatabase = { nav.navigate(Routes.ADD_DATABASE) },
                onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
            )
        }
        composable(Routes.ADD_DATABASE) {
            AddDatabaseRoute(onDone = { nav.popBackStack() })
        }
        composable(Routes.DIAGNOSTICS) {
            val canGoBack = nav.previousBackStackEntry != null
            DiagnosticsRoute(
                onBack = if (canGoBack) ({ nav.popBackStack() }) else null,
                onContinue = {
                    nav.navigate(Routes.MONTH) {
                        popUpTo(nav.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
