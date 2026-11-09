package com.ysdoing.notioncal.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.DesignTokens
import com.ysdoing.notioncal.core.data.ledger.DayEntry
import com.ysdoing.notioncal.core.data.ledger.DayMoney
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.data.ledger.MoneyFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * LD-03 월 그리드: 지난 날·오늘 칸은 지출(파랑 '-'), 앞날 칸은 예정(흐리게 + 밑줄 '-'), 수입은 초록 '+'.
 * 지출 '-'·파랑은 사용자 요구(2026-10-19).
 * 1만 미만은 그대로, 1만 이상은 만 단위.
 */
@Composable
internal fun MonthGrid(board: LedgerBoard, selected: LocalDate?, onDayClick: (LocalDate) -> Unit) {
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
        val noSpend = remember(board.summary) { board.summary.noSpendDays.orEmpty().toSet() }
        repeat(6) { week ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            WeekTotals(board.summary.weekMoney(start.plusDays(week * 7L)))
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val date = start.plusDays(week * 7L + col)
                    DayCell(date, board, board.summary.days[date] ?: DayMoney(), date in noSpend, date == selected, onDayClick, Modifier.weight(1f))
                }
            }
        }
    }
}

/** LD-15 주 합계 줄(뱅크샐러드식): 그 주 이 달 날짜의 수입(초록 '+')과 지출(파랑 '-')을 오른쪽 끝에. 둘 다 0이면 비운다. */
@Composable
private fun WeekTotals(money: DayMoney) {
    if (money.spend == 0L && money.income == 0L) return
    val description = stringResource(R.string.ledger_week_total_a11y, MoneyFormat.full(money.spend), MoneyFormat.full(money.income))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).testTag("ledgerWeekTotal").clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.End,
    ) {
        if (money.income != 0L) {
            Text(MoneyFormat.signed(money.income), style = MaterialTheme.typography.labelSmall, color = incomeColor(), maxLines = 1)
        }
        if (money.spend != 0L) {
            Text(
                MoneyFormat.signed(-money.spend, full = true),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = expenseColor(),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, board: LedgerBoard, money: DayMoney, noSpend: Boolean, isSelected: Boolean, onDayClick: (LocalDate) -> Unit, modifier: Modifier) {
    val inMonth = YearMonth.from(date) == board.month
    val future = date.isAfter(board.today)
    val amount = if (future) money.planned else money.spend
    val description = listOfNotNull(
        stringResource(R.string.ledger_day, date.monthValue, date.dayOfMonth),
        amount.takeIf { it != 0L }?.let {
            stringResource(if (future) R.string.ledger_day_planned else R.string.ledger_day_spend, MoneyFormat.full(it))
        },
        money.income.takeIf { it != 0L }?.let { stringResource(R.string.ledger_day_income, MoneyFormat.full(it)) },
        stringResource(R.string.ledger_day_no_spend).takeIf { noSpend },
    ).joinToString(", ")
    Column(
        modifier
            .heightIn(min = 52.dp)
            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small) else Modifier)
            .clickable { onDayClick(date) }
            .testTag("ledgerDay")
            .clearAndSetSemantics {
                contentDescription = description
                selected = isSelected
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val isToday = date == board.today
        // 무지출 날은 날짜 위에 점(뱅크샐러드와 같은 자리, 점이 없는 날도 같은 높이를 둔다).
        Box(Modifier.size(5.dp)) {
            if (noSpend) Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
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
                MoneyFormat.signed(-amount),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (future) null else FontWeight.SemiBold,
                textDecoration = if (future) TextDecoration.Underline else null,
                color = if (future) MaterialTheme.colorScheme.onSurfaceVariant else expenseColor(),
                maxLines = 1,
            )
        }
        if (money.income != 0L) {
            Text(MoneyFormat.signed(money.income), style = MaterialTheme.typography.labelSmall, color = incomeColor(), maxLines = 1)
        }
    }
}

private val isDark: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 수입 초록(DesignTokens.INCOME). */
@Composable
internal fun incomeColor(): Color = Color(if (isDark) DesignTokens.Dark.INCOME else DesignTokens.Light.INCOME)

/** 지출 파랑(DesignTokens.EXPENSE). */
@Composable
private fun expenseColor(): Color = Color(if (isDark) DesignTokens.Dark.EXPENSE else DesignTokens.Light.EXPENSE)

/**
 * 고른 날의 거래 목록: 그 날 칸 숫자를 이루는 행(시간 순). 지출 파랑 '-', 수입 초록 '+', 예정은 흐리게 + 밑줄.
 * '이 날 추가'는 LD-07 입력 시트를 그 날짜로 연다.
 */
@Composable
internal fun DayEntries(date: LocalDate, entries: List<DayEntry>, onAdd: ((LocalDate) -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ledgerDayEntries")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ledger_day_title, date.monthValue, date.dayOfMonth, date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (onAdd != null) TextButton(onClick = { onAdd(date) }) { Text(stringResource(R.string.ledger_day_add)) }
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.ledger_day_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.forEach { entry -> EntryRow(entry) }
    }
}

@Composable
private fun EntryRow(entry: DayEntry) {
    val tx = entry.tx
    val planned = entry.kind == DayEntry.Kind.PLANNED
    // 대분류·결제수단·상태는 가운뎃점 대신 사이를 띄워 늘어놓는다(DESIGN.md).
    val notes = listOfNotNull(
        tx.category,
        tx.payment,
        stringResource(R.string.ledger_entry_planned).takeIf { planned },
        stringResource(R.string.ledger_entry_manual).takeIf { tx.manual },
    )
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            tx.time.orEmpty(),
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(tx.title.ifBlank { stringResource(R.string.ledger_untitled) }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString(NOTE_GAP),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            MoneyFormat.signed(entry.value, full = true),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (planned) null else FontWeight.SemiBold,
            textDecoration = if (planned) TextDecoration.Underline else null,
            color = when (entry.kind) {
                DayEntry.Kind.PLANNED -> MaterialTheme.colorScheme.onSurfaceVariant
                DayEntry.Kind.INCOME -> incomeColor()
                DayEntry.Kind.SPEND -> expenseColor()
            },
        )
    }
}

/** 한 줄 안에서 덩이를 나누는 띄움(가운뎃점 대신, 전각 공백 하나). */
private const val NOTE_GAP = "\u3000"
