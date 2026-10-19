package com.ysdoing.notioncal.feature.dday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.dday.Dday
import com.ysdoing.notioncal.core.data.dday.DdayMode
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** 디데이 화면 상태. [results]는 '일정 찾아 고정' 검색 결과(Room 제목 검색). */
data class DdayUiState(
    val pins: List<Dday> = emptyList(),
    val removedNotice: Set<String> = emptySet(),
    val query: String = "",
    val results: List<CalendarEvent> = emptyList(),
    val pinnedIds: Set<String> = emptySet(),
)

/** 화면 동작 묶음(테스트에서 바꿔 끼운다). */
data class DdayActions(
    val onBack: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onPin: (String) -> Unit = {},
    val onUnpin: (String) -> Unit = {},
    val onMode: (String, DdayMode) -> Unit = { _, _ -> },
    val onDismissNotice: () -> Unit = {},
)

/** DD-01·02 설정 → 디데이: 고정 목록(표기·세는 방식·해제), 일정 찾아 고정, 노션에서 지워져 풀린 디데이 안내. */
@Composable
fun DdayRoute(onBack: () -> Unit, viewModel: DdayViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DdayScreen(
        state,
        DdayActions(
            onBack = onBack,
            onQuery = viewModel::setQuery,
            onPin = viewModel::pin,
            onUnpin = viewModel::unpin,
            onMode = viewModel::setMode,
            onDismissNotice = viewModel::dismissNotice,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DdayScreen(state: DdayUiState, actions: DdayActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dday_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.dday_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().imePadding().testTag("ddayList"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.removedNotice.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.dday_removed_notice, state.removedNotice.sorted().joinToString(", ")),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = actions.onDismissNotice) { Text(stringResource(R.string.dday_dismiss)) }
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.dday_pinned)) }
            if (state.pins.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.dday_empty),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.pins, key = { "pin-${it.pageId}" }) { dday -> PinRow(dday, actions) }

            item {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                SectionTitle(stringResource(R.string.dday_find))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = actions.onQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("ddaySearch"),
                    label = { Text(stringResource(R.string.dday_find_hint)) },
                    singleLine = true,
                )
            }
            items(state.results, key = { "result-${it.pageId}" }) { event ->
                val pinned = event.pageId in state.pinnedIds
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(event.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dateText(event.startDate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilterChip(
                        selected = pinned,
                        onClick = { if (pinned) actions.onUnpin(event.pageId) else actions.onPin(event.pageId) },
                        label = { Text(stringResource(if (pinned) R.string.dday_pinned_chip else R.string.dday_pin_chip)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}

/** 고정 한 줄: 큰 표기('D-12'), 제목·날짜, 세는 방식 칩, 해제. 지난 디데이가 숨김 기간을 넘으면 '위젯에서 숨김'. */
@Composable
private fun PinRow(dday: Dday, actions: DdayActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("ddayPin")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dday.label?.text ?: stringResource(R.string.dday_waiting),
                modifier = Modifier.widthIn(min = 72.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (dday.label?.hidden == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(dday.title ?: stringResource(R.string.dday_untitled), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val note = listOfNotNull(
                    dday.start?.let(::dateText),
                    stringResource(R.string.dday_hidden).takeIf { dday.label?.hidden == true },
                ).joinToString(" · ")
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { actions.onUnpin(dday.pageId) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dday_unpin, dday.title.orEmpty()))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(DdayMode.DOWN to R.string.dday_mode_down, DdayMode.UP to R.string.dday_mode_up, DdayMode.DAYS to R.string.dday_mode_days)
                .forEach { (mode, label) ->
                    FilterChip(selected = dday.mode == mode, onClick = { actions.onMode(dday.pageId, mode) }, label = { Text(stringResource(label)) })
                }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** '10/31 금' */
internal fun dateText(date: LocalDate): String =
    "${date.monthValue}/${date.dayOfMonth} ${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}"
