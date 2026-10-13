package com.ysdoing.notioncal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var syncRequester: SyncRequester

    /** 위젯 날짜 탭으로 받은 날짜(AppIntents.EXTRA_DATE). 화면이 처리하면 비운다. */
    private var openDate by mutableStateOf<LocalDate?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 37은 엣지 투 엣지가 기본이므로 인셋은 Scaffold로 처리한다(S25-08).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val firstLaunch = savedInstanceState == null
        if (firstLaunch) openDate = dateFrom(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                NotionCalNavHost(openDate = openDate, onOpenDateHandled = { openDate = null })
            }
            // PF-01: 로컬 데이터로 첫 프레임을 그린 뒤에 동기화를 요청한다.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (firstLaunch) syncRequester.request(SyncTrigger.APP_OPEN)
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
