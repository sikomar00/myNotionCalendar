package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// 월 그리드의 칸 바탕(둥근 타일)·주차 열·요일 머리·날짜 숫자. 일정 칩 그리기는 MonthGrid.kt가 맡는다.

/** 주차 열 폭. 설정의 '주 번호 표시'를 켰을 때만 그리드 왼쪽에 둔다. sp 기준이라 글꼴·앱 배율이 커져도 두 자리 숫자가 줄바꿈되지 않는다. */
@Composable
internal fun weekGutterWidth(): Dp = with(LocalDensity.current) { 28.sp.toDp() }

/** 구글 캘린더식 둥근 날짜 타일 7칸. 칸 사이 간격은 2dp이고, 일정 칩(좌우 1dp 안쪽 여백)이 타일 가장자리에 맞는다. */
@Composable
internal fun DayTiles(modifier: Modifier = Modifier) {
    Row(modifier) {
        repeat(7) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(1.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

/** 한 주의 주차(예: 45). 눈으로는 숫자만 보이고 낭독은 "45주"로 읽는다. */
@Composable
internal fun WeekNumberCell(number: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.month_week_number_a11y, number)
    Box(
        modifier
            .padding(1.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            modifier = Modifier.semantics { contentDescription = description },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 요일 머리. [gutter]만큼 왼쪽을 비워 주차 열 위에 요일이 오지 않게 한다. */
@Composable
internal fun WeekdayHeader(weekStart: DayOfWeek, gutter: Dp, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.width(gutter))
        repeat(7) { i ->
            val day = weekStart.plus(i.toLong())
            Text(
                text = day.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = dayOfWeekColor(day) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DayLabel(date: LocalDate, inMonth: Boolean, isToday: Boolean, isHoliday: Boolean, modifier: Modifier = Modifier) {
    // MV-01·EX-02: 공휴일은 일요일과 같은 빨강. 다른 달 날짜는 요일색 없이 흐린 기본색(3:1 이상).
    val base = when {
        !inMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = OUTSIDE_MONTH_ALPHA)
        isHoliday -> sundayColor()
        else -> dayOfWeekColor(date.dayOfWeek) ?: MaterialTheme.colorScheme.onSurface
    }
    // 글꼴 크기에 맞춰 커지도록 sp 기준 최소 크기를 쓴다(고정 dp면 글꼴 최대에서 숫자가 잘림).
    val minSize = with(LocalDensity.current) { 18.sp.toDp() }
    Box(modifier.padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
        // 오늘은 칠한 네모(위젯·앱 아이콘의 오늘 칸과 같은 모양).
        val circle = if (isToday) Modifier.clip(TodayShape).background(MaterialTheme.colorScheme.primary) else Modifier
        Box(
            Modifier.defaultMinSize(minWidth = minSize, minHeight = minSize).then(circle).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else base,
            )
        }
    }
}
