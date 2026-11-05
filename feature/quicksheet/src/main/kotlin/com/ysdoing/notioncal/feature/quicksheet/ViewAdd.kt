package com.ysdoing.notioncal.feature.quicksheet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetConfigRepository
import com.ysdoing.notioncal.core.data.view.InputForm
import com.ysdoing.notioncal.core.data.view.InputForms
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewFilter
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewPreset
import com.ysdoing.notioncal.core.data.view.ViewPresets
import com.ysdoing.notioncal.core.data.view.ViewQuickInput
import com.ysdoing.notioncal.core.data.view.ViewRepository
import com.ysdoing.notioncal.core.data.view.ViewWriter
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import com.ysdoing.notioncal.core.designsystem.NotionCalTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    /** AD-02: 이 위젯이 이 보기에 정해 둔 입력 양식. 없으면 예전과 같다. */
    val input: InputForm? = null,
    /** 이 시트에서 프리셋을 저장·보일 수 있는가: 위젯에서 열었고 그 위젯의 배치가 프리셋 줄을 그리는 배치일 때만(`WidgetLayout.presetsApply`: 단일, 또는 보기가 하나뿐인 섹션). */
    val canPreset: Boolean = false,
    /** Phase 11h: 새 항목의 날짜 열에 이미 항목이 있는 날짜들(하루 한 줄 가드). */
    val takenDates: Set<LocalDate> = emptySet(),
)

/** AD-01: 새 항목 시트에 미리 채워 보이는 값. [columns]는 채운 속성들(고칠 수 있는 편집기가 붙고), [values]는 그 값(속성 ID → 값). */
data class ViewAddPrefill(val columns: List<ViewColumn> = emptyList(), val values: Map<String, JsonElement> = emptyMap())

