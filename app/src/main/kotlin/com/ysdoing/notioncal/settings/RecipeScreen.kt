package com.ysdoing.notioncal.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.recipe.Recipe
import com.ysdoing.notioncal.core.data.recipe.RecipeConfig
import com.ysdoing.notioncal.core.data.recipe.RecipeDraft
import com.ysdoing.notioncal.core.data.recipe.RecipeView
import com.ysdoing.notioncal.core.data.recipe.Recipes
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.designsystem.CheckboxRow
import com.ysdoing.notioncal.core.designsystem.RadioRow
import com.ysdoing.notioncal.widget.RecipePreview

/** 핀(홈 화면에 놓기) 요청의 진행: 아직 안 눌렀다 / 요청 중 / 런처가 위치 고르기를 열었다 / 이 런처는 앱에서 바로 놓기를 지원하지 않는다. */
internal enum class PinState { IDLE, BUSY, REQUESTED, UNSUPPORTED }

/** 레시피 화면 상태. [views]가 null이면 등록한 보기를 읽는 중이고, [config]가 null이면 아직 만들 수 없는 선택이다. */
internal data class RecipeUi(val views: List<RecipeView>?, val draft: RecipeDraft, val config: RecipeConfig?, val pin: PinState)

internal class RecipeActions(
    val onRecipe: (Recipe) -> Unit,
    val onView: (String) -> Unit,
    val onBoard: (String) -> Unit,
    val onAmount: (String) -> Unit,
    val onCategory: (String) -> Unit,
    val onCheck: (String) -> Unit,
    val onBudget: (String) -> Unit,
    val onAddView: () -> Unit,
    val onPin: () -> Unit,
)

