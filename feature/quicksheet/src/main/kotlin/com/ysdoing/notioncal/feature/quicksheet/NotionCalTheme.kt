package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 앱과 퀵 시트가 함께 쓰는 테마(S25-10). 시스템 다크 모드를 따르고, [dynamicColor]면 배경화면 색(Material You)을 쓴다.
 * minSdk 31이라 동적 색은 항상 쓸 수 있다. 일정 색(노션 색)은 테마와 무관하다.
 */
@Composable
fun NotionCalTheme(dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && dark -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
