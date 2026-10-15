package com.ysdoing.notioncal.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * 목록 위젯(WG-06)과 '달력 + 일정' 위젯(WG-09)이 같이 쓰는 일정 목록 줄.
 * 크기 값은 각 위젯의 치수(AgendaMetrics·SplitMetrics)에서 받는다.
 */

private val rowTimeFormat = DateTimeFormatter.ofPattern("H:mm")

/** 일정을 누르면 그 일정의 편집 시트(WI-05). PendingIntent가 일정마다 구분되도록 데이터 URI에 페이지 ID를 넣는다. */
internal fun editIntent(context: Context, event: CalendarEvent, date: LocalDate): Intent =
    quickSheetIntent(context, AppIntents.MODE_EDIT, date)
        .setData("notioncal://edit/$date/${event.pageId}".toUri())
        .putExtra(AppIntents.EXTRA_PAGE_ID, event.pageId)

/**
 * 일정 한 줄: 색 점(완료면 ✓), 시각, 제목.
 * - [timeColumn]이 있으면 그 폭의 시간 칸에 '9:30'·'종일'·'여러 날'·'이어짐'을 늘 보인다(목록 위젯).
 *   없으면 시간 일정만 제목 앞에 시각을 붙인다('달력 + 일정' 위젯).
 * - EX-11 강조 일정은 제목을 일정 색 칸에 대비되는 굵은 글자로, 스크린 리더에는 '중요'를 먼저 읽힌다.
 * - A11Y-03: 완료는 색 점 대신 체크 + 취소선(색만으로 구분하지 않음).
 */
@Composable
internal fun WidgetEventRow(
    event: CalendarEvent,
    date: LocalDate,
    height: Dp,
    itemFontSp: Float,
    timeFontSp: Float,
    palette: WidgetPalette,
    tag: String,
    dotWidth: Dp,
    timeColumn: Dp? = null,
    continues: Boolean = false,
) {
    val context = LocalContext.current
    val time = if (timeColumn != null) {
        when {
            event.isMultiDay -> context.getString(if (continues) R.string.agenda_widget_continues else R.string.agenda_widget_multi_day)
            event.startTime == null -> context.getString(R.string.agenda_widget_all_day)
            else -> event.startTime!!.atZone(APP_ZONE).format(rowTimeFormat)
        }
    } else {
        event.startTime?.takeIf { !event.isMultiDay }?.atZone(APP_ZONE)?.format(rowTimeFormat)
    }
    val title = event.title.ifBlank { context.getString(R.string.agenda_widget_untitled) }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(height)
            .clickable(actionStartActivity(editIntent(context, event, date)))
            .semantics {
                contentDescription = listOfNotNull(
                    context.getString(R.string.widget_emphasized).takeIf { event.showsEmphasis },
                    title,
                    time,
                    context.getString(R.string.agenda_widget_done).takeIf { event.done },
                ).joinToString(", ")
                testTag = tag
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (event.done) "✓" else "●",
            modifier = GlanceModifier.width(dotWidth),
            style = TextStyle(color = ColorProvider(Color(NotionColors.argb(event.colorHex))), fontSize = timeFontSp.sp),
            maxLines = 1,
        )
        if (time != null) {
            Text(
                text = time,
                modifier = if (timeColumn != null) GlanceModifier.width(timeColumn) else GlanceModifier.padding(end = 4.dp),
                style = TextStyle(color = palette.subtle, fontSize = timeFontSp.sp),
                maxLines = 1,
            )
        }
        // 강조 칸은 글자 폭만큼(제목 칸을 다 칠하지 않게 상자로 감싼다).
        Box(GlanceModifier.defaultWeight()) {
            Text(
                text = title,
                modifier = GlanceModifier.emphasisBox(event),
                style = TextStyle(
                    color = when {
                        event.showsEmphasis -> emphasisTextColor(event)
                        event.done -> palette.subtle
                        else -> palette.text
                    },
                    fontSize = itemFontSp.sp,
                    fontWeight = if (event.showsEmphasis) FontWeight.Bold else null,
                    textDecoration = if (event.done) TextDecoration.LineThrough else null,
                ),
                maxLines = 1,
            )
        }
    }
}

/** 오늘에 일정이 없을 때의 '일정 없음' 줄. */
@Composable
internal fun WidgetEmptyRow(height: Dp, fontSp: Float, palette: WidgetPalette, tag: String, startPadding: Dp = 0.dp) {
    val context = LocalContext.current
    Text(
        text = context.getString(R.string.agenda_widget_empty),
        modifier = GlanceModifier.fillMaxWidth().height(height).padding(start = startPadding).semantics { testTag = tag },
        style = TextStyle(color = palette.subtle, fontSize = fontSp.sp),
        maxLines = 1,
    )
}
