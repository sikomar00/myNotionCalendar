package com.ysdoing.notioncal.feature.month

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.calendar.CalendarFocus
import com.ysdoing.notioncal.core.data.calendar.CalendarView
import com.ysdoing.notioncal.core.data.calendar.CalendarZoom
import com.ysdoing.notioncal.core.data.calendar.EventFilter
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private const val PAGE_COUNT = 2400
private const val CENTER_PAGE = PAGE_COUNT / 2

/** 오늘 버튼·위젯 날짜·검색 결과로 특정 날짜로 가라는 요청. 같은 날짜를 다시 눌러도 움직이게 순번을 둔다. */
data class CalendarJump(val date: LocalDate, val seq: Int)

/** 날짜 시트로 열 내용(그날 목록, 일정 편집, 새 일정). 회전·프로세스 복원 뒤에도 남게 글자로 저장한다. */
internal val SheetSaver = Saver<QuickSheetStart?, String>(
    save = { start ->
        when (start) {
            is QuickSheetStart.Day -> "day:${start.date}"
            is QuickSheetStart.Edit -> "edit:${start.pageId}"
            is QuickSheetStart.Add -> "add:${start.date}"
            else -> ""
        }
    },
    restore = { saved ->
        when {
            saved.startsWith("day:") -> QuickSheetStart.Day(LocalDate.parse(saved.removePrefix("day:")))
            saved.startsWith("edit:") -> QuickSheetStart.Edit(saved.removePrefix("edit:"))
            saved.startsWith("add:") -> QuickSheetStart.Add(LocalDate.parse(saved.removePrefix("add:")))
            else -> null
        }
    },
)

