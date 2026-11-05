package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.calendar.GRID_DAYS
import com.ysdoing.notioncal.core.data.calendar.WeekLayout
import com.ysdoing.notioncal.core.data.calendar.gridStart
import com.ysdoing.notioncal.core.data.view.CalendarRows
import com.ysdoing.notioncal.core.data.view.ViewBoard
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

// Phase 12a VR-CAL: 보기 위젯의 캘린더 본문이 쓰는 순수 계산. 크기 단계로 모양을 고르고, 머리·줄을 뺀 높이를 격자와 오늘 목록에 나눈다.

/** 캘린더 모양: 월 격자(2×2는 점만, 4×4 이상은 일정 막대) 또는 4×2의 '이번 주 한 줄 + 오늘 목록'. */
internal enum class CalendarMode { MONTH, WEEK }

internal fun calendarMode(step: SizeStep): CalendarMode = if (step == SizeStep.WIDE) CalendarMode.WEEK else CalendarMode.MONTH

/** 캘린더가 쓰는 앱 설정과 공휴일. 보기 위젯 상태에 실려 오고 Room에서만 읽는다. */
data class CalendarInfo(
    val weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    val showWeekNumbers: Boolean = false,
    val holidays: Map<LocalDate, String> = emptyMap(),
)

/** 그릴 때마다 오늘을 다시 계산한다(PRD §7-4). */
internal fun calendarToday(state: ViewWidgetState): LocalDate = Instant.ofEpochMilli(state.nowMillis).atZone(APP_ZONE).toLocalDate()

/** 보이는 달: 월 격자는 위젯의 `monthOffset`만큼 옮긴 달, 이번 주 모양은 오늘이 든 달이다. */
internal fun calendarMonth(state: ViewWidgetState, mode: CalendarMode): YearMonth {
    val thisMonth = YearMonth.from(calendarToday(state))
    return if (mode == CalendarMode.MONTH) thisMonth.plusMonths(state.config.monthOffset.toLong()) else thisMonth
}

/** 캘린더로 그릴 수 있는 보기: 종류가 캘린더이고 날짜 열을 알 때. 아니면 지금처럼 표·목록으로 그린다. */
internal fun ViewBoard.drawsCalendar(): Boolean = type == "calendar" && dateColumn != null

/** v1 월 위젯의 [WidgetUiState]와 같은 모양으로 보기의 일정을 담는다. 그러면 같은 일정은 같은 격자 노드가 된다. */
internal fun calendarUi(board: ViewBoard, state: ViewWidgetState, mode: CalendarMode) = WidgetUiState(
    month = calendarMonth(state, mode),
    today = calendarToday(state),
    weekStart = state.calendar.weekStart,
    events = CalendarRows.events(board),
    config = state.config,
    holidays = state.calendar.holidays,
    showWeekNumbers = state.calendar.showWeekNumbers,
    dimOutsideMonth = mode == CalendarMode.MONTH, // 이번 주 한 줄은 달이 아니라 7일이다
)

/** 공휴일을 읽을 날짜 범위: 이번 달과 위젯이 옮겨 둔 달의 6주 격자를 모두 덮는다(이번 주 모양은 이번 달 격자 안이다). */
internal fun calendarRange(today: LocalDate, monthOffset: Int, weekStart: DayOfWeek): Pair<LocalDate, LocalDate> {
    val thisMonth = YearMonth.from(today)
    val a = gridStart(thisMonth, weekStart)
    val b = gridStart(thisMonth.plusMonths(monthOffset.toLong()), weekStart)
    return (if (a.isBefore(b)) a else b) to (if (a.isAfter(b)) a else b).plusDays(GRID_DAYS - 1)
}

/** 그 날짜가 든 주의 첫날. */
internal fun weekStartOf(date: LocalDate, weekStart: DayOfWeek): LocalDate = date.minusDays(((date.dayOfWeek.value - weekStart.value + 7) % 7).toLong())

/** 그날 일정(여러 날 일정은 걸친 날마다). */
internal fun eventsOn(events: List<CalendarEvent>, date: LocalDate): List<CalendarEvent> = events.filter { it.occursOn(date) }

