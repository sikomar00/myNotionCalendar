package com.ysdoing.notioncal.feature.quicksheet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SyncRequester
import com.ysdoing.notioncal.core.data.WidgetConfigRepository
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewFilter
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewPreset
import com.ysdoing.notioncal.core.data.view.ViewPresets
import com.ysdoing.notioncal.core.data.view.ViewQuickInput
import com.ysdoing.notioncal.core.data.view.ViewRepository
import com.ysdoing.notioncal.core.data.view.ViewWriter
import com.ysdoing.notioncal.core.designsystem.NotionCalTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement

/** 입력 시트가 쓰는 보기 정보(작게). 보기 행 전체를 메인 스레드에서 다루지 않게, 저장소 흐름을 줄이는 일은 IO에서 한다. */
data class ViewAddForm(
    val name: String = "",
    val canCreate: Boolean = true,
    val hasDate: Boolean = false,
    val prefill: ViewAddPrefill = ViewAddPrefill(),
    /** 한 줄 입력을 읽을 때 쓰는 보기의 쓰기 정보(속성 ID·종류·선택지). */
    val columns: List<ViewColumn> = emptyList(),
)

/** AD-01: 새 항목 시트에 미리 채워 보이는 값. [columns]는 채운 속성들(고칠 수 있는 편집기가 붙고), [values]는 그 값(속성 ID → 값). */
data class ViewAddPrefill(val columns: List<ViewColumn> = emptyList(), val values: Map<String, JsonElement> = emptyMap())

/** 보기 필터의 단순 같음 조건(체크박스·선택·상태·숫자)을 미리 채울 값으로. 제목·날짜는 시트가 따로 정하니 뺀다. 조건이 없으면 빈 값이다. */
internal fun prefillOf(board: ViewBoard): ViewAddPrefill {
    val values = ViewFilter.parse(board.filterJson).equalsConditions(board.columnInfo)
    val columns = board.columnInfo.filter { it.id in values && it.writable && it.type != "title" && it.type != "date" }
    return ViewAddPrefill(columns, values.filterKeys { id -> columns.any { it.id == id } })
}

/** 채운 값(속성 ID → 값)을 프로세스가 죽었다 살아나도 남게 JSON 글자로 저장한다. */
private val PropsSaver = androidx.compose.runtime.saveable.Saver<Map<String, JsonElement>, String>(save = { ViewJson.encodeTyped(it) }, restore = { ViewJson.typed(it) })

/**
 * VW-08 노션 보기 위젯의 새 항목 입력 시트 상태. 저장은 쓰기 큐에 쌓을 뿐이고 노션에는 동기화 쪽이 보낸다.
 * 시트를 열 때 노션과 맞춘다(사용자 결정: 앱·시트를 열 때 + 위젯 새로고침, 연달아 열면 [SheetSyncThrottle]이 줄인다).
 */
@HiltViewModel
class ViewAddViewModel @Inject constructor(
    private val views: ViewRepository,
    private val writer: ViewWriter,
    private val widgetConfigs: WidgetConfigRepository,
    sync: SyncRequester,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** 쌓는 중이거나 쌓았다(시트가 닫힌다). 연타하거나 완료 키와 버튼이 겹쳐도 한 번만 만든다. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** 쌓지 못했다(보기가 지워졌거나 아직 쓰기 정보를 받지 못함). */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    init {
        SheetSyncThrottle.Shared.request(sync)
    }

    fun form(viewId: String): Flow<ViewAddForm> =
        views.observe(viewId).map { board -> ViewAddForm(board?.name.orEmpty(), board?.canCreate ?: false, board?.hasDateColumn == true, board?.let(::prefillOf) ?: ViewAddPrefill(), board?.columnInfo.orEmpty()) }.flowOn(io)

    /** 시트의 제목과 값 묶음을 이 위젯의 프리셋으로 저장한다(이름이 같으면 바꾼다). 이름이 비었거나 새 이름인데 꽉 찼으면 저장하지 않고 false다. */
    suspend fun savePreset(appWidgetId: Int, name: String, props: Map<String, JsonElement>): Boolean {
        val config = widgetConfigs.get(appWidgetId)
        val updated = ViewPresets.add(config.presetsJson, ViewPreset(name, props)) ?: return false
        widgetConfigs.save(config.copy(presetsJson = updated))
        return true
    }

    fun save(viewId: String, title: String, date: LocalDate?, props: Map<String, JsonElement> = emptyMap()) {
        if (_saving.value) return
        _saving.value = true
        _failed.value = false
        viewModelScope.launch {
            val queued = writer.create(viewId, title, date, props)
            _saved.value = queued
            _failed.value = !queued
            _saving.value = queued // 실패하면 다시 시도할 수 있게 풀고, 성공하면 시트가 닫힐 때까지 막아 둔다
        }
    }
}

