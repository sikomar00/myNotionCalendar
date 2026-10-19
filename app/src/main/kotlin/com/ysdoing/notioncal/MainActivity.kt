package com.ysdoing.notioncal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.designsystem.NotionCalTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import com.ysdoing.notioncal.core.sync.HolidayScheduler
import com.ysdoing.notioncal.reminders.ReminderScheduler

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var syncRequester: SyncRequester

    @Inject
    lateinit var health: AppHealth

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var holidayScheduler: HolidayScheduler

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    /** null: 실행 표지를 확인하는 중(수 ms), true: 안전 모드로 시작(PF-10), false: 정상 시작. */
    private var safeMode by mutableStateOf<Boolean?>(null)

    /** 위젯 날짜 탭으로 받은 날짜(AppIntents.EXTRA_DATE). 화면이 처리하면 비운다. */
    private var openDate by mutableStateOf<LocalDate?>(null)

    /** 습관·가계부 위젯에서 열었으면 그 탭(AppIntents.EXTRA_TAB). */
    private var openTab by mutableStateOf<HomeTab?>(null)

    /** EX-12 `notioncal://dday`(디데이 목록 위젯 '+' 포함): 설정의 디데이 화면을 연다. */
    private var openDday by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 37은 엣지 투 엣지가 기본이므로 인셋은 Scaffold로 처리한다(S25-08).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val firstLaunch = savedInstanceState == null
        if (firstLaunch) {
            openDate = dateFrom(intent)
            openTab = tabFrom(intent)
            applyLink(intent)
        }
        // PF-10: 실행 표지는 IO에서 확인한다. 프로세스 복원(회전·활동 유지 안 함)은 새 실행으로 세지 않는다.
        if (firstLaunch) lifecycleScope.launch { safeMode = health.onLaunchStarted() } else safeMode = false
        setContent {
            val failure by health.failure.collectAsStateWithLifecycle()
            // 설정을 읽기 전에는 기본값(Material You 켬)으로 그린다.
            val dynamicColor by remember { settings.settings.map { it.dynamicColor } }.collectAsStateWithLifecycle(false)
            NotionCalTheme(dynamicColor) {
                val safe = safeMode
                if (safe != null) {
                    NotionCalNavHost(
                        openDate = openDate,
                        onOpenDateHandled = { openDate = null },
                        safeMode = safe,
                        failure = failure,
                        openTab = openTab,
                        onOpenTabHandled = { openTab = null },
                        openDday = openDday,
                        onOpenDdayHandled = { openDday = false },
                    )
                }
            }
            // PF-01: 로컬 데이터로 첫 프레임을 그린 뒤에 동기화를 요청한다. 정상 첫 화면을 그렸으면 실행 표지를 지운다.
            LaunchedEffect(safeMode) {
                if (safeMode != false) return@LaunchedEffect
                withFrameNanos { }
                if (firstLaunch) {
                    health.onFirstFrame()
                    syncRequester.request(SyncTrigger.APP_OPEN)
                    // EX-02: 공휴일은 하루 한 번 폰의 공휴일 캘린더에서 다시 읽는다.
                    holidayScheduler.requestIfStale()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // EX-02: 앱 설정에서 캘린더 권한을 거둔 뒤 돌아오면 공휴일을 숨긴다(프로세스 복원 실행 포함).
        lifecycleScope.launch {
            holidayScheduler.dropIfPermissionRevoked()
            reminderScheduler.dropIfPermissionRevoked() // EX-01
        }
    }

    /** 앱이 떠 있는 상태에서 위젯 날짜를 누르면 singleTop이라 여기로 온다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dateFrom(intent)?.let { openDate = it }
        tabFrom(intent)?.let { openTab = it }
        applyLink(intent)
    }

    /** EX-12: VIEW 링크만 처리한다. 화면 열기만 하고 쓰기는 하지 않는다. */
    private fun applyLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        when (val link = DeepLinks.parse(intent.dataString)) {
            DeepLink.Today -> openDate = LocalDate.now(APP_ZONE)
            DeepLink.Habit -> openTab = HomeTab.HABIT
            DeepLink.Ledger -> openTab = HomeTab.LEDGER
            is DeepLink.Date -> openDate = link.date
            DeepLink.Dday -> openDday = true
            null -> Unit
        }
    }

    private fun tabFrom(intent: Intent?): HomeTab? = when (intent?.getStringExtra(AppIntents.EXTRA_TAB)) {
        AppIntents.TAB_HABIT -> HomeTab.HABIT
        AppIntents.TAB_LEDGER -> HomeTab.LEDGER
        else -> null
    }

    private fun dateFrom(intent: Intent?): LocalDate? =
        intent?.getStringExtra(AppIntents.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
