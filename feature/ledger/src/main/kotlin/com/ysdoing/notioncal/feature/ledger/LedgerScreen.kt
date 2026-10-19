package com.ysdoing.notioncal.feature.ledger

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.ledger.DayMoney
import com.ysdoing.notioncal.core.data.ledger.DuplicateGroup
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.data.ledger.LedgerTx
import com.ysdoing.notioncal.core.data.ledger.MoneyFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** 💰 탭(LD-03~09): 이번 달 요약·주 예산 줄·월 그리드, 중복·확인 필요·정리 대상 카드. [onDayClick]은 LD-07 입력(B 묶음). */
@Composable
fun LedgerRoute(onOpenSettings: () -> Unit, onDayClick: ((LocalDate) -> Unit)? = null, viewModel: LedgerViewModel = hiltViewModel()) {
    val board by viewModel.board.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    LedgerScreen(
        board = board,
        actions = LedgerActions(
            onOpenSettings = onOpenSettings,
            onShiftMonth = viewModel::shiftMonth,
            onResolve = viewModel::resolveDuplicates,
            onKeepBoth = viewModel::keepBoth,
            onDelete = viewModel::trash,
            onConvert = viewModel::convertPlanned,
            onDayClick = onDayClick,
        ),
    )
}

/** 화면 동작 묶음(테스트에서 바꿔 끼운다). */
data class LedgerActions(
    val onOpenSettings: () -> Unit = {},
    val onShiftMonth: (Long) -> Unit = {},
    val onResolve: (DuplicateGroup) -> Unit = {},
    val onKeepBoth: (DuplicateGroup) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onConvert: (String) -> Unit = {},
    val onDayClick: ((LocalDate) -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(board: LedgerBoard?, actions: LedgerActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ledger_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.ledger_settings))
                    }
                },
            )
        },
    ) { padding ->
        when {
            board == null -> Unit
            !board.registered -> EmptyLedger(actions.onOpenSettings, Modifier.padding(padding))
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("ledgerList")) {
                item { MonthNav(board.month, actions.onShiftMonth) }
                item { Summary(board) }
                item { WeekBudget(board) }
                val s = board.summary
                if (s.duplicates.isNotEmpty()) item { Duplicates(s.duplicates, actions) }
                if (s.needsReview.isNotEmpty()) item { Review(s.needsReview, actions) }
                if (s.matchedManual.isNotEmpty()) item { Matched(s.matchedManual.map { it.first }, actions) }
                item { MonthGrid(board, actions.onDayClick) }
            }
        }
    }
}

@Composable
private fun EmptyLedger(onOpenSettings: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ledger_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.ledger_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.ledger_open_settings)) }
    }
}

@Composable
private fun MonthNav(month: YearMonth, onShift: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.ledger_prev))
        }
        Text(
            stringResource(R.string.ledger_month, month.year, month.monthValue),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onShift(1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.ledger_next))
        }
    }
}