/**
 * 캘린더 본문의 배치. [navHeight]는 월 이동 줄(이번 주 모양은 없다), [grid]는 격자가 쓸 면, [listHeight]는 격자 아래 오늘 목록의 높이다.
 * 2×2는 눌림 영역을 48dp로 줄 높이가 없어 이동 줄을 32dp로 줄인다(v1 작음 단계의 머리 버튼과 같은 예외).
 */
internal data class CalendarLayout(val mode: CalendarMode, val navHeight: Dp, val grid: GridArea, val listHeight: Dp, val compact: Boolean = false)

private val COMPACT_NAV_HEIGHT = 32.dp
private const val WEEK_GRID_SHARE = 0.5f

/** [width]는 격자 폭(위젯 안쪽), [bodyHeight]는 머리·상태·되돌리기·프리셋 줄을 뺀 높이다. */
internal fun calendarLayout(step: SizeStep, width: Dp, bodyHeight: Dp): CalendarLayout {
    val area = bodyHeight.coerceAtLeast(0.dp)
    if (calendarMode(step) == CalendarMode.WEEK) {
        val strip = area * WEEK_GRID_SHARE
        return CalendarLayout(CalendarMode.WEEK, 0.dp, GridArea(width, strip, weeks = 1), area - strip)
    }
    val compact = step == SizeStep.COMPACT
    val nav = if (compact) COMPACT_NAV_HEIGHT else NAV_HEIGHT
    val maxMode = if (compact) WidgetMode.SMALL else WidgetMode.LARGE
    return CalendarLayout(CalendarMode.MONTH, nav, GridArea(width, (area - nav).coerceAtLeast(0.dp), maxMode = maxMode), 0.dp, compact)
}

// 격자가 읽힐 최소 면. 월은 주 한 줄이 날짜 숫자보다 낮아지면(v1 작음 단계도 10dp까지) 숫자가 겹치고, 이번 주 모양은 요일 줄 + 날짜 + 일정 한 줄이 들어가야 한다.
private val MIN_MONTH_GRID = 72.dp
private val MIN_STRIP = 44.dp

/**
 * 이 보기를 캘린더로 그릴 배치. 캘린더 보기가 아니거나, 격자가 읽힐 높이가 없으면(상태·되돌리기 줄이 높이를 먹은 150dp 2×2, 낮은 탭·섹션 칸) null이고
 * 그때는 표·목록으로 대체한다. [spec]은 탭·섹션 칸이면 그 칸의 높이를, [aboveHeight]는 머리줄 아래 이미 그려진 줄(상태·되돌리기·프리셋)의 높이를 준다.
 */
internal fun calendarLayoutFor(board: ViewBoard, size: DpSize, spec: PaneSpec, m: ViewMetrics, aboveHeight: Dp): CalendarLayout? {
    if (!board.drawsCalendar()) return null
    val total = spec.height ?: (size.height - VIEW_PADDING_V * 2)
    val body = paneBodyHeight(total, progressOf(board, size) != null, m, aboveHeight)
    val layout = calendarLayout(sizeStep(size), size.width - PADDING_H * 2, body)
    return layout.takeIf { it.grid.height >= (if (it.mode == CalendarMode.MONTH) MIN_MONTH_GRID else MIN_STRIP) }
}

/**
 * 머리줄 아래 본문(캘린더·차트)이 쓰는 높이. [total]은 단일 위젯이면 위젯 높이에서 위아래 여백을 뺀 값, 탭·섹션 칸이면 그 칸의 높이다.
 * 머리 높이는 [ViewHeader]와 같은 조건(부제가 보일 때만 더 높다)으로 정하고, [linesHeight]는 상태·되돌리기·프리셋 줄이다.
 */
internal fun paneBodyHeight(total: Dp, subtitleShown: Boolean, m: ViewMetrics, linesHeight: Dp): Dp =
    (total - (if (subtitleShown) m.headerWithSub else m.header) - linesHeight).coerceAtLeast(0.dp)