/** 보기 필터의 단순 같음 조건(체크박스·선택·상태·숫자)을 미리 채울 값으로. 제목·날짜는 시트가 따로 정하니 뺀다. 조건이 없으면 빈 값이다. */
internal fun prefillOf(board: ViewBoard): ViewAddPrefill {
    val values = ViewFilter.parse(board.filterJson).equalsConditions(board.columnInfo)
    val columns = InputForm.editable(board.columnInfo).filter { it.id in values }
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

    /** [appWidgetId]가 0이면 위젯 없이 연 시트다(양식·프리셋 없음). */
    fun form(viewId: String, appWidgetId: Int): Flow<ViewAddForm> {
        val widget: Flow<WidgetConfig?> = if (appWidgetId > 0) widgetConfigs.observe(appWidgetId) else flowOf(null)
        return views.observe(viewId).combine(widget) { board, config ->
            ViewAddForm(
                board?.name.orEmpty(), board?.canCreate ?: false, board?.hasDateColumn == true, board?.let(::prefillOf) ?: ViewAddPrefill(), board?.formColumns.orEmpty(),
                input = config?.let { InputForms.get(it.formJson, viewId) },
                canPreset = config != null && WidgetLayout.parse(config.layoutJson).presetsApply,
                takenDates = board?.takenDates().orEmpty(),
            )
        }.flowOn(io)
    }

    /** 보이는 양식 속성의 지금 값을 이 위젯·보기의 기본값으로 저장한다(값이 null이면 기본값을 지운다, 안 보인 속성은 그대로). 그 보기에 양식이 없으면 저장하지 않고 false다. */
    suspend fun saveDefaults(appWidgetId: Int, viewId: String, values: Map<String, JsonElement?>): Boolean {
        val config = widgetConfigs.get(appWidgetId)
        val updated = InputForms.withDefaults(config.formJson, viewId, values) ?: return false
        widgetConfigs.save(config.copy(formJson = updated))
        return true
    }

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

/** 새 항목 입력 내용: 제목(바로 포커스), 날짜 칩(없음·오늘·내일·직접 고르기, 날짜 열이 있는 보기만), 입력 양식의 속성, 취소·추가. */
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
    /** AD-05 프리셋 만들기: 항목은 만들지 않고 프리셋만 저장한다(날짜 칩·추가 버튼 없음). */
    presetOnly: Boolean = false,
    prefill: ViewAddPrefill = ViewAddPrefill(),
    /** AD-03 한 줄 입력을 읽을 속성들. 비면 읽지 않는다. */
    columns: List<ViewColumn> = emptyList(),
    /** AD-02 입력 양식. 있으면 양식의 속성만 이 순서로, 기본값으로 시작해 보인다. */
    input: InputForm? = null,
    /** 하루 한 줄 양식([InputForm.oncePerDay])이 이 날짜에 이미 항목이 있는지 가리는 날짜들. */
    takenDates: Set<LocalDate> = emptySet(),
    /** '이 값을 기본값으로': 보이는 양식 속성의 지금 값(비웠으면 null)을 넘긴다. 없으면 버튼이 없다. */
    onSaveDefaults: (suspend (Map<String, JsonElement?>) -> Boolean)? = null,
    onSavePreset: (suspend (title: String, props: Map<String, JsonElement>) -> Boolean)? = null,
    /** Phase 12a 캘린더 날짜 칸에서 열었을 때 그 날짜: 날짜 칩이 '직접 고른' 그날로 시작한다(제목에서 읽은 날짜보다 우선). */
    initialDate: LocalDate? = null,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var choice by rememberSaveable { mutableStateOf(if (initialDate != null) DateChoice.PICK else DateChoice.AUTO) }
    var pickedIso by rememberSaveable { mutableStateOf(initialDate?.toString()) }
    var picking by remember { mutableStateOf(false) }
    // 필터에서 채운 값과 양식의 기본값은 시트가 열린 뒤 보기·위젯 설정을 읽어 오면 도착한다. 사용자가 고치기 전에는 도착한 시작 값이 그대로 보이고,
    // 고친 뒤에는 고친 값을 쓴다(그 뒤 값이 또 와도 덮지 않는다). 편집기는 시작 값이 바뀌면 새로 만들어 글자 칸도 새 값으로 시작한다.
    val plan = remember(columns, hasDate, presetOnly, input, prefill) { sheetPlan(columns, hasDate && !presetOnly, input, prefill) }
    var props by rememberSaveable(stateSaver = PropsSaver) { mutableStateOf(plan.start) }
    var edited by rememberSaveable { mutableStateOf(false) }
    val current = if (edited) props else plan.start
    val change = { id: String, value: JsonElement? ->
        edited = true
        props = if (value == null) current - id else current + (id to value)
    }
    var presetSaved by rememberSaveable { mutableStateOf<Boolean?>(null) } // null: 아직, true: 저장함, false: 저장하지 못함
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // AD-03: 쓰는 글에서 날짜·금액·옵션을 읽는다. 미리 채운 속성(필터·양식 기본값)은 사용자가 정한 값이라 건드리지 않는다.
    // 끈 칩의 키는 저장해 두되 지금 글에서 읽히는 것만 적용하고(옛 글자의 끈 칩이 남아 보이지 않게 숨은 채 작용하지 않도록), 끈 칩은 다시 켤 수 있게 보인다.
    // 직접 고른 날짜 칩은 글에서 읽은 날짜를 이긴다: 그 글자는 날짜로 읽지 않고 제목에 남긴다.
    var ignoredText by rememberSaveable { mutableStateOf("") }
    val candidates = remember(title, plan.readable, today) { ViewQuickInput.parse(title, plan.readable, today) }
    val ignored = ignoredKeys(ignoredText).let { if (choice == DateChoice.AUTO) it else it + QUICK_DATE_KEY }.filter { key -> candidates.chips.any { it.key == key } }.toSet()
    val quick = remember(title, plan.readable, today, ignored) { if (ignored.isEmpty()) candidates else ViewQuickInput.parse(title, plan.readable, today, ignored) }
    val allProps = quick.props + current
    // 레시피 양식(Phase 11h): 날짜·제목·하루 한 줄 막힘은 순수 규칙([resolveAdd])이 정한다.
    val resolved = remember(choice, pickedIso, quick, plan, today, takenDates, presetOnly) { resolveAdd(choice, pickedIso, quick.date, quick.title, plan, today, takenDates, presetOnly) }
    val canSave = (title.isNotBlank() || resolved.titleHint != null) && canAdd && !saving && !resolved.dayTaken
    val save = { if (canSave) onSave(resolved.title, resolved.date, allProps) }
    val savePreset: () -> Unit = { onSavePreset?.let { store -> scope.launch { presetSaved = store(quick.title.trim(), allProps) } } }
    val done = { if (presetOnly) { if (title.isNotBlank()) savePreset() } else save() }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(if (presetOnly) R.string.view_add_preset_title else R.string.view_add_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (viewName.isNotBlank()) {
            Text(viewName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            label = { Text(resolved.titleHint?.let { stringResource(R.string.view_add_title_date_label, it) } ?: stringResource(R.string.qs_title_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
        )
        // 만들 수 없을 때(보기가 지워졌거나 쓰기 정보를 아직 못 받음)와 쌓지 못했을 때는 이유를 글자로 알린다.
        if (!canAdd && !presetOnly) Text(stringResource(R.string.view_add_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (failed) Text(stringResource(R.string.view_add_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (resolved.dayTaken) Text(stringResource(R.string.view_add_day_taken), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        QuickChips((candidates.chips + quick.chips).distinctBy { it.key }, ignored) { key ->
            if (key == QUICK_DATE_KEY && choice != DateChoice.AUTO) {
                choice = DateChoice.AUTO // 글에서 읽은 날짜를 다시 켜면 직접 고른 날짜는 거둔다
                ignoredText = withoutKey(ignoredText, key)
            } else {
                ignoredText = toggled(ignoredText, key)
            }
        }
        if (plan.showDate) {
            val noDate = choice == DateChoice.EMPTY || (choice == DateChoice.AUTO && quick.date == null && !plan.dateToday)
            DateChips(choice, noDate, resolved.autoToday, pickedIso, onPick = { picking = true }) { chosen -> choice = chosen } // 날짜 없음(EMPTY)은 글에서 읽은 날짜도 쓰지 않는다
        }
        key(plan.start) {
            FormSection(plan.fields, current, change, onSaveDefaults)
            if (prefill.columns.isNotEmpty()) {
                Text(stringResource(R.string.view_add_prefilled), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PropEditors(prefill.columns, current, change)
            }
        }
        PresetStatus(presetSaved)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // AD-05: 위젯에서 연 시트면 지금 제목(= 프리셋 이름)과 값을 이 위젯의 프리셋으로 저장한다. 프리셋 만들기 모드는 저장만 하고 항목은 만들지 않는다.
            if (onSavePreset != null && !presetOnly) {
                TextButton(onClick = { savePreset() }, enabled = title.isNotBlank()) { Text(stringResource(R.string.view_add_preset_save)) }
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.qs_cancel)) }
            if (presetOnly) {
                Button(onClick = { savePreset() }, enabled = title.isNotBlank() && onSavePreset != null) { Text(stringResource(R.string.view_add_preset_save)) }
            } else {
                Button(onClick = { save() }, enabled = canSave) { Text(stringResource(R.string.qs_add)) }
            }
        }
    }
    if (picking) {
        PickDateDialog(initialIso = pickedIso, onPicked = { iso -> pickedIso = iso; choice = DateChoice.PICK }, onDismiss = { picking = false })
    }
}

/** VW-08 위젯 위에 뜨는 반투명 새 항목 입력 시트. 바깥을 누르면 닫히고, 추가하면 쓰기 큐에 쌓고 닫는다. */
@AndroidEntryPoint
class ViewAddActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    /**
     * 프리셋 만들기 모드: 프리셋을 저장하지 않고 이름과 값을 위젯 편집 화면에 돌려준다. 프리셋 목록은 편집 화면이 들고 있다가 '저장'에서 함께 저장하니
     * (화면에서 지운 프리셋을 되살리지 않고, 5개 상한도 화면의 목록으로 센다) 여기서 DB를 건드리지 않는다.
     */
    private fun finishWithPreset(title: String, props: Map<String, JsonElement>): Boolean {
        setResult(RESULT_OK, Intent().putExtra(AppIntents.EXTRA_PRESET_NAME, title).putExtra(AppIntents.EXTRA_PRESET_PROPS, ViewJson.encodeTyped(props)))
        finish()
        return true
    }

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
        val presetOnly = intent.getBooleanExtra(AppIntents.EXTRA_PRESET_ONLY, false) && appWidgetId > 0
        val initialDate = intent.getStringExtra(AppIntents.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        setContent {
            val dynamicColor by remember { settings.settings.map { it.dynamicColor } }.collectAsStateWithLifecycle(false)
            NotionCalTheme(dynamicColor) {
                val viewModel: ViewAddViewModel = hiltViewModel()
                val form by remember(viewId) { viewModel.form(viewId, appWidgetId) }.collectAsStateWithLifecycle(ViewAddForm())
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
                        input = form.input,
                        takenDates = form.takenDates,
                        presetOnly = presetOnly,
                        initialDate = initialDate,
                        onSaveDefaults = if (appWidgetId > 0 && !presetOnly) { values -> viewModel.saveDefaults(appWidgetId, viewId, values) } else null,
                        onSavePreset = when {
                            presetOnly -> { title, props -> finishWithPreset(title, props) }
                            form.canPreset -> { title, props -> viewModel.savePreset(appWidgetId, title, props) }
                            else -> null
                        },
                    )
                }
            }
        }
    }
}