/** 설정 › 위젯 › 레시피로 새 위젯 만들기(RC-01): 레시피 → 보기·속성 → 미리보기 → 홈 화면에 추가. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeRoute(onBack: () -> Unit, onAddView: () -> Unit, viewModel: RecipeViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        RecipeActions(
            viewModel::selectRecipe, viewModel::selectView, viewModel::selectBoard, viewModel::selectAmount,
            viewModel::selectCategory, viewModel::toggleCheck, viewModel::setBudget, onAddView, viewModel::pin,
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recipe_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            RecipeContent(ui, actions) { ui.config?.let { RecipePreview(it, preview.names, preview.boards, preview.weekStart) } }
        }
    }
}

@Composable
internal fun RecipeContent(ui: RecipeUi, actions: RecipeActions, preview: @Composable () -> Unit) {
    val views = ui.views
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (views == null) {
            Text(stringResource(R.string.recipe_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val recipe = ui.draft.recipe
        RecipeChips(recipe, actions.onRecipe)
        Text(stringResource(describe(recipe)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val candidates = Recipes.candidates(recipe, views)
        if (candidates.isEmpty()) NoMatchingView(recipe, actions.onAddView) else PicksSection(ui.draft, candidates, actions)
        if (ui.config != null) {
            Text(stringResource(R.string.recipe_section_preview), style = MaterialTheme.typography.titleSmall)
            preview()
        }
        PinButton(ui.pin, ui.config != null, actions.onPin)
    }
}

@Composable
private fun RecipeChips(selected: Recipe, onSelect: (Recipe) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Recipe.values().forEach { recipe ->
            FilterChip(selected = recipe == selected, onClick = { onSelect(recipe) }, label = { Text(stringResource(nameOf(recipe))) })
        }
    }
}

@Composable
private fun NoMatchingView(recipe: Recipe, onAddView: () -> Unit) {
    Text(stringResource(R.string.recipe_no_view, stringResource(needs(recipe))), color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = onAddView) { Text(stringResource(R.string.recipe_add_view)) }
}

/** 고른 레시피에 맞는 보기와 그 보기의 속성 고르기. 보기를 바꾸면 속성은 새 보기에서 다시 고른다(화면 상태가 [RecipeDraft]다). */
@Composable
private fun PicksSection(draft: RecipeDraft, candidates: List<RecipeView>, actions: RecipeActions) {
    val picks = draft.picks
    Section(R.string.recipe_section_view)
    candidates.forEach { view ->
        RadioRow(stringResource(R.string.recipe_view_label, view.name.ifBlank { stringResource(R.string.recipe_untitled) }, stringResource(typeName(view.type))), picks.viewId == view.viewId) { actions.onView(view.viewId) }
    }
    val view = candidates.firstOrNull { it.viewId == picks.viewId } ?: return
    when (draft.recipe) {
        Recipe.TASKS -> BoardPick(picks.boardViewId, candidates.filter { it.type == "board" && it.viewId != picks.viewId }, actions.onBoard)
        Recipe.HABITS -> {
            DateNote(view)
            Section(R.string.recipe_section_checks)
            view.columns.filter { it.type == "checkbox" }.forEach { column -> CheckboxRow(column.name, column.id in picks.checkProps) { actions.onCheck(column.id) } }
        }
        Recipe.LEDGER -> {
            DateNote(view)
            ColumnPick(R.string.recipe_section_amount, view, "number", picks.amountProp, actions.onAmount)
            ColumnPick(R.string.recipe_section_category, view, setOf("select", "status"), picks.categoryProp, actions.onCategory, noneLabel = R.string.recipe_category_none)
            OutlinedTextField(
                value = draft.budgetText,
                onValueChange = actions.onBudget,
                label = { Text(stringResource(R.string.recipe_budget)) },
                supportingText = { Text(stringResource(R.string.recipe_budget_note)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 새 항목의 날짜는 보기의 첫 날짜 열에 기록되고 타일도 그 열을 읽는다(고르지 않는다). 어느 열인지 알려 준다. */
@Composable
private fun DateNote(view: RecipeView) {
    val date = view.columns.firstOrNull { it.type == "date" } ?: return
    Text(stringResource(R.string.recipe_date_note, date.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 일정·할 일의 상태 보드 고르기: 보드 보기가 하나도 없으면 줄을 그리지 않는다. */
@Composable
private fun BoardPick(selectedId: String, boards: List<RecipeView>, onBoard: (String) -> Unit) {
    if (boards.isEmpty()) return
    Section(R.string.recipe_section_board)
    RadioRow(stringResource(R.string.recipe_board_none), selectedId.isEmpty()) { onBoard("") }
    boards.forEach { board -> RadioRow(board.name.ifBlank { stringResource(R.string.recipe_untitled) }, selectedId == board.viewId) { onBoard(board.viewId) } }
}

/** 한 종류(들)의 속성을 라디오로 고른다. [noneLabel]이 있으면 '없음' 줄을 맨 앞에 둔다(선택 속성). */
@Composable
private fun ColumnPick(title: Int, view: RecipeView, type: String, selectedId: String, onPick: (String) -> Unit, noneLabel: Int? = null) =
    ColumnPick(title, view, setOf(type), selectedId, onPick, noneLabel)

@Composable
private fun ColumnPick(title: Int, view: RecipeView, types: Set<String>, selectedId: String, onPick: (String) -> Unit, noneLabel: Int? = null) {
    Section(title)
    if (noneLabel != null) RadioRow(stringResource(noneLabel), selectedId.isEmpty()) { onPick("") }
    view.columns.filter { it.type in types }.forEach { column: ViewColumn -> RadioRow(column.name, selectedId == column.id) { onPick(column.id) } }
}

@Composable
private fun PinButton(pin: PinState, valid: Boolean, onPin: () -> Unit) {
    Button(onClick = onPin, enabled = valid && pin != PinState.BUSY, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recipe_add_home)) }
    when (pin) {
        PinState.REQUESTED -> Text(stringResource(R.string.recipe_pin_requested), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        PinState.UNSUPPORTED -> Text(stringResource(R.string.recipe_pin_unsupported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PinState.IDLE, PinState.BUSY -> Unit
    }
}

@Composable
private fun Section(title: Int) = Text(stringResource(title), style = MaterialTheme.typography.titleSmall)

private fun nameOf(recipe: Recipe): Int = when (recipe) {
    Recipe.TASKS -> R.string.recipe_name_tasks
    Recipe.HABITS -> R.string.recipe_name_habits
    Recipe.LEDGER -> R.string.recipe_name_ledger
}

private fun describe(recipe: Recipe): Int = when (recipe) {
    Recipe.TASKS -> R.string.recipe_desc_tasks
    Recipe.HABITS -> R.string.recipe_desc_habits
    Recipe.LEDGER -> R.string.recipe_desc_ledger
}

private fun needs(recipe: Recipe): Int = when (recipe) {
    Recipe.TASKS -> R.string.recipe_needs_tasks
    Recipe.HABITS -> R.string.recipe_needs_habits
    Recipe.LEDGER -> R.string.recipe_needs_ledger
}

private fun typeName(type: String): Int = when (type) {
    "table" -> R.string.recipe_type_table
    "list" -> R.string.recipe_type_list
    "board" -> R.string.recipe_type_board
    "calendar" -> R.string.recipe_type_calendar
    "gallery" -> R.string.recipe_type_gallery
    "timeline" -> R.string.recipe_type_timeline
    else -> R.string.recipe_type_other
}