private enum class DateChoice { NONE, TODAY, TOMORROW, PICK }

/** 새 항목 입력 내용: 제목(바로 포커스), 날짜 칩(없음·오늘·내일·직접 고르기, 날짜 열이 있는 보기만), 취소·추가. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewAddContent(
    viewName: String,
    hasDate: Boolean,
    today: LocalDate,
    onSave: (title: String, date: LocalDate?, props: Map<String, JsonElement>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    canAdd: Boolean = true,
    saving: Boolean = false,
    failed: Boolean = false,
    prefill: ViewAddPrefill = ViewAddPrefill(),
    /** AD-03 한 줄 입력을 읽을 속성들. 비면 읽지 않는다. */
    columns: List<ViewColumn> = emptyList(),
    onSavePreset: (suspend (title: String, props: Map<String, JsonElement>) -> Boolean)? = null,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var choice by rememberSaveable { mutableStateOf(DateChoice.NONE) }
    var pickedIso by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    // 필터에서 채운 값은 시트가 열린 뒤 보기를 읽어 오면 도착한다. 도착하면 그 값으로 시작한다.
    var props by rememberSaveable(stateSaver = PropsSaver) { mutableStateOf(prefill.values) }
    var edited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(prefill) { if (!edited) props = prefill.values } // 사용자가 고친 뒤(화면이 다시 만들어진 뒤 값이 또 와도)에는 덮지 않는다
    var presetSaved by rememberSaveable { mutableStateOf<Boolean?>(null) } // null: 아직, true: 저장함, false: 저장하지 못함
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // AD-03: 쓰는 글에서 날짜·금액·옵션을 읽는다. 필터에서 채운 속성은 사용자가 정한 값이라 건드리지 않는다. 칩 목록은 끈 것까지 담아 다시 켤 수 있게 한다.
    var ignoredText by rememberSaveable { mutableStateOf("") }
    val ignored = ignoredKeys(ignoredText)
    val readable = columns.filter { c -> prefill.columns.none { it.id == c.id } }
    val candidates = remember(title, readable, today) { ViewQuickInput.parse(title, readable, today) }
    val quick = remember(title, readable, today, ignoredText) { ViewQuickInput.parse(title, readable, today, ignored) }
    val allProps = quick.props + props
    val date = when (choice) {
        DateChoice.NONE -> quick.date
        DateChoice.TODAY -> today
        DateChoice.TOMORROW -> today.plusDays(1)
        DateChoice.PICK -> pickedIso?.let(LocalDate::parse)
    }
    val save = { if (title.isNotBlank() && canAdd && !saving) onSave(quick.title, date, allProps) }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.view_add_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (viewName.isNotBlank()) {
            Text(viewName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            label = { Text(stringResource(R.string.qs_title_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        // 만들 수 없을 때(보기가 지워졌거나 쓰기 정보를 아직 못 받음)와 쌓지 못했을 때는 이유를 글자로 알린다.
        if (!canAdd) Text(stringResource(R.string.view_add_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (failed) Text(stringResource(R.string.view_add_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        QuickChips(candidates.chips, ignored) { ignoredText = toggled(ignoredText, it) }
        if (hasDate) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = choice == DateChoice.NONE, onClick = { choice = DateChoice.NONE }, label = { Text(stringResource(R.string.view_add_no_date)) })
                FilterChip(selected = choice == DateChoice.TODAY, onClick = { choice = DateChoice.TODAY }, label = { Text(stringResource(R.string.view_add_today)) })
                FilterChip(selected = choice == DateChoice.TOMORROW, onClick = { choice = DateChoice.TOMORROW }, label = { Text(stringResource(R.string.view_add_tomorrow)) })
                FilterChip(
                    selected = choice == DateChoice.PICK,
                    onClick = { picking = true },
                    label = { Text(pickedIso?.let { DateTimeFormatter.ofPattern("M/d").format(LocalDate.parse(it)) } ?: stringResource(R.string.view_add_pick)) },
                )
            }
        }
        if (prefill.columns.isNotEmpty()) {
            Text(stringResource(R.string.view_add_prefilled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            prefill.columns.forEach { column ->
                key(column.id) {
                    PropField(
                        column, props[column.id],
                        onChange = { value ->
                            edited = true
                            props = if (value == null) props - column.id else props + (column.id to value)
                        },
                        onInvalid = {},
                    )
                }
            }
        }
        when (presetSaved) {
            true -> Text(stringResource(R.string.view_add_preset_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            false -> Text(stringResource(R.string.view_add_preset_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // AD-05: 위젯에서 연 시트면 지금 제목(= 프리셋 이름)과 값을 이 위젯의 프리셋으로 저장한다(만들지는 않는다).
            if (onSavePreset != null) {
                TextButton(
                    onClick = { scope.launch { presetSaved = onSavePreset(quick.title.trim(), allProps) } },
                    enabled = title.isNotBlank(),
                ) { Text(stringResource(R.string.view_add_preset_save)) }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.qs_cancel)) }
            Button(onClick = { save() }, enabled = title.isNotBlank() && canAdd && !saving) { Text(stringResource(R.string.qs_add)) }
        }
    }
    if (picking) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        pickedIso = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        choice = DateChoice.PICK
                    }
                    picking = false
                }) { Text(stringResource(R.string.view_add_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.qs_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** VW-08 위젯 위에 뜨는 반투명 새 항목 입력 시트. 바깥을 누르면 닫히고, 추가하면 쓰기 큐에 쌓고 닫는다. */
@AndroidEntryPoint
class ViewAddActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val viewId = intent.getStringExtra(AppIntents.EXTRA_VIEW_ID)
        if (viewId == null) {
            finish()
            return
        }
        val today = LocalDate.now(APP_ZONE)
        val appWidgetId = intent.getIntExtra(AppIntents.EXTRA_APPWIDGET_ID, 0)
        setContent {
            val dynamicColor by remember { settings.settings.map { it.dynamicColor } }.collectAsStateWithLifecycle(false)
            NotionCalTheme(dynamicColor) {
                val viewModel: ViewAddViewModel = hiltViewModel()
                val form by remember(viewId) { viewModel.form(viewId) }.collectAsStateWithLifecycle(ViewAddForm())
                val saved by viewModel.saved.collectAsStateWithLifecycle()
                val saving by viewModel.saving.collectAsStateWithLifecycle()
                val failed by viewModel.failed.collectAsStateWithLifecycle()
                LaunchedEffect(saved) { if (saved) finish() }
                BottomSheetFrame(onClose = ::finish) {
                    ViewAddContent(
                        viewName = form.name,
                        hasDate = form.hasDate,
                        today = today,
                        onSave = { title, date, props -> viewModel.save(viewId, title, date, props) },
                        onCancel = ::finish,
                        modifier = Modifier.navigationBarsPadding().padding(top = 8.dp),
                        canAdd = form.canCreate,
                        saving = saving,
                        failed = failed,
                        prefill = form.prefill,
                        columns = form.columns,
                        onSavePreset = if (appWidgetId > 0) { title, props -> viewModel.savePreset(appWidgetId, title, props) } else null,
                    )
                }
            }
        }
    }
}
