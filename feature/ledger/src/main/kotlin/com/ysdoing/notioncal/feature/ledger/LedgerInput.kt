package com.ysdoing.notioncal.feature.ledger

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.ledger.Flow
import com.ysdoing.notioncal.core.data.ledger.LedgerAddResult
import com.ysdoing.notioncal.core.data.ledger.LedgerRepository
import com.ysdoing.notioncal.core.designsystem.NotionCalTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

/** LD-07 빠른 입력 열기(위젯 '+'·날짜 탭, 💰 탭). 날짜마다 data URI를 달리해 PendingIntent가 서로 덮지 않게 한다. */
fun ledgerInputIntent(context: Context, date: LocalDate): Intent =
    Intent(AppIntents.ACTION_LEDGER_INPUT)
        .setPackage(context.packageName)
        .setData("notioncal://ledger/add/$date".toUri())
        .putExtra(AppIntents.EXTRA_DATE, date.toString())

/** LD-07 입력 시트 상태: 대분류 칩(최근 쓴 순)과 저장 결과. */
@HiltViewModel
class LedgerInputViewModel @Inject constructor(private val ledger: LedgerRepository) : ViewModel() {
    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    private val _result = MutableStateFlow<LedgerAddResult?>(null)
    val result: StateFlow<LedgerAddResult?> = _result.asStateFlow()

    fun loadCategories(flow: Flow) {
        viewModelScope.launch { _categories.value = ledger.categoryChoices(flow) }
    }

    fun save(date: LocalDate, amount: Long, flow: Flow, category: String?, title: String) {
        viewModelScope.launch { _result.value = ledger.add(date, amount, flow, category, title) }
    }
}

/**
 * LD-07 입력 내용. 금액 칸에 바로 포커스(숫자 자판), 지출·수입 토글, 대분류 칩(최근 쓴 첫 칩을 미리 고름), 내용(선택).
 * 금액을 넣은 뒤 칩 하나·저장 하나, 탭 2번 이하로 저장한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerInputContent(
    date: LocalDate,
    today: LocalDate,
    categories: List<String>,
    onFlowChange: (Flow) -> Unit,
    onSave: (amount: Long, flow: Flow, category: String?, title: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    noLedger: Boolean = false,
) {
    var amountText by rememberSaveable { mutableStateOf("") }
    var flow by rememberSaveable { mutableStateOf(Flow.OUT) }
    var picked by rememberSaveable(categories) { mutableStateOf(categories.firstOrNull()) }
    var title by rememberSaveable { mutableStateOf("") }
    val amount = amountText.filter(Char::isDigit).toLongOrNull() ?: 0L
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        val kind = stringResource(if (date.isAfter(today)) R.string.ledger_input_planned else R.string.ledger_input_actual)
        Text(stringResource(R.string.ledger_input_title, date.monthValue, date.dayOfMonth, weekday, kind), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SingleChoiceSegmentedButtonRow {
            listOf(Flow.OUT to R.string.ledger_input_out, Flow.IN to R.string.ledger_input_in).forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = flow == value,
                    onClick = {
                        flow = value
                        onFlowChange(value)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    icon = {},
                ) { Text(stringResource(label)) }
            }
        }
        OutlinedTextField(
            value = amountText,
            onValueChange = { text -> amountText = text.filter(Char::isDigit).take(MAX_DIGITS) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("ledgerAmount"),
            label = { Text(stringResource(R.string.ledger_input_amount)) },
            suffix = { Text(stringResource(R.string.ledger_input_won)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        )
        if (categories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(categories) { name ->
                    FilterChip(selected = picked == name, onClick = { picked = if (picked == name) null else name }, label = { Text(name) })
                }
            }
        }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ledger_input_memo)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (amount > 0) onSave(amount, flow, picked, title) }),
        )
        if (noLedger) Text(stringResource(R.string.ledger_input_no_ledger), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.ledger_input_cancel)) }
            Button(onClick = { onSave(amount, flow, picked, title) }, enabled = amount > 0) { Text(stringResource(R.string.ledger_input_save)) }
        }
    }
}

private const val MAX_DIGITS = 12

/** LD-07 위젯 위에 뜨는 반투명 입력 시트. 저장하면 Room에 먼저 반영(위젯 즉시 갱신)되고 노션은 아웃박스로 간다. */
@AndroidEntryPoint
class LedgerInputActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val today = LocalDate.now(APP_ZONE)
        val date = intent.getStringExtra(AppIntents.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        setContent {
            val dynamicColor by remember { settings.settings.map { it.dynamicColor } }.collectAsStateWithLifecycle(false)
            NotionCalTheme(dynamicColor) {
                val viewModel: LedgerInputViewModel = hiltViewModel()
                val categories by viewModel.categories.collectAsStateWithLifecycle()
                val result by viewModel.result.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.loadCategories(Flow.OUT) }
                LaunchedEffect(result) { if (result == LedgerAddResult.SAVED) finish() }
                Box(
                    Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().imePadding()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        tonalElevation = 6.dp,
                    ) {
                        LedgerInputContent(
                            date = date,
                            today = today,
                            categories = categories,
                            onFlowChange = viewModel::loadCategories,
                            onSave = { amount, flow, category, title -> viewModel.save(date, amount, flow, category, title) },
                            onCancel = ::finish,
                            modifier = Modifier.navigationBarsPadding().padding(top = 8.dp),
                            noLedger = result == LedgerAddResult.NO_LEDGER,
                        )
                    }
                }
            }
        }
    }
}
