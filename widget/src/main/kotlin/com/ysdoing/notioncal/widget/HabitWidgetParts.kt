package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.calendar.gridStart
import com.ysdoing.notioncal.core.data.habit.HabitBoard
import com.ysdoing.notioncal.core.data.habit.HabitMessage
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

// 습관 위젯의 히어로·연속 알약·이번 달 잔디. HabitWidget.kt가 500줄을 넘지 않게 나눴다.

/** 4×4 위쪽 히어로: 오늘 한 개수를 크게 + 진행 막대. 낭독은 한 문장으로 읽는다. */
@Composable
internal fun HabitHero(board: HabitBoard, metrics: HabitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val total = board.habits.size
    val description = context.getString(R.string.habit_widget_hero_description, total, board.doneToday)
    val fraction = board.doneToday.toFloat() / total
    Row(
        GlanceModifier.fillMaxWidth().height(metrics.heroHeight).padding(horizontal = 4.dp).semantics {
            contentDescription = description
            testTag = "habitHero"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(board.doneToday.toString(), style = TextStyle(color = palette.text, fontSize = metrics.heroFontSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Text("/$total", modifier = GlanceModifier.padding(start = 2.dp), style = TextStyle(color = palette.subtle, fontSize = (metrics.heroFontSp * 0.55f).sp), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Box(GlanceModifier.width(HERO_BAR).height(6.dp).cornerRadius(3.dp).background(palette.tileHigh)) {
            if (fraction > 0f) Box(GlanceModifier.width(HERO_BAR * fraction).height(6.dp).cornerRadius(3.dp).background(palette.green)) {}
        }
    }
}

private val HERO_BAR = 116.dp

/** 이름 아래 알약: 이어 가는 중이면 '연속 N일'(2일부터), 하루 쉬었으면 '오늘 하면 이어짐'(HB-04). 그 밖에는 없다. */
@Composable
internal fun HabitMessagePill(message: HabitMessage, metrics: HabitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    when {
        message is HabitMessage.Streak && message.days >= 2 -> WidgetPill(
            context.getString(R.string.habit_widget_streak, message.days), palette.greenContainer, palette.onGreenContainer,
            metrics.smallFontSp, GlanceModifier.padding(top = 2.dp), icon = R.drawable.ic_widget_flame,
        )
        message == HabitMessage.MissedYesterday -> WidgetPill(
            context.getString(R.string.habit_widget_missed), palette.accentContainer, palette.onAccentContainer,
            metrics.smallFontSp, GlanceModifier.padding(top = 2.dp),
        )
    }
}


/**
 * HB-03 4×4 이번 달 잔디: 그날 진행 중 습관 중 한 비율을 4단계 농도로. 요일 줄 + 주 줄(최대 6) = 자식 7개.
 * 아직 오지 않은 날은 빈칸, 다른 달 날짜는 그리지 않는다.
 */
@Composable
internal fun MonthGrass(state: HabitWidgetState, metrics: HabitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val board = state.board
    val month = YearMonth.from(board.today)
    val ids = board.habits.map { it.habit.pageId }
    val doneByDay = ids.flatMap { board.doneDates[it].orEmpty() }.groupingBy { it }.eachCount()
    val fullDays = (1..board.today.dayOfMonth).count { doneByDay[month.atDay(it)] == ids.size }
    val start = gridStart(month, state.weekStart)
    val weeks = ((month.atEndOfMonth().toEpochDay() - start.toEpochDay()) / 7 + 1).toInt()
    Column(
        GlanceModifier.fillMaxWidth().padding(bottom = 4.dp).semantics {
            contentDescription = context.getString(R.string.habit_widget_grass, month.monthValue, fullDays)
            testTag = "habitGrass"
        },
    ) {
        Row(GlanceModifier.fillMaxWidth().height(metrics.dayLabelHeight)) {
            repeat(7) { i ->
                Text(
                    text = state.weekStart.plus(i.toLong()).getDisplayName(JavaTextStyle.NARROW, Locale.KOREAN),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = palette.subtle, fontSize = metrics.smallFontSp.sp, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
        repeat(weeks) { week ->
            Row(GlanceModifier.fillMaxWidth().height(metrics.grassHeight + 2.dp)) {
                repeat(7) { col ->
                    val date = start.plusDays(week * 7L + col)
                    Box(GlanceModifier.defaultWeight().height(metrics.grassHeight + 2.dp).padding(1.dp)) {
                        if (YearMonth.from(date) == month) {
                            val color = when {
                                date.isAfter(board.today) -> null
                                ids.isEmpty() -> palette.track
                                else -> palette.grassLevel((doneByDay[date] ?: 0).toFloat() / ids.size)
                            }
                            Box(
                                GlanceModifier.fillMaxSize().cornerRadius(2.dp)
                                    .then(if (color != null) GlanceModifier.background(color) else GlanceModifier)
                                    .semantics { testTag = "habitGrassDay" },
                            ) {}
                        }
                    }
                }
            }
        }
    }
}
