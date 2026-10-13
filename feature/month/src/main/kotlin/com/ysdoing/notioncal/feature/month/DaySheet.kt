package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** MV-04 날짜 시트. Phase 1은 읽기 전용이고 체크·편집·추가는 Phase 3에서 붙인다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaySheet(date: LocalDate, events: List<CalendarEvent>, onDismiss: () -> Unit) {
    val dayEvents = events.filter { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                text = stringResource(
                    R.string.day_sheet_title,
                    date.monthValue,
                    date.dayOfMonth,
                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (dayEvents.isEmpty()) {
                Text(
                    stringResource(R.string.day_sheet_empty),
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.padding(top = 12.dp)) {
                    items(dayEvents, key = { it.pageId }) { DayEventRow(it) }
                }
            }
            Text(
                stringResource(R.string.day_sheet_read_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val shortDate = DateTimeFormatter.ofPattern("M/d")

@Composable
private fun DayEventRow(event: CalendarEvent) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(NotionColors.argb(event.colorHex))))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (event.done) TextDecoration.LineThrough else null,
                color = if (event.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = whenText(event),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // A11Y-03: 완료는 색만이 아니라 아이콘으로도 구분한다.
        if (event.done) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.day_sheet_done),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun whenText(event: CalendarEvent): String {
    val range = if (event.isMultiDay) "${event.startDate.format(shortDate)}~${event.lastDate.format(shortDate)}" else null
    val start = event.startTime
    if (event.isAllDay || start == null) {
        return listOfNotNull(stringResource(R.string.day_sheet_all_day), range).joinToString(" · ")
    }
    val from = start.atZone(APP_ZONE).format(timeFormat)
    val to = event.endTime?.atZone(APP_ZONE)?.format(timeFormat)
    return listOfNotNull(if (to != null) "$from–$to" else from, range).joinToString(" · ")
}
