package com.ysdoing.notioncal.feature.month

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.APP_ZONE
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private const val PAGE_COUNT = 2400
private const val CENTER_PAGE = PAGE_COUNT / 2

/** 앱 첫 화면(MV-01~05). Room만 읽고, 동기화는 [MonthViewModel.refresh]로 요청만 한다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthRoute(
    onOpenSettings: () -> Unit,
    openDate: LocalDate? = null,
    onOpenDateHandled: () -> Unit = {},
    /** 상태줄 아래 추가 안내(앱이 절전 안내 카드를 넣는다, S25-03). */
    topBanner: @Composable () -> Unit = {},
    viewModel: MonthViewModel = hiltViewModel(),
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    // 페이지 번호 ↔ 달: 앱을 연 달이 가운데 페이지(±100년).
    val baseMonth = remember { YearMonth.from(today) }
    val pager = rememberPagerState(initialPage = CENTER_PAGE) { PAGE_COUNT }
    val shownMonth = baseMonth.plusMonths((pager.currentPage - CENTER_PAGE).toLong())
    val scope = rememberCoroutineScope()
    var picking by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableStateOf<LocalDate?>(null) }

    // PF-01: 이번 달 일정을 처음 그린 시점을 '완전히 그림'으로 알린다(Macrobenchmark timeToFullDisplay).
    val currentEvents by remember(baseMonth, settings?.weekStart) {
        viewModel.eventsFor(baseMonth, settings?.weekStart ?: DayOfWeek.SUNDAY)
    }.collectAsStateWithLifecycle()
    ReportDrawnWhen { settings != null && currentEvents != null }
    // S25-05: 월 스와이프 중 느린 프레임 비율을 logcat(NotionCalJank)에 남긴다.
    MonthSwipeJankTracker(swiping = pager.isScrollInProgress)

    fun pageOf(month: YearMonth) = CENTER_PAGE + (month.year - baseMonth.year) * 12 + (month.monthValue - baseMonth.monthValue)

    // 위젯에서 날짜를 눌러 들어오면 그 달로 이동하고 날짜 시트를 연다.
    LaunchedEffect(openDate) {
        val date = openDate ?: return@LaunchedEffect
        pager.scrollToPage(pageOf(YearMonth.from(date)).coerceIn(0, PAGE_COUNT - 1))
        selectedDay = date
        onOpenDateHandled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextButton(onClick = { picking = true }) {
                        Text(
                            stringResource(R.string.month_title, shownMonth.year, shownMonth.monthValue),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pageOf(YearMonth.from(today))) } }) {
                        Text(stringResource(R.string.month_today))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.month_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncStatusLine(header)
            Banner(header, onOpenSettings)
            WriteIssuesBanner(header, onAcknowledge = viewModel::acknowledgeConflicts)
            topBanner()
            PullToRefreshBox(
                isRefreshing = header.syncing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
            ) {
                val weekStart = settings?.weekStart ?: DayOfWeek.SUNDAY
                val hideDone = settings?.hideDone ?: false
                HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { it }) { page ->
                    val month = baseMonth.plusMonths((page - CENTER_PAGE).toLong())
                    val flow = remember(month, weekStart) { viewModel.eventsFor(month, weekStart) }
                    val events by flow.collectAsStateWithLifecycle()
                    val visible = remember(events, hideDone) {
                        events.orEmpty().let { list -> if (hideDone) list.filterNot { it.done } else list }
                    }
                    // 그리드는 스크롤할 내용이 없지만, 당겨서 새로고침이 제스처를 받도록 세로 스크롤 컨테이너에 넣는다.
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val height = maxHeight
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MonthGrid(
                                state = MonthGridState(month, weekStart, today, visible),
                                onDayClick = { selectedDay = it },
                                onMove = viewModel::move,
                                modifier = Modifier.fillMaxWidth().height(height),
                            )
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        YearMonthPicker(
            current = shownMonth,
            onPick = { month ->
                picking = false
                scope.launch { pager.scrollToPage(pageOf(month).coerceIn(0, PAGE_COUNT - 1)) }
            },
            onDismiss = { picking = false },
        )
    }
    selectedDay?.let { day -> DaySheet(day, onDismiss = { selectedDay = null }) }
}

private val syncTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

@Composable
private fun SyncStatusLine(header: SyncHeader) {
    val text = when {
        header.syncing -> stringResource(R.string.month_syncing)
        header.lastSuccessAt != null -> stringResource(
            R.string.month_last_sync,
            Instant.ofEpochMilli(header.lastSuccessAt).atZone(APP_ZONE).format(syncTimeFormat),
        )
        else -> stringResource(R.string.month_never_synced)
    }
    val pending = if (header.pendingWrites > 0) stringResource(R.string.month_pending_writes, header.pendingWrites) else null
    Text(
        listOfNotNull(text, pending).joinToString(" · "),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
