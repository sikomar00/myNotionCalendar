package com.ysdoing.notioncal.feature.quicksheet

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate

/**
 * 위젯 위에 뜨는 반투명 퀵 시트(WI-01·02·05). 앱 화면을 열지 않고 추가·체크·편집한다.
 * 바깥(어두운 부분)을 누르면 닫힌다. 키보드는 매니페스트(stateVisible)와 포커스 요청으로 바로 뜬다.
 */
@AndroidEntryPoint
class QuickSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val openedAt = SystemClock.elapsedRealtime()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = startFrom(intent.getStringExtra(AppIntents.EXTRA_MODE), intent.getStringExtra(AppIntents.EXTRA_DATE), intent.getStringExtra(AppIntents.EXTRA_PAGE_ID))
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }, // 시트 안 터치는 닫지 않음
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        tonalElevation = 6.dp,
                    ) {
                        QuickSheetContent(
                            start = start,
                            viewModel = hiltViewModel(),
                            onFinished = { finish() },
                            modifier = Modifier.navigationBarsPadding().padding(top = 16.dp),
                        )
                    }
                }
                // WI-01 지표(탭 → 시트 0.5초): 첫 프레임까지 걸린 시간을 남긴다.
                LaunchedEffect(Unit) { Log.d(TAG, "퀵 시트 표시: ${SystemClock.elapsedRealtime() - openedAt}ms") }
            }
        }
    }

    companion object {
        const val TAG = "NotionCalQuickSheet"

        fun startFrom(mode: String?, date: String?, pageId: String?): QuickSheetStart {
            val day = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(APP_ZONE)
            return when {
                mode == AppIntents.MODE_EDIT && pageId != null -> QuickSheetStart.Edit(pageId)
                mode == AppIntents.MODE_ADD -> QuickSheetStart.Add(day)
                else -> QuickSheetStart.Day(day)
            }
        }
    }
}
