package com.ysdoing.notioncal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ysdoing.notioncal.core.data.AppFailure
import com.ysdoing.notioncal.core.data.SettingsTab
import com.ysdoing.notioncal.feature.dday.DdayRoute
import com.ysdoing.notioncal.feature.diagnostics.BatteryGuideCard
import com.ysdoing.notioncal.feature.diagnostics.DiagnosticsRoute
import com.ysdoing.notioncal.feature.habit.HabitRoute
import com.ysdoing.notioncal.feature.ledger.LedgerRoute
import com.ysdoing.notioncal.feature.month.MonthRoute
import com.ysdoing.notioncal.feature.month.SearchRoute
import com.ysdoing.notioncal.feature.viewer.ViewerRoute
import com.ysdoing.notioncal.settings.AddDatabaseRoute
import com.ysdoing.notioncal.settings.AddViewRoute
import com.ysdoing.notioncal.settings.SettingsRoute
import com.ysdoing.notioncal.settings.settingsRoute
import java.time.LocalDate

private object Routes {
    const val MONTH = "month"
    /** ST-04 설정. `?tab=<id>`가 있으면 그 카테고리로 연다(없으면 첫 카테고리 규칙). */
    const val SETTINGS = "settings?tab={tab}"
    const val ADD_DATABASE = "settings/add"
    const val ADD_VIEW = "settings/add-view"

    /** NS-09 다시 연결: 그 DB의 링크(ID)와 data source를 미리 채운다. */
    const val RECONNECT = "settings/add/{db}/{ds}"
    fun reconnect(databaseId: String, dataSourceId: String) = "settings/add/$databaseId/$dataSourceId"
    const val DIAGNOSTICS = "diagnostics"
    const val SEARCH = "search"
    const val DDAY = "settings/dday"

    /** VR-02 뷰어('더 보기' 전체 화면). */
    const val VIEWER = "view/{viewId}"
    fun viewer(viewId: String) = "view/$viewId"
}

/**
 * [openDate]: 위젯에서 누른 날짜. 설정 화면에 있었더라도 월 화면으로 돌아와 그 날짜를 연다.
 * [safeMode]: 앞선 실행이 연달아 첫 화면 전에 끝났다. 월 화면 대신 진단 화면으로 시작한다(PF-10).
 * [failure]: 실행 중 DB를 열지 못하면 진단 화면으로 보낸다(PF-10).
 * [openTab]: 위젯에서 연 하단 탭(습관 위젯 → 습관 탭).
 */
@Composable
fun NotionCalNavHost(
    openDate: LocalDate?,
    onOpenDateHandled: () -> Unit,
    safeMode: Boolean,
    failure: AppFailure?,
    openTab: HomeTab? = null,
    onOpenTabHandled: () -> Unit = {},
    openDday: Boolean = false,
    onOpenDdayHandled: () -> Unit = {},
    openView: String? = null,
    onOpenViewHandled: () -> Unit = {},
) {
    val nav = rememberNavController()
    var tab by rememberSaveable { mutableStateOf(HomeTab.CALENDAR) }
    // 위젯 날짜를 누르면 달력 탭에서 그 날짜를 연다.
    LaunchedEffect(openDate) {
        if (openDate != null) tab = HomeTab.CALENDAR
    }
    LaunchedEffect(openTab) {
        if (openTab != null) {
            tab = openTab
            nav.popBackStack(Routes.MONTH, inclusive = false)
            onOpenTabHandled()
        }
    }
    // MV-10: 검색 결과를 누르면 월 화면으로 돌아와 그 날짜를 연다(위젯 날짜와 같은 길).
    var searchDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(openDate) {
        if (openDate != null) nav.popBackStack(Routes.MONTH, inclusive = false)
    }
    LaunchedEffect(openDday) {
        if (openDday) {
            nav.navigate(Routes.DDAY) { launchSingleTop = true }
            onOpenDdayHandled()
        }
    }
    LaunchedEffect(openView) {
        if (openView != null) {
            nav.navigate(Routes.viewer(openView)) { launchSingleTop = true }
            onOpenViewHandled()
        }
    }
    LaunchedEffect(failure) {
        if (failure != null && nav.currentDestination?.route != Routes.DIAGNOSTICS) {
            nav.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true }
        }
    }
    NavHost(navController = nav, startDestination = if (safeMode) Routes.DIAGNOSTICS else Routes.MONTH) {
        composable(Routes.MONTH) {
            val openSettings = { nav.navigate(settingsRoute()) { launchSingleTop = true } }
            val openConnection = { nav.navigate(settingsRoute(SettingsTab.CONNECTION)) { launchSingleTop = true } }
            val openDatabase = { nav.navigate(settingsRoute(SettingsTab.DATABASE)) { launchSingleTop = true } }
            HomeTabs(
                tab = tab,
                onTabChange = { tab = it },
                calendar = {
                    MonthRoute(
                        onOpenSettings = openSettings,
                        onOpenConnectionSettings = openConnection,
                        onOpenDatabaseSettings = openDatabase,
                        openDate = openDate ?: searchDate,
                        onOpenDateHandled = {
                            if (openDate != null) onOpenDateHandled() else searchDate = null
                        },
                        onOpenSearch = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } },
                        onReconnect = { db, ds -> nav.navigate(Routes.reconnect(db, ds)) { launchSingleTop = true } },
                        topBanner = { BatteryGuideCard() },
                    )
                },
                habit = { HabitRoute(onOpenSettings = openSettings, onOpenDatabaseSettings = openDatabase) },
                ledger = { LedgerRoute(onOpenSettings = openSettings, onOpenDatabaseSettings = openDatabase) },
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
        composable(Routes.SETTINGS, arguments = listOf(navArgument("tab") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            SettingsRoute(
                startTab = SettingsTab.fromId(entry.arguments?.getString("tab")),
                onBack = { nav.popBackStack() },
                onAddDatabase = { nav.navigate(Routes.ADD_DATABASE) },
                onAddView = { nav.navigate(Routes.ADD_VIEW) },
                onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
                onOpenDday = { nav.navigate(Routes.DDAY) { launchSingleTop = true } },
            )
        }
        composable(Routes.VIEWER) {
            ViewerRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.DDAY) {
            DdayRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.ADD_VIEW) {
            AddViewRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.ADD_DATABASE) {
            AddDatabaseRoute(onDone = { nav.popBackStack() })
        }
        composable(Routes.RECONNECT) { entry ->
            val db = entry.arguments?.getString("db")
            val ds = entry.arguments?.getString("ds")
            AddDatabaseRoute(onDone = { nav.popBackStack() }, reconnect = if (db != null && ds != null) db to ds else null)
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
