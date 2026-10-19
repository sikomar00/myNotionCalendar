package com.ysdoing.notioncal.feature.month

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.calendar.CalendarFocus
import com.ysdoing.notioncal.core.data.calendar.CalendarView
import com.ysdoing.notioncal.core.data.calendar.CalendarZoom
import com.ysdoing.notioncal.core.data.calendar.EventFilter
import com.ysdoing.notioncal.feature.quicksheet.QuickSheetStart
import kotlinx.coroutines.delay
import com.ysdoing.notioncal.core.designsystem.WeatherBadge
import com.ysdoing.notioncal.core.designsystem.WeatherCredit
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val PAGE_COUNT = 2400
private const val CENTER_PAGE = PAGE_COUNT / 2

/** 오늘 버튼·위젯 날짜·검색 결과로 특정 날짜로 가라는 요청. 같은 날짜를 다시 눌러도 움직이게 순번을 둔다. */
data class CalendarJump(val date: LocalDate, val seq: Int)

/** 날짜 시트로 열 내용(그날 목록 또는 일정 편집). 회전·프로세스 복원 뒤에도 남게 글자로 저장한다. */
private val SheetSaver = Saver<QuickSheetStart?, String>(
    save = { start ->
        when (start) {
            is QuickSheetStart.Day -> "day:${start.date}"
            is QuickSheetStart.Edit -> "edit:${start.pageId}"
            else -> ""
        }
    },
    restore = { saved ->
        when {
            saved.startsWith("day:") -> QuickSheetStart.Day(LocalDate.parse(saved.removePrefix("day:")))
            saved.startsWith("edit:") -> QuickSheetStart.Edit(saved.removePrefix("edit:"))
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 제목은 먹색(색은 일정에만, 파랑은 '오늘'·보기 같은 동작에만).
                    TextButton(
                        onClick = { if (view == CalendarView.MONTH) picking = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    ) {
                        Text(
                            title(view, focus, shownStart),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { jumpTo(today) }) { Text(stringResource(R.string.month_today)) }
                    ViewMenu(view, onSelect = viewModel::setView)
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_title))
                    }
                    IconButton(onClick = { filtering = true }) {
                        Icon(
                            FilterIcon,
                            contentDescription = stringResource(if (filter.isActive) R.string.filter_active else R.string.filter_title),
                            tint = if (filter.isActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.month_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncStatusLine(header, weather, today)
            Banner(header, onOpenSettings)
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
                        viewModel = viewModel, ready = settings != null, zoom = zoom,
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
private fun title(view: CalendarView, focus: LocalDate, shownStart: LocalDate?): String = when (view) {
    // 목록 보기도 맨 위 날짜의 연·월을 보인다(보기 이름은 오른쪽 '목록 ▾'에 있다).
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

/** MV-06 보기 바꾸기 메뉴. 버튼에는 지금 보기 이름을 보여 준다. */
@Composable
private fun ViewMenu(view: CalendarView, onSelect: (CalendarView) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(viewLabel(view)) + " ▾") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        CalendarView.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(stringResource(viewLabel(option))) },
                onClick = {
                    open = false
                    onSelect(option)
                },
                trailingIcon = { if (option == view) Icon(Icons.Filled.Check, contentDescription = null) },
            )
        }
    }
}

private fun viewLabel(view: CalendarView) = when (view) {
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
                        state = MonthGridState(month, weekStart, today, visible, holidays, weather),
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

private val syncTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

@Composable
private fun SyncStatusLine(header: SyncHeader, weather: MonthWeather, today: LocalDate) {
    val text = when {
        header.syncing -> stringResource(R.string.month_syncing)
        header.lastSuccessAt != null -> stringResource(
            R.string.month_last_sync,
            Instant.ofEpochMilli(header.lastSuccessAt).atZone(APP_ZONE).format(syncTimeFormat),
        )
        else -> stringResource(R.string.month_never_synced)
    }
    val pending = if (header.pendingWrites > 0) stringResource(R.string.month_pending_writes, header.pendingWrites) else null
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(text, pending).joinToString(" · "),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // WX-03 머리: 오늘 날씨(아이콘·최고·최저). WX-05 출처 링크를 함께 둔다.
        weather.days[today]?.let { day ->
            WeatherBadge(day, weather.stale, iconSize = 14.dp, fontSize = 11.sp)
            val uri = LocalUriHandler.current
            Text(
                stringResource(R.string.month_weather_credit),
                modifier = Modifier.padding(start = 6.dp).clickable { uri.openUri(WeatherCredit.SITE) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** NS-05: 노션 반영 실패(되돌림)·충돌(노션 값 우선) 알림. 확인을 누르면 내용을 보여주고 읽음 처리한다. */
@Composable
private fun WriteIssuesBanner(header: SyncHeader, onAcknowledge: () -> Unit) {
    val issues = header.writeIssues
    if (issues.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val rejected = issues.count { it.kind == "REJECTED" }
    val conflicts = issues.size - rejected
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.month_write_issues, rejected, conflicts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.month_write_issues_show)) }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.month_write_issues_title)) },
            text = {
                Column {
                    issues.take(20).forEach { issue ->
                        val what = when (issue.kind) {
                            "REJECTED" -> stringResource(R.string.month_issue_rejected)
                            "REMOTE_DELETED" -> stringResource(R.string.month_issue_remote_deleted)
                            else -> stringResource(R.string.month_issue_conflict, issue.localValue.orEmpty(), issue.remoteValue.orEmpty())
                        }
                        Text("• ${issue.title.ifBlank { "(제목 없음)" }}: $what", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAcknowledge()
                    open = false
                }) { Text(stringResource(R.string.month_write_issues_ack)) }
            },
        )
    }
}

