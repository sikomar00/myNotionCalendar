package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.EditableEvent
import com.ysdoing.notioncal.core.designsystem.rememberDiscardGuard
import java.time.LocalDate

/** 퀵 시트가 처음 보여줄 화면. */
sealed interface QuickSheetStart {
    /** WI-01 날짜 탭: 그날 목록 + 추가 입력칸. */
    data class Day(val date: LocalDate) : QuickSheetStart

    /** WI-02 + 버튼: 추가 입력칸만. */
    data class Add(val date: LocalDate) : QuickSheetStart

    /** WI-05 일정 탭: 편집. */
    data class Edit(val pageId: String) : QuickSheetStart
}

/**
 * 퀵 시트 본문. 위젯에서 여는 반투명 Activity와 앱의 날짜 시트(MV-04)가 같이 쓴다.
 * [onFinished]: 추가·편집·삭제를 마친 뒤(추가 모드·편집 모드에서 시트를 닫을 때) 부른다.
 */
@Composable
fun QuickSheetContent(
    start: QuickSheetStart,
    viewModel: QuickSheetViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    /** 스크롤 내용 맨 아래 여백(시트가 내비게이션 바와 겹칠 때). */
    bottomInset: Dp = 0.dp,
    /** ED-04: 편집·추가 중 저장하지 않은 내용이 있는지. 시트를 닫기 전에 '변경을 버릴까요?'를 묻는 데 쓴다. */
    onDirtyChange: (Boolean) -> Unit = {},
) {
    // 날짜 모드에서 일정을 누르면 같은 시트 안에서 편집으로 넘어가고, 뒤로 가면 목록으로 돌아온다.
    var editing by rememberSaveable { mutableStateOf((start as? QuickSheetStart.Edit)?.pageId) }
    // ED-04: 시트 안에서 편집을 떠날 때(뒤로 화살표, 적다 만 추가를 두고 다른 일정 열기)도 고친 내용이 있으면 먼저 묻는다.
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val leaveGuard = rememberDiscardGuard(onClose = { pendingLeave?.invoke(); pendingLeave = null })
    val reportDirty: (Boolean) -> Unit = { leaveGuard.dirty = it; onDirtyChange(it) }
    val leave = { action: () -> Unit ->
        pendingLeave = action
        if (leaveGuard.mayClose()) { action(); pendingLeave = null }
    }
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val defaultSource by viewModel.defaultSourceId.collectAsStateWithLifecycle()

    // 일정이 많은 날·속성 편집기가 많은 일정도 끝까지 보이게 시트 안에서 스크롤한다.
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val pageId = editing
        if (pageId != null) {
            // 읽는 중(null)과 일정이 없음(Found(null))을 가른다: 없으면 안내와 닫기를 보인다(빈 시트만 남지 않게).
            val lookup by remember(pageId) { viewModel.observeEvent(pageId).map(::Found) }.collectAsState(null)
            val item = lookup?.item
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (start !is QuickSheetStart.Edit) {
                    IconButton(onClick = { leave { editing = null } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.qs_back))
                    }
                }
                Text(stringResource(R.string.qs_edit_title), style = MaterialTheme.typography.titleLarge)
            }
            val current = item
            val pinnedIds by viewModel.pinnedDdays.collectAsStateWithLifecycle()
            val back = { if (start is QuickSheetStart.Edit) onFinished() else editing = null }
            // 직접 지우는 중에는 일정이 먼저 사라져도 '찾을 수 없음'을 띄우지 않는다(시트가 곧 닫힌다).
            var trashing by remember(pageId) { mutableStateOf(false) }
            if (lookup != null && current == null && !trashing) {
                Text(stringResource(R.string.qs_event_missing), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = back) { Text(stringResource(R.string.qs_close)) }
            }
            if (current != null) {
                EventEditorForm(
                    item = current,
                    onSave = { change -> viewModel.save(pageId, change, onSaved = back) },
                    onDelete = {
                        trashing = true
                        viewModel.trash(pageId, onTrashed = back)
                    },
                    pinned = viewModel.isDdayPinned(pageId, pinnedIds),
                    onPinChange = { viewModel.setDdayPinned(pageId, it) },
                    onDirtyChange = reportDirty,
                )
            }
            return@Column
        }
        when (start) {
            is QuickSheetStart.Add -> {
                Text(stringResource(R.string.qs_add_title), style = MaterialTheme.typography.titleLarge)
                AddForm(
                    start.date, sources, defaultSource, autoFocus = autoFocus,
                    onAdd = { viewModel.add(it, onAdded = onFinished) },
                    onAddRepeated = { viewModel.addAll(it, onAdded = onFinished) },
                    onDirtyChange = reportDirty,
                )
            }
            is QuickSheetStart.Day -> {
                LaunchedEffect(start.date) { viewModel.showDay(start.date) }
                // 지금 연 날짜의 값만 쓴다(다른 날을 열면 첫 화면에 직전 날의 값이 남아 있다).
                val events = viewModel.dayEvents.collectAsStateWithLifecycle().value.on(start.date, emptyList())
                val holiday = viewModel.dayHoliday.collectAsStateWithLifecycle().value.on(start.date, null)
                // 공휴일 이름은 날짜 옆에 따로(일요일 빨강), 가운뎃점으로 잇지 않는다.
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dayTitle(start.date), style = MaterialTheme.typography.titleLarge)
                    holiday?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error) }
                }
                val weather = viewModel.dayWeather.collectAsStateWithLifecycle().value.on(start.date, DayWeather())
                if (!weather.isEmpty) DayWeatherStrip(weather)
                AddForm(
                    start.date, sources, defaultSource, autoFocus = autoFocus, onAdd = { viewModel.add(it) }, onAddRepeated = { viewModel.addAll(it) },
                    onDirtyChange = reportDirty,
                )
                HorizontalDivider()
                DayList(
                    events = events,
                    onToggle = { event, done -> viewModel.setDone(event.pageId, done) },
                    onOpen = { event -> leave { editing = event.pageId } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is QuickSheetStart.Edit -> Unit // 위에서 처리
        }
    }
}

/** 편집할 일정을 찾아본 결과([item]이 null이면 없음). 찾는 중(아직 결과 없음)과 가르려고 감싼다. */
private data class Found(val item: EditableEvent?)