/**
 * 오늘 목록의 줄 수: 높이에 들어가는 만큼. 다 못 보이면 '더 보기' 줄 자리를 한 줄 빼 준다(그래도 한 줄은 둔다).
 * 일정을 일부만 받았으면([complete]가 false) 다 보여도 '더 보기'가 붙으니 늘 그 자리를 뺀다.
 */
internal fun todayRowLimit(listHeight: Dp, rowHeight: Dp, total: Int, complete: Boolean = true): Int {
    val withMore = ((listHeight - MORE_HEIGHT) / rowHeight).toInt().coerceAtLeast(1)
    if (!complete) return minOf(total, withMore)
    return if (total <= (listHeight / rowHeight).toInt()) total else withMore
}

// Glance는 위젯 하나에 뷰를 500개(생성된 view id 000~499)까지만 둔다. 넘으면 그릴 때 'There are too many views'로 위젯이 오류 화면이 된다.
// 일정이 많은 달력(칸마다 줄 수가 많고 일정이 다 다름)이 그렇게 되지 않도록, 그릴 뷰 수를 어림해 넘칠 듯하면 칸당 줄 수를 줄인다.
// 실측(변환 시험이 view id 번호를 읽음): 일정 없는 6주 격자는 282개(머리·이동·요일 줄 + 주 틀), 줄 하나마다 1개, 일정 막대 하나 3개, 그 밖의 칸(빈 칸·+N) 하나 1개,
// 줄 수 1은 일정 없이 330개에 일정 있는 날마다 2~3개. 3줄로 가득 찬 4×5가 498개였다. 아래 상수는 실측보다 조금 넉넉하다(주차 열·프리셋·되돌리기 줄 몫).
internal const val GLANCE_VIEW_LIMIT = 500
private const val CHROME_VIEWS = 110 // 머리·상태·프리셋·이동 줄·요일 줄(실측 약 70 + 프리셋·되돌리기·상태 줄 몫)
private const val WEEK_BASE_VIEWS = 37 // 주 틀 + 날짜 숫자 7개 + 칸 누름 영역 7개 + 주차 + 오늘 표시
private const val SINGLE_LINE_VIEWS = 29 // 줄 수가 1이면 칸마다 '첫 일정 + N' 한 줄(가장 많을 때)
private const val SEGMENT_VIEWS = 3 // 일정 막대·칩 하나(바깥 + 점 + 글자)

/** 한 주가 만드는 뷰 수의 어림. 작음(점) 단계는 줄을 줄이지 않으니 다루지 않는다. */
internal fun weekViews(weekStart: LocalDate, events: List<CalendarEvent>, metrics: WidgetMetrics): Int =
    if (metrics.lanes == 1) {
        WEEK_BASE_VIEWS + SINGLE_LINE_VIEWS
    } else {
        val layout = WeekLayout.layout(weekStart, events, metrics.lanes)
        WEEK_BASE_VIEWS + (0 until metrics.lanes).sumOf { lane -> 1 + laneSegments(layout, lane).sumOf { if (it.placed != null) SEGMENT_VIEWS else 1 } }
    }

/** 위젯 전체의 뷰 수 어림: 머리·이동 줄 + [weekStarts]의 주. */
internal fun viewEstimate(weekStarts: List<LocalDate>, events: List<CalendarEvent>, metrics: WidgetMetrics): Int =
    CHROME_VIEWS + weekStarts.sumOf { weekViews(it, events, metrics) }

/** [weekStarts]의 주를 [metrics]대로 그리면 뷰 한도를 넘을 듯할 때, 한도 안에 들어올 때까지(최소 1줄) 칸당 줄 수를 하나씩 줄인 치수. */
internal fun fitLanes(weekStarts: List<LocalDate>, events: List<CalendarEvent>, metrics: WidgetMetrics): WidgetMetrics {
    var fitted = metrics
    while (fitted.lanes > 1 && fitted.mode != WidgetMode.SMALL && viewEstimate(weekStarts, events, fitted) > GLANCE_VIEW_LIMIT) {
        fitted = fitted.copy(lanes = fitted.lanes - 1)
    }
    return fitted
}