/**
 * 앱 첫 화면(MV-01~06). 월·주·3일·목록 보기를 바꿔도 기준 날짜(focus)를 함께 쓴다.
 * Room만 읽고, 동기화는 [MonthViewModel.refresh]로 요청만 한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthRoute(
    onOpenSettings: () -> Unit,
    /** ST-04 인증·공유 오류 배너와 첫 안내 카드의 버튼이 여는 설정(연결 카테고리). 기본은 [onOpenSettings]와 같다. */
    onOpenConnectionSettings: () -> Unit = onOpenSettings,
    openDate: LocalDate? = null,
    /** MV-10 검색 화면 열기. */
    onOpenSearch: () -> Unit = {},
    /** NS-09 매핑을 다시 고르는 화면 열기(databaseId, dataSourceId). */
    onReconnect: (String, String) -> Unit = { _, _ -> },
    onOpenDateHandled: () -> Unit = {},
    /** 상태줄 아래 추가 안내(앱이 절전 안내 카드를 넣는다, S25-03). */
    topBanner: @Composable () -> Unit = {},
    viewModel: MonthViewModel = hiltViewModel(),
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    val view = settings?.calendarView ?: CalendarView.MONTH
    val weekStart = settings?.weekStart ?: DayOfWeek.SUNDAY
    val filter = settings?.filter ?: EventFilter()
    val filterSources by viewModel.filterSources.collectAsStateWithLifecycle()
    var filtering by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf(today) }
    var shownStart by remember { mutableStateOf<LocalDate?>(null) }
    var jump by remember { mutableStateOf<CalendarJump?>(null) }
    var jumpSeq by remember { mutableIntStateOf(0) }
    fun jumpTo(date: LocalDate) {
        focus = date
        jumpSeq += 1
        jump = CalendarJump(date, jumpSeq)
    }
    var sheet by rememberSaveable(stateSaver = SheetSaver) { mutableStateOf<QuickSheetStart?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // MV-08: 제스처 중에는 화면 상태만 바꾸고, 손을 떼면 저장한다.
    val savedZoom = settings?.zoomPercent ?: CalendarZoom.DEFAULT
    var zoomPercent by remember(savedZoom) { mutableFloatStateOf(savedZoom.toFloat()) }
    val zoom = zoomPercent / 100f

    // 위젯에서 날짜를 눌러 들어오면 그 날짜로 이동하고 날짜 시트를 연다.
    LaunchedEffect(openDate) {
        val date = openDate ?: return@LaunchedEffect
        jumpTo(date)
        sheet = QuickSheetStart.Day(date)
        onOpenDateHandled()
    }

    // ☰로만 열고(스와이프로 열면 월 넘김 가로 페이저와 겹친다), 열려 있을 때만 밀어서 닫을 수 있다.
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            MonthDrawerContent(
                view = view,
                filterActive = filter.isActive,
                onSelectView = { selected ->
                    viewModel.setView(selected)
                    scope.launch { drawerState.close() }
                },
                onFilter = {
                    filtering = true
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                MonthTopBar(
                    title = title(view, focus, shownStart),
                    pickable = view == CalendarView.MONTH,
                    filterActive = filter.isActive,
                    today = today,
                    onMenu = { scope.launch { drawerState.open() } },
                    onPickMonth = { picking = true },
                    onSearch = onOpenSearch,
                    onToday = { jumpTo(today) },
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { sheet = QuickSheetStart.Add(focus) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.month_add_event))
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                SyncStatusLine(header, weather, today)
                Banner(header, onOpenConnectionSettings)
                WriteIssuesBanner(header, onAcknowledge = viewModel::acknowledgeConflicts)
                SchemaBanner(header.schemaAlerts, onReconnect = onReconnect, onAcknowledge = viewModel::acknowledgeSchema)
                topBanner()
                PullToRefreshBox(
                    isRefreshing = header.syncing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.weight(1f).pinchToZoom(
                        onZoom = { factor -> zoomPercent = CalendarZoom.clamp(zoomPercent * factor) },
                        onEnd = { viewModel.saveZoom(zoomPercent) },
                    ),
                ) {
                    val openDay = { date: LocalDate -> sheet = QuickSheetStart.Day(date) }
                    val openEvent = { event: CalendarEvent -> sheet = QuickSheetStart.Edit(event.pageId) }
                    when (view) {
                        CalendarView.MONTH -> MonthPager(
                            initialFocus = focus, focus = focus, jump = jump, today = today, weekStart = weekStart,
                            viewModel = viewModel, ready = settings != null, zoom = zoom, showWeekNumbers = settings?.showWeekNumbers ?: false,
                            onFocusChange = { focus = it }, onDayClick = openDay,
                        )
                        CalendarView.WEEK, CalendarView.THREE_DAY -> key(view) {
                            PeriodPager(
                                view = view, initialFocus = focus, focus = focus, jump = jump, today = today, weekStart = weekStart,
                                viewModel = viewModel, zoom = zoom,
                                onFocusChange = { focus = it }, onShownChange = { shownStart = it },
                                onDayClick = openDay, onEventClick = openEvent,
                            )
                        }
                        CalendarView.AGENDA -> AgendaView(
                            initialFocus = focus, jump = jump, today = today, events = viewModel::eventsBetween, holidays = viewModel::holidaysBetween,
                            weather = weather,
                            onDayClick = openDay, onEventClick = openEvent, onFocusChange = { focus = it }, textScale = zoom,
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        YearMonthPicker(
            current = YearMonth.from(focus),
            onPick = { month ->
                picking = false
                jumpTo(CalendarFocus.focusForMonth(month, today))
            },
            onDismiss = { picking = false },
        )
    }
    if (filtering) {
        FilterSheet(filter, filterSources, onChange = viewModel::setFilter, onDismiss = { filtering = false })
    }
    sheet?.let { start -> key(start) { DaySheet(start, onDismiss = { sheet = null }) } }
}

@Composable
internal fun title(view: CalendarView, focus: LocalDate, shownStart: LocalDate?): String = when (view) {
    // 목록 보기도 맨 위 날짜의 연·월을 보인다(보기 이름은 ☰ 서랍에 있다).
    CalendarView.MONTH, CalendarView.AGENDA -> stringResource(R.string.month_title, focus.year, focus.monthValue)
    else -> {
        val start = shownStart ?: focus
        val end = start.plusDays(view.days - 1L)
        if (start.month == end.month) {
            stringResource(R.string.period_title_same_month, start.monthValue, start.dayOfMonth, end.dayOfMonth)
        } else {
            stringResource(R.string.period_title, start.monthValue, start.dayOfMonth, end.monthValue, end.dayOfMonth)
        }
    }
}

internal fun viewLabel(view: CalendarView) = when (view) {
    CalendarView.MONTH -> R.string.view_month
    CalendarView.WEEK -> R.string.view_week
    CalendarView.THREE_DAY -> R.string.view_three_day
    CalendarView.AGENDA -> R.string.view_agenda
}

/** 월 보기(MV-01~05): 가운데 페이지가 앱을 연 달인 가로 페이저(±100년). */
@Composable
private fun MonthPager(
    initialFocus: LocalDate,
    focus: LocalDate,
    jump: CalendarJump?,
    today: LocalDate,
    weekStart: DayOfWeek,
    viewModel: MonthViewModel,
    ready: Boolean,
    zoom: Float,
    showWeekNumbers: Boolean,
    onFocusChange: (LocalDate) -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    val baseMonth = remember { YearMonth.from(today) }
    fun pageOf(month: YearMonth) =
        (CENTER_PAGE + (month.year - baseMonth.year) * 12 + (month.monthValue - baseMonth.monthValue)).coerceIn(0, PAGE_COUNT - 1)
    val pager = rememberPagerState(initialPage = pageOf(YearMonth.from(initialFocus))) { PAGE_COUNT }
    LaunchedEffect(jump) { jump?.let { pager.scrollToPage(pageOf(YearMonth.from(it.date))) } }
    // 넘긴 달이 기준 날짜의 달과 다를 때만 기준 날짜를 바꾼다(주 보기에서 고른 날을 월 보기가 덮지 않게).
    val currentFocus by rememberUpdatedState(focus)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val month = baseMonth.plusMonths((page - CENTER_PAGE).toLong())
            if (YearMonth.from(currentFocus) != month) onFocusChange(CalendarFocus.focusForMonth(month, today))
        }
    }

    // PF-01: 이번 달 일정을 처음 그린 시점을 '완전히 그림'으로 알린다(Macrobenchmark timeToFullDisplay).
    val currentEvents by remember(baseMonth, weekStart) { viewModel.eventsFor(baseMonth, weekStart) }.collectAsStateWithLifecycle()
    ReportDrawnWhen { ready && currentEvents != null }
    // S25-05: 월 스와이프 중 느린 프레임 비율을 logcat(NotionCalJank)에 남긴다.
    MonthSwipeJankTracker(swiping = pager.isScrollInProgress)

    HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { it }) { page ->
        val month = baseMonth.plusMonths((page - CENTER_PAGE).toLong())
        val flow = remember(month, weekStart) { viewModel.eventsFor(month, weekStart) }
        val events by flow.collectAsStateWithLifecycle()
        val visible = events.orEmpty()
        val holidays by remember(month, weekStart) { viewModel.holidaysFor(month, weekStart) }.collectAsStateWithLifecycle()
        val weather by viewModel.weather.collectAsStateWithLifecycle()
        // 그리드는 스크롤할 내용이 없지만, 당겨서 새로고침이 제스처를 받도록 세로 스크롤 컨테이너에 넣는다.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val height = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                TextZoom(zoom) {
                    MonthGrid(
                        state = MonthGridState(month, weekStart, today, visible, holidays, weather, showWeekNumbers),
                        onDayClick = onDayClick,
                        onMove = viewModel::move,
                        modifier = Modifier.fillMaxWidth().height(height),
                    )
                }
            }
        }
    }
}

