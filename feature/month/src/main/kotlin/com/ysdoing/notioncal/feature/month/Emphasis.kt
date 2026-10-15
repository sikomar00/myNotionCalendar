package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors

/**
 * EX-11 강조 일정(예: 우선순위 '1st') 표시 규칙. 일정 색으로 칠한 칸에, 그 색과 대비되는 글자색(흰색·먹색)과 굵은 글씨를 쓴다.
 * 색만으로 구분하지 않도록 굵기를 함께 바꾼다(A11Y-03). 완료한 일정은 강조하지 않는다.
 */
internal fun CalendarEvent.emphasisTextColor(): Color = Color(NotionColors.onColor(colorHex))

/** 강조 일정이면 굵게, 아니면 기본 굵기(null). */
internal val CalendarEvent.emphasisWeight: FontWeight? get() = if (showsEmphasis) FontWeight.Bold else null

/**
 * 여러 날·종일 막대 배경: 강조 일정은 불투명, 보통 28%, 완료 12%. 완료 막대 위 흐린 글자(70%)가 4.5:1을 넘도록
 * 배경을 더 옅게 한다(28% 배경에 55% 글자는 3.2:1이었다).
 */
internal fun barBackground(event: CalendarEvent, color: Color): Color = when {
    event.showsEmphasis -> color
    event.done -> color.copy(alpha = 0.12f)
    else -> color.copy(alpha = 0.28f)
}

/** 막대 안 글자 투명도: 완료 70%, 그 밖 100%. */
internal fun barTextAlpha(event: CalendarEvent): Float = if (event.done) 0.7f else 1f

/** 목록(목록 보기·검색) 제목: 강조 일정이면 일정 색 둥근 칸으로 감싼다. */
internal fun Modifier.emphasisPill(event: CalendarEvent): Modifier =
    if (event.showsEmphasis) {
        clip(RoundedCornerShape(4.dp)).background(Color(NotionColors.argb(event.colorHex))).padding(horizontal = 6.dp, vertical = 1.dp)
    } else {
        this
    }
