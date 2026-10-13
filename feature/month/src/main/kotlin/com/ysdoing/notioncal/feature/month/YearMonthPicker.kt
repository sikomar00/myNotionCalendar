package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.YearMonth

/** MV-03 연·월 선택기. */
@Composable
fun YearMonthPicker(current: YearMonth, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(current.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.month_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { year-- }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.month_pick_prev_year))
                    }
                    Text(stringResource(R.string.month_pick_year, year), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { year++ }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.month_pick_next_year))
                    }
                }
                (1..12).chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { m ->
                            val label = stringResource(R.string.month_pick_month, m)
                            val modifier = Modifier.weight(1f)
                            if (year == current.year && m == current.monthValue) {
                                FilledTonalButton(onClick = { onPick(YearMonth.of(year, m)) }, modifier = modifier) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { onPick(YearMonth.of(year, m)) }, modifier = modifier) { Text(label) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.month_cancel)) } },
    )
}