/** 주·3일 보기(MV-06): 기간 단위로 넘기는 가로 페이저 + 시간표. */
@Composable
private fun PeriodPager(
    view: CalendarView,
    initialFocus: LocalDate,
    focus: LocalDate,
    jump: CalendarJump?,
    today: LocalDate,
    weekStart: DayOfWeek,
    viewModel: MonthViewModel,
    zoom: Float,
    onFocusChange: (LocalDate) -> Unit,
    onShownChange: (LocalDate) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
) {
    val days = view.days
    val base = remember { CalendarFocus.periodStart(view, initialFocus, weekStart) }
    fun startOf(page: Int): LocalDate = base.plusDays((page - CENTER_PAGE).toLong() * days)
    fun pageOf(date: LocalDate): Int =
        (CENTER_PAGE + Math.floorDiv(ChronoUnit.DAYS.between(base, date), days.toLong()).toInt()).coerceIn(0, PAGE_COUNT - 1)
    val pager = rememberPagerState(initialPage = pageOf(initialFocus)) { PAGE_COUNT }
    LaunchedEffect(jump) { jump?.let { pager.scrollToPage(pageOf(it.date)) } }
    val currentFocus by rememberUpdatedState(focus)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val start = startOf(page)
            val end = start.plusDays(days - 1L)
            onShownChange(start)
            if (currentFocus !in start..end) onFocusChange(CalendarFocus.focusFor(start, end, today))
        }
    }
    // 지금 시각 선은 1분마다 옮긴다.
    var nowMinute by remember { mutableIntStateOf(LocalTime.now(APP_ZONE).let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMinute = LocalTime.now(APP_ZONE).let { it.hour * 60 + it.minute }
        }
    }
    HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { it }) { page ->
        val start = startOf(page)
        val flow = remember(start, days) { viewModel.eventsBetween(start, start.plusDays(days - 1L)) }
        val events by flow.collectAsStateWithLifecycle()
        val visible = events.orEmpty()
        val holidays by remember(start, days) { viewModel.holidaysBetween(start, start.plusDays(days - 1L)) }.collectAsStateWithLifecycle()
        val weather by viewModel.weather.collectAsStateWithLifecycle()
        TimeGrid(
            weather = weather,
            start = start,
            days = days,
            today = today,
            nowMinute = nowMinute,
            events = visible,
            holidays = holidays,
            hourHeight = 48.dp * zoom,
            onDayClick = onDayClick,
            onEventClick = onEventClick,
            initialScrollHour = if (today in start..start.plusDays(days - 1L)) (nowMinute / 60 - 1).coerceAtLeast(0) else 8,
        )
    }
}
