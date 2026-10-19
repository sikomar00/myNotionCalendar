package com.ysdoing.notioncal.feature.habit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.habit.DayMark
import com.ysdoing.notioncal.core.data.habit.HabitBoard
import com.ysdoing.notioncal.core.data.habit.HabitStats
import com.ysdoing.notioncal.core.data.habit.HabitSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** HB-02·HB-04 습관 탭. 습관마다 최근 7일(누르면 체크·해제), 상태 문구, 이번 주·이번 달, 이번 달 잔디. */
@Composable
fun HabitRoute(onOpenSettings: () -> Unit, viewModel: HabitViewModel = hiltViewModel()) {
    val board by viewModel.board.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    HabitScreen(board, onToggle = viewModel::toggle, onOpenSettings = onOpenSettings, blocked = blocked, onBlockedShown = viewModel::dismissBlocked)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitScreen(
    board: HabitBoard?,
    onToggle: (habitId: String, date: LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
    blocked: Boolean = false,
    onBlockedShown: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val blockedText = stringResource(R.string.habit_blocked)
    LaunchedEffect(blocked) {
        if (blocked) {
            onBlockedShown()
            snackbar.showSnackbar(blockedText)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(R.string.habit_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (board != null && board.habits.isNotEmpty()) {
                            Text(
                                stringResource(R.string.habit_today_count, board.doneToday, board.habits.size),
                                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.habit_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            board == null -> Unit
            board.habits.isEmpty() -> EmptyHabits(onOpenSettings, Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("habitList")) {
                items(board.habits, key = { it.habit.pageId }) { summary ->
                    HabitCard(summary, board.today, board.doneDates[summary.habit.pageId].orEmpty(), onToggle)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyHabits(onOpenSettings: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.habit_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.habit_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.habit_open_settings)) }
    }
}

@Composable
private fun HabitCard(summary: HabitSummary, today: LocalDate, done: Set<LocalDate>, onToggle: (String, LocalDate) -> Unit) {
    val habit = summary.habit
    val status = summary.status
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("habit")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            habit.emoji?.let { Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 8.dp)) }
            Column(Modifier.weight(1f)) {
                Text(habit.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // HB-04: '어제 쉼 · 오늘 하면 이어짐'은 오늘 칸과 함께 강조(누를 곳을 가리키므로 강조색).
                Text(
                    HabitStats.label(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.highlightToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("habitLabel"),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            status.last7.forEach { mark ->
                DayCell(habit.name, mark, isToday = mark.date == today, highlight = mark.date == today && status.highlightToday, Modifier.weight(1f)) {
                    onToggle(habit.pageId, mark.date)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.habit_week_count, status.weekCount, status.weekTarget),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.habit_month_count, status.monthCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        MonthGrass(today, done, status.monthCount)
    }
}

/** 최근 7일 칸 하나. 누르면 그날 체크·해제(HB-02). 한 날은 칠한 네모 + ✓(색만으로 구분하지 않음, A11Y-03). */
@Composable
private fun DayCell(name: String, mark: DayMark, isToday: Boolean, highlight: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val weekday = mark.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    val description = stringResource(R.string.habit_day, name, mark.date.monthValue, mark.date.dayOfMonth, weekday) +
        if (mark.beforeStart) ", " + stringResource(R.string.habit_before_start) else ""
    Column(
        modifier
            .heightIn(min = 56.dp)
            .toggleable(value = mark.done, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description }
            .testTag(if (isToday) "habitToday" else "habitDay"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (isToday) stringResource(R.string.habit_today) else weekday,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                isToday -> scheme.onSurface
                mark.date.dayOfWeek == DayOfWeek.SUNDAY -> SundayColor
                else -> scheme.onSurfaceVariant
            },
            fontWeight = if (isToday) FontWeight.Bold else null,
        )
        Spacer(Modifier.height(4.dp))
        val shape = MaterialTheme.shapes.small
        Box(
            Modifier
                .size(34.dp)
                .then(
                    when {
                        mark.done -> Modifier.background(scheme.primary, shape)
                        highlight -> Modifier.border(2.dp, scheme.primary, shape)
                        mark.beforeStart -> Modifier.border(1.dp, scheme.outlineVariant, shape)
                        else -> Modifier.border(1.dp, scheme.outline, shape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (mark.done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
            } else {
                Text(
                    mark.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (mark.beforeStart) scheme.onSurfaceVariant else scheme.onSurface,
                )
            }
        }
    }
}

/** 이번 달 잔디(HB-03 4×4와 같은 줄): 1일부터 말일까지 한 줄. 한 날은 강조색, 지난 빈 날은 선 색, 아직 안 온 날은 빈칸. */
@Composable
private fun MonthGrass(today: LocalDate, done: Set<LocalDate>, monthCount: Int) {
    val scheme = MaterialTheme.colorScheme
    val description = stringResource(R.string.habit_month_grass, today.monthValue, monthCount)
    Row(Modifier.fillMaxWidth().height(10.dp).clearAndSetSemantics { contentDescription = description }) {
        for (day in 1..today.lengthOfMonth()) {
            val date = today.withDayOfMonth(day)
            val color = when {
                date in done -> scheme.primary
                date.isAfter(today) -> Color.Transparent
                else -> scheme.outlineVariant
            }
            Box(Modifier.weight(1f).fillMaxSize().padding(horizontal = 1.dp).background(color, MaterialTheme.shapes.extraSmall))
        }
    }
}

/** 일요일 글자색(앱 달력·위젯과 같은 값, 흰 배경 5.6:1·#191919 위 6.3:1). */
private val SundayColor: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFF6B6B) else Color(0xFFC62828)
