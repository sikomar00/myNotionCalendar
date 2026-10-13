package com.ysdoing.notioncal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var syncRequester: SyncRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 37은 엣지 투 엣지가 기본이므로 인셋은 Scaffold로 처리한다(S25-08).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val firstLaunch = savedInstanceState == null
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                NotionCalNavHost()
            }
            // PF-01: 로컬 데이터로 첫 프레임을 그린 뒤에 동기화를 요청한다.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (firstLaunch) syncRequester.request(SyncTrigger.APP_OPEN)
            }
        }
    }
}
