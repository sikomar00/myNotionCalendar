package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek

/*
 * 요일·공휴일 글자색. 작은 글자(공휴일 이름 9sp 등)도 읽히도록 다크·라이트 배경 모두 4.5:1 이상으로 고른 값이다
 * (다크 #191919 위 빨강 6.3·파랑 6.3, 라이트 #FFFFFF 위 빨강 5.6·파랑 5.8). 예전 #E03E3E·#2F80ED는 3.9~4.3이었다.
 */

/** 오늘 표시: 칠한 네모. 앱 아이콘(오늘 칸 체크)·위젯과 같은 모양으로 앱의 정체성을 맞춘다. */
internal val TodayShape = RoundedCornerShape(6.dp)

private val SundayDark = Color(0xFFFF6B6B)
private val SundayLight = Color(0xFFC62828)
private val SaturdayDark = Color(0xFF5B9BF8)
private val SaturdayLight = Color(0xFF1565C0)

/** 지금 화면 배경이 어두운지(Material You·다크 모드 모두 surface 색으로 판단). */
@Composable
@ReadOnlyComposable
private fun darkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** 일요일·공휴일 빨강. */
@Composable
@ReadOnlyComposable
internal fun sundayColor(): Color = if (darkSurface()) SundayDark else SundayLight

/** 토요일 파랑. */
@Composable
@ReadOnlyComposable
internal fun saturdayColor(): Color = if (darkSurface()) SaturdayDark else SaturdayLight

/** 요일 글자색: 일요일 빨강, 토요일 파랑, 평일은 null(기본 글자색). */
@Composable
@ReadOnlyComposable
internal fun dayOfWeekColor(day: DayOfWeek): Color? = when (day) {
    DayOfWeek.SUNDAY -> sundayColor()
    DayOfWeek.SATURDAY -> saturdayColor()
    else -> null
}

/**
 * 다른 달 날짜·공휴일 이름의 흐린 글자: 요일색 없이 기본 글자색 55%. 다크 4.9:1, 라이트 3.8:1로 3:1 이상을 지킨다
 * (같은 날짜가 제 달에도 나오는 보조 정보라 4.5:1 대신 3:1을 하한으로 둔다).
 */
internal const val OUTSIDE_MONTH_ALPHA = 0.55f
