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
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.feature.quicksheet.NotionCalTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var syncRequester: SyncRequester

    @Inject
    lateinit var health: AppHealth

    @Inject
    lateinit var settings: SettingsRepository

    /** null: 실행 표지를 확인하는 중(수 ms), true: 안전 모드로 시작(PF-10), false: 정상 시작. */
    private var safeMode by mutableStateOf<Boolean?>(null)

    /** 위젯 날짜 탭으로 받은 날짜(AppIntents.EXTRA_DATE). 화면이 처리하면 비운다. */
    private var openDate by mutableStateOf<LocalDate?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 37은 엣지 투 엣지가 기본이므로 인셋은 Scaffold로 처리한다(S25-08).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val firstLaunch = savedInstanceState == null
        if (firstLaunch) openDate = dateFrom(intent)
        // PF-10: 실행 표지는 IO에서 확인한다. 프로세스 복원(회전·활동 유지 안 함)은 새 실행으로 세지 않는다.
        if (firstLaunch) lifecycleScope.launch { safeMode = health.onLaunchStarted() } else safeMode = false
        setContent {
            val failure by health.failure.collectAsStateWithLifecycle()
            // 설정을 읽기 전에는 기본값(Material You 켬)으로 그린다.
            val dynamicColor by remember { settings.settings.map { it.dynamicColor } }.collectAsStateWithLifecycle(true)
            NotionCalTheme(dynamicColor) {
                val safe = safeMode
                if (safe != null) {
                    NotionCalNavHost(
                        openDate = openDate,
                        onOpenDateHandled = { openDate = null },
                        safeMode = safe,
                        failure = failure,
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
                }
            }
        }
    }

    /** 앱이 떠 있는 상태에서 위젯 날짜를 누르면 singleTop이라 여기로 온다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dateFrom(intent)?.let { openDate = it }
    }

    private fun dateFrom(intent: Intent?): LocalDate? =
        intent?.getStringExtra(AppIntents.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