/**
 * NS-09: 노션에서 매핑한 속성이 사라지거나 바뀌었을 때. 제목·날짜가 없으면 그 DB는 동기화를 멈추고(캐시는 그대로)
 * '다시 연결'로 매핑을 다시 고르게 한다. 이름만 바뀐 경우는 동작이 그대로라 알림만 한다.
 */
@Composable
private fun SchemaBanner(alerts: List<SchemaAlert>, onReconnect: (String, String) -> Unit, onAcknowledge: (String) -> Unit) {
    alerts.forEach { alert ->
        val blocked = alert.issues.any { it.blocksSync }
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (blocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                alert.issues.forEach { issue ->
                    Text(schemaMessage(alert.name, issue), style = MaterialTheme.typography.bodyMedium)
                }
                Row {
                    if (alert.issues.any { it.kind != SchemaIssue.RENAMED }) {
                        TextButton(onClick = { onReconnect(alert.databaseId, alert.dataSourceId) }) {
                            Text(stringResource(R.string.schema_reconnect))
                        }
                    }
                    TextButton(onClick = { onAcknowledge(alert.dataSourceId) }) { Text(stringResource(R.string.schema_ack)) }
                }
            }
        }
    }
}

@Composable
private fun schemaMessage(dbName: String, issue: SchemaIssue): String {
    val role = stringResource(
        when (issue.role) {
            SchemaIssue.TITLE -> R.string.schema_role_title
            SchemaIssue.DATE -> R.string.schema_role_date
            SchemaIssue.DONE -> R.string.schema_role_done
            SchemaIssue.COLOR -> R.string.schema_role_color
            else -> R.string.schema_role_sort
        },
    )
    val prop = issue.oldName?.let { "'$it'" } ?: role
    return when {
        issue.kind == SchemaIssue.RENAMED -> stringResource(R.string.schema_renamed, dbName, issue.oldName.orEmpty(), issue.newName.orEmpty())
        issue.blocksSync && issue.kind == SchemaIssue.MISSING -> stringResource(R.string.schema_missing_required, dbName, role, prop)
        issue.blocksSync -> stringResource(R.string.schema_type_required, dbName, role, prop)
        issue.kind == SchemaIssue.MISSING -> stringResource(R.string.schema_missing_optional, dbName, role, prop)
        else -> stringResource(R.string.schema_type_optional, dbName, role, prop)
    }
}

/** PF-05: 인증·공유 오류여도 화면 내용은 그대로 두고 안내만 띄운다. 로그인 화면으로 강제 이동하지 않는다. */
@Composable
private fun Banner(header: SyncHeader, onOpenSettings: () -> Unit) {
    val message = when {
        !header.hasSources -> null
        header.lastError == "Unauthorized" -> R.string.month_banner_auth
        header.lastError == "NotShared" -> R.string.month_banner_not_shared
        else -> null
    }
    if (!header.hasSources) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.month_onboarding_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.month_onboarding_body), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.month_banner_action)) }
            }
        }
    } else if (message != null) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.month_banner_action)) }
            }
        }
    }
}
