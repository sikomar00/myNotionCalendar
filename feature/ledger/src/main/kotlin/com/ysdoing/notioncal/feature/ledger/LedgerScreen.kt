package com.ysdoing.notioncal.feature.ledger

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.ysdoing.notioncal.core.data.ledger.DuplicateGroup
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.data.ledger.LedgerTx
import com.ysdoing.notioncal.core.data.ledger.MoneyFormat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * 💰 탭(LD-03~10): 이번 달 요약·주 예산 줄·월 그리드, 중복·확인 필요·정리 대상 카드.
 * 날짜를 누르면 그리드 아래에 그 날 거래 목록을 보이고, '거래 추가'·'이 날 추가'는 LD-07 입력 시트를 연다.
 */
@Composable
fun LedgerRoute(onOpenSettings: () -> Unit, viewModel: LedgerViewModel = hiltViewModel()) {
    val board by viewModel.board.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openInput: (LocalDate) -> Unit = { date -> context.startActivity(ledgerInputIntent(context, date)) }
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
            onAdd = openInput,
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
    val onAdd: ((LocalDate) -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(board: LedgerBoard?, actions: LedgerActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ledger_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                actions = {
                    // '거래 추가'(오늘)는 위쪽 막대에 둔다. 떠 있는 버튼은 날짜 목록의 금액을 가렸다.
                    val onAdd = actions.onAdd
                    if (board?.registered == true && onAdd != null) {
                        IconButton(onClick = { onAdd(board.today) }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.ledger_add))
                        }
                    }
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
            else -> {
                // 고른 날(처음은 오늘). 달을 바꾸면 그 달 안에 오늘이 있을 때만 오늘을 고른다.
                var selected by rememberSaveable(board.month) { mutableStateOf(board.today.takeIf { YearMonth.from(it) == board.month }) }
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val s = board.summary
                val gridIndex = 3 + listOf(s.duplicates, s.needsReview, s.matchedManual).count { it.isNotEmpty() }
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize().testTag("ledgerList"),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    item { MonthNav(board.month, actions.onShiftMonth) }
                    item { Summary(board) }
                    item { WeekBudget(board) }
                    if (s.duplicates.isNotEmpty()) item { Duplicates(s.duplicates, actions) }
                    if (s.needsReview.isNotEmpty()) item { Review(s.needsReview, actions) }
                    if (s.matchedManual.isNotEmpty()) item { Matched(s.matchedManual.map { it.first }, actions) }
                    item {
                        MonthGrid(board, selected) { date ->
                            selected = date
                            // 그리드를 화면 위로 올려 아래의 날짜 목록이 보이게 한다.
                            scope.launch { listState.animateScrollToItem(gridIndex) }
                        }
                    }
                    selected?.let { date -> item { DayEntries(date, s.entries[date].orEmpty(), actions.onAdd) } }
                }
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

/**
 * LD-03 위쪽: 이번 달 지출(크게)과 '10/8까지 반영', 아래 세 칸(예정·수입·월 예산). 칸은 라벨 위·값 아래로 나눠
 * 가운뎃점으로 잇지 않는다(DESIGN.md). 예산을 넘으면 라벨이 '월 예산 초과'로 바뀌고 경고색(색만으로 구분하지 않음).
 */
@Composable
private fun Summary(board: LedgerBoard) {
    val s = board.summary
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("ledgerSummary")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ledger_month_spend, board.month.monthValue),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                s.asOf?.let { stringResource(R.string.ledger_as_of, it.monthValue, it.dayOfMonth) } ?: stringResource(R.string.ledger_as_of_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ledgerAsOf"),
            )
        }
        Text(MoneyFormat.full(s.monthSpend), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat(stringResource(R.string.ledger_stat_planned), MoneyFormat.short(s.monthPlanned), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            Stat(stringResource(R.string.ledger_stat_income), "+" + MoneyFormat.short(s.monthIncome), incomeColor(), Modifier.weight(1f))
            val remaining = s.monthRemaining
            when {
                remaining == null -> Stat(stringResource(R.string.ledger_stat_budget), stringResource(R.string.ledger_stat_none), MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                remaining >= 0 -> Stat(stringResource(R.string.ledger_stat_left), MoneyFormat.short(remaining), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                else -> Stat(stringResource(R.string.ledger_stat_over), MoneyFormat.short(-remaining), MaterialTheme.colorScheme.error, Modifier.weight(1f))
            }
        }
    }
}

/** 요약 한 칸: 흑연 라벨 위, 값 아래. 낭독은 '예정 5만'처럼 한 번에. */
@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clearAndSetSemantics { contentDescription = "$label $value" }) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
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
            // 위젯 막대와 같은 모양: 둥근 트랙 위에 쓴 만큼 채운다(Material 진행 막대의 틈·끝 점 없이).
            val used = ((budget - remaining).toFloat() / budget).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(
                    Modifier.fillMaxWidth(used).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                        .background(if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                )
            }
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