/** LD-03 위쪽 줄: 이번 달 지출·예정·수입·월 예산 남음, '10/8까지 반영'. */
@Composable
private fun Summary(board: LedgerBoard) {
    val s = board.summary
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("ledgerSummary")) {
        Text(stringResource(R.string.ledger_month_spend, board.month.monthValue), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(MoneyFormat.full(s.monthSpend), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ledger_planned, MoneyFormat.short(s.monthPlanned)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.ledger_income, MoneyFormat.short(s.monthIncome)), style = MaterialTheme.typography.bodyMedium, color = incomeColor())
        }
        val remaining = s.monthRemaining
        Text(
            when {
                remaining == null -> stringResource(R.string.ledger_no_budget)
                remaining >= 0 -> stringResource(R.string.ledger_month_left, MoneyFormat.short(remaining))
                else -> stringResource(R.string.ledger_month_over, MoneyFormat.short(-remaining))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (remaining != null && remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            s.asOf?.let { stringResource(R.string.ledger_as_of, it.monthValue, it.dayOfMonth) } ?: stringResource(R.string.ledger_as_of_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("ledgerAsOf"),
        )
    }
}

/** LD-04 주 예산 줄: '이번 주 8.2만 남음 · 3일' + 막대. 넘으면 경고색과 '초과' 글자(색만으로 구분하지 않음). */
@Composable
private fun WeekBudget(board: LedgerBoard) {
    val s = board.summary
    val budget = s.weekBudget
    val remaining = s.weekRemaining
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ledgerWeek")) {
        val over = remaining != null && remaining < 0
        Text(
            when {
                remaining == null -> stringResource(R.string.ledger_week_spend, MoneyFormat.short(s.weekSpend))
                over -> stringResource(R.string.ledger_week_over, MoneyFormat.short(-remaining), s.weekDaysLeft)
                else -> stringResource(R.string.ledger_week_left, MoneyFormat.short(remaining), s.weekDaysLeft)
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (budget != null && budget > 0 && remaining != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { ((budget - remaining).toFloat() / budget).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun IssueCard(title: String, note: String, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun TxLine(tx: LedgerTx) {
    Text(
        stringResource(R.string.ledger_row, tx.date.monthValue, tx.date.dayOfMonth, tx.title.ifBlank { stringResource(R.string.ledger_untitled) }, MoneyFormat.full(abs(tx.amount))),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** LD-08 '중복 N건': 확인하면 먼저 만든 행만 남기거나, 실제 거래 두 건이면 둘 다 유지. 자동 삭제는 하지 않는다. */
@Composable
private fun Duplicates(groups: List<DuplicateGroup>, actions: LedgerActions) {
    IssueCard(stringResource(R.string.ledger_duplicates, groups.sumOf { it.extra.size }), stringResource(R.string.ledger_duplicates_note), "ledgerDuplicates") {
        groups.forEach { group ->
            Column(Modifier.padding(top = 8.dp)) {
                TxLine(group.keep)
                Row {
                    TextButton(onClick = { actions.onResolve(group) }) { Text(stringResource(R.string.ledger_keep_first)) }
                    TextButton(onClick = { actions.onKeepBoth(group) }) { Text(stringResource(R.string.ledger_keep_both)) }
                }
            }
        }
    }
}

/** LD-09 '확인 필요': 반영 기준일이 지난 예정. 한 번 탭으로 삭제 또는 실제로 전환. */
@Composable
private fun Review(rows: List<LedgerTx>, actions: LedgerActions) {
    IssueCard(stringResource(R.string.ledger_review, rows.size), stringResource(R.string.ledger_review_note), "ledgerReview") {
        rows.forEach { tx ->
            Column(Modifier.padding(top = 8.dp)) {
                TxLine(tx)
                Row {
                    TextButton(onClick = { actions.onDelete(tx.pageId) }) { Text(stringResource(R.string.ledger_delete)) }
                    TextButton(onClick = { actions.onConvert(tx.pageId) }) { Text(stringResource(R.string.ledger_convert)) }
                }
            }
        }
    }
}

/** LD-10 '정리 대상': 같은 거래가 들어와 합계에서 뺀 앱입력 행. 삭제로 정리한다. */
@Composable
private fun Matched(rows: List<LedgerTx>, actions: LedgerActions) {
    IssueCard(stringResource(R.string.ledger_matched, rows.size), stringResource(R.string.ledger_matched_note), "ledgerMatched") {
        rows.forEach { tx ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TxLine(tx) }
                TextButton(onClick = { actions.onDelete(tx.pageId) }) { Text(stringResource(R.string.ledger_delete)) }
            }
        }
    }
}

/**
 * LD-03 월 그리드: 지난 날·오늘 칸은 지출(진하게), 앞날 칸은 예정(흐리게 + 밑줄), 수입은 초록 '+'.
 * 1만 미만은 그대로, 1만 이상은 만 단위.
 */
@Composable
private fun MonthGrid(board: LedgerBoard, onDayClick: ((LocalDate) -> Unit)?) {
    val start = board.gridStart
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).testTag("ledgerGrid")) {
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { i ->
                Text(
                    board.weekStart.plus(i.toLong()).getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        repeat(6) { week ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val date = start.plusDays(week * 7L + col)
                    DayCell(date, board, board.summary.days[date] ?: DayMoney(), onDayClick, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, board: LedgerBoard, money: DayMoney, onDayClick: ((LocalDate) -> Unit)?, modifier: Modifier) {
    val inMonth = YearMonth.from(date) == board.month
    val future = date.isAfter(board.today)
    val amount = if (future) money.planned else money.spend
    val description = listOfNotNull(
        stringResource(R.string.ledger_day, date.monthValue, date.dayOfMonth),
        amount.takeIf { it != 0L }?.let {
            stringResource(if (future) R.string.ledger_day_planned else R.string.ledger_day_spend, MoneyFormat.full(it))
        },
        money.income.takeIf { it != 0L }?.let { stringResource(R.string.ledger_day_income, MoneyFormat.full(it)) },
    ).joinToString(", ")
    Column(
        modifier
            .heightIn(min = 52.dp)
            .then(if (onDayClick != null) Modifier.clickable { onDayClick(date) } else Modifier)
            .clearAndSetSemantics { contentDescription = description }
            .testTag("ledgerDay")
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val isToday = date == board.today
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else null,
            color = when {
                isToday -> MaterialTheme.colorScheme.primary
                inMonth -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (amount != 0L) {
            Text(
                MoneyFormat.short(amount),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (future) null else FontWeight.SemiBold,
                textDecoration = if (future) TextDecoration.Underline else null,
                color = if (future) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        if (money.income != 0L) {
            Text("+" + MoneyFormat.short(money.income), style = MaterialTheme.typography.labelSmall, color = incomeColor(), maxLines = 1)
        }
    }
}

/** 수입 초록(노션 초록, 라이트 #0F7B6C 5.4:1, 다크 #4DAB9A). */
@Composable
private fun incomeColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF4DAB9A) else Color(0xFF0F7B6C)
