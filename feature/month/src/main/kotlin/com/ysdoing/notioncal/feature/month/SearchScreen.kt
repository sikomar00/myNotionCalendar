package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.NotionColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import com.ysdoing.notioncal.core.data.calendar.DONE_MARK
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics

/** 검색어와 그 결과. 입력이 바뀐 직후 이전 결과를 새 검색어의 결과로 오해하지 않게 함께 둔다. */
data class SearchResults(val query: String, val events: List<CalendarEvent>)

/** MV-10 제목 검색. 로컬 캐시(Room)만 읽고, 입력이 150ms 멈추면 찾는다. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val events: EventRepository,
    private val health: AppHealth,
    private val clock: Clock,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchResults?> = _query
        .debounce(SEARCH_DELAY_MS)
        .mapLatest { q -> SearchResults(q, events.search(q, LocalDate.now(clock.withZone(APP_ZONE)))) }
        .catch { error ->
            health.report(error)
            emit(SearchResults("", emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) {
        _query.value = text
    }

    private companion object {
        const val SEARCH_DELAY_MS = 150L
    }
}

@Composable
fun SearchRoute(onBack: () -> Unit, onPick: (CalendarEvent) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    SearchScreen(query, results, onQueryChange = viewModel::setQuery, onBack = onBack, onPick = onPick)
}

private val searchDate = DateTimeFormatter.ofPattern("yyyy. M. d.")
private val searchTime = DateTimeFormatter.ofPattern("H:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    query: String,
    results: SearchResults?,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (CalendarEvent) -> Unit,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_back))
                    }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear))
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        val shown = results?.takeIf { it.query.trim() == query.trim() }
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                query.isBlank() -> Hint(stringResource(R.string.search_empty_query))
                shown == null -> Unit // 입력 중(아직 검색 전)
                shown.events.isEmpty() -> Hint(stringResource(R.string.search_no_result))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown.events, key = { it.pageId }) { event ->
                        SearchRow(event, onPick)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchRow(event: CalendarEvent, onPick: (CalendarEvent) -> Unit) {
    val date = buildString {
        append(event.startDate.format(searchDate))
        append(" (").append(event.startDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)).append(")")
        if (event.isMultiDay) append(" – ").append(event.lastDate.format(searchDate))
        event.startTime?.let { append(" ").append(it.atZone(APP_ZONE).format(searchTime)) }
    }
    val doneLabel = stringResource(R.string.a11y_done)
    val color = Color(NotionColors.argb(event.colorHex))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(event) }
            .semantics { if (event.done) stateDescription = doneLabel }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A11Y-03: 완료는 색 점 대신 체크.
        if (event.done) {
            Text(DONE_MARK, color = color, modifier = Modifier.width(10.dp))
        } else {
            Spacer(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (event.done) 0.55f else 1f)) {
            Text(
                event.title.ifBlank { stringResource(R.string.search_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (event.done) TextDecoration.LineThrough else null,
            )
            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
