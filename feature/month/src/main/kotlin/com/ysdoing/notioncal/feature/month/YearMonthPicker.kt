package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.YearMonth

/** 연도 목록 범위. */
private val YEARS = 1900..2100

/** 월·연도 버튼: 좁은 대화상자에서도 "10월"이 줄바꿈되지 않게 안쪽 여백을 줄인다. */
private val PickButtonPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

/**
 * MV-03 연·월 선택기. 가운데 연도를 누르면 연도 목록(스크롤)으로 바뀌고, 고르면 그 해의 월 격자로 돌아온다.
 * ‹ ›는 한 해씩 옮긴다.
 */
@Composable
fun YearMonthPicker(current: YearMonth, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(current.year) }
    var choosingYear by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.month_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { year = (year - 1).coerceIn(YEARS) }, enabled = !choosingYear) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.month_pick_prev_year))
                    }
                    val chooseYear = stringResource(R.string.month_pick_choose_year)
                    TextButton(
                        onClick = { choosingYear = !choosingYear },
                        modifier = Modifier.semantics { contentDescription = chooseYear },
                    ) {
                        Text(stringResource(R.string.month_pick_year, year), style = MaterialTheme.typography.titleMedium)
                        Icon(if (choosingYear) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                    }
                    IconButton(onClick = { year = (year + 1).coerceIn(YEARS) }, enabled = !choosingYear) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.month_pick_next_year))
                    }
                }
                if (choosingYear) {
                    YearGrid(
                        selected = year,
                        onPick = {
                            year = it
                            choosingYear = false
                        },
                    )
                } else {
                    MonthGrid(year, current, onPick)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.month_cancel)) } },
    )
}

@Composable
private fun MonthGrid(year: Int, current: YearMonth, onPick: (YearMonth) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..12).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    PickButton(
                        label = stringResource(R.string.month_pick_month, m),
                        highlighted = year == current.year && m == current.monthValue,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(YearMonth.of(year, m)) },
                    )
                }
            }
        }
    }
}

/** 연도 목록: 3열 격자, 고른 연도가 보이게 스크롤해 연다. */
@Composable
private fun YearGrid(selected: Int, onPick: (Int) -> Unit) {
    val years = YEARS.toList()
    // 고른 연도가 둘째 줄쯤 오도록 한 줄(3개) 앞에서 시작한다.
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = (years.indexOf(selected) - 3).coerceAtLeast(0))
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        modifier = Modifier.fillMaxWidth().height(232.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(years, key = { it }) { y ->
            PickButton(
                label = stringResource(R.string.month_pick_year, y),
                highlighted = y == selected,
                onClick = { onPick(y) },
            )
        }
    }
}

@Composable
private fun PickButton(label: String, highlighted: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (highlighted) {
        FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = PickButtonPadding) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PickButtonPadding) { Text(label, maxLines = 1) }
    }
}
