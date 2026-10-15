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
 * 막대·시간 블록 배경: 강조 일정은 불투명, 보통 28%, 완료 12%. 노션 20색 모두에서 보통 막대의 먹색 글자는
 * 라이트 7.5:1·다크 7.0:1, 완료 막대의 흐린 글자([DONE_TEXT_ALPHA])는 라이트 5.0:1·다크 6.8:1 이상이다.
 */
internal fun barBackground(event: CalendarEvent, color: Color): Color = when {
    event.showsEmphasis -> color
    event.done -> color.copy(alpha = 0.12f)
    else -> color.copy(alpha = 0.28f)
}

/**
 * 완료 일정 글자 투명도(✓·취소선과 함께 쓴다). 라이트 흰 바탕에서 55%는 3.2:1이라 75%로 올렸다
 * (라이트 5.6:1·다크 8.4:1, 완료 막대 위 라이트 5.0:1).
 */
internal const val DONE_TEXT_ALPHA = 0.75f

/** 막대 안 글자 투명도: 완료면 [DONE_TEXT_ALPHA], 그 밖 100%. */
internal fun barTextAlpha(event: CalendarEvent): Float = if (event.done) DONE_TEXT_ALPHA else 1f

/** 목록(목록 보기·검색) 제목: 강조 일정이면 일정 색 둥근 칸으로 감싼다. */
internal fun Modifier.emphasisPill(event: CalendarEvent): Modifier =
    if (event.showsEmphasis) {
        clip(RoundedCornerShape(4.dp)).background(Color(NotionColors.argb(event.colorHex))).padding(horizontal = 6.dp, vertical = 1.dp)
    } else {
        this
    }
