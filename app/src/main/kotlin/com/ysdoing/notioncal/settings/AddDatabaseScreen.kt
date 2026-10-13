package com.ysdoing.notioncal.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.sync.PropertyOption

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddDatabaseRoute(onDone: () -> Unit, viewModel: AddDatabaseViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ui.registered) { if (ui.registered) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_db_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.add_db_link_label)) },
                supportingText = { Text(stringResource(R.string.add_db_link_hint)) },
                singleLine = true,
                // URL 자판(영문)으로 열어 한글 입력 상태에서 링크가 깨지지 않게 한다.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            OutlinedButton(onClick = { viewModel.inspect(link) }, enabled = link.isNotBlank() && !ui.loading) {
                Text(stringResource(R.string.add_db_inspect))
            }
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.invalidLink) Text(stringResource(R.string.error_invalid_link), color = MaterialTheme.colorScheme.error)
            ui.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }

            val inspection = ui.inspection ?: return@Column
            Text(inspection.name, style = MaterialTheme.typography.titleLarge)
            if (inspection.dataSources.size > 1) {
                PropertyDropdown(
                    label = stringResource(R.string.add_db_data_source),
                    options = inspection.dataSources.map { PropertyOption(it.first, it.second, "data_source") },
                    selectedId = inspection.dataSourceId,
                    allowNone = false,
                    onSelect = { it?.let(viewModel::selectDataSource) },
                )
            }
            val choice = ui.choice
            if (choice == null) {
                Text(stringResource(R.string.add_db_no_date), color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Text(
                stringResource(R.string.add_db_preset_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val c = inspection.candidates
            PropertyDropdown(stringResource(R.string.add_db_title_prop), c.titles, choice.titlePropId, allowNone = false) { id ->
                id?.let { viewModel.updateChoice { ch -> ch.copy(titlePropId = it) } }
            }
            PropertyDropdown(stringResource(R.string.add_db_date_prop), c.dates, choice.datePropId, allowNone = false) { id ->
                id?.let { viewModel.updateChoice { ch -> ch.copy(datePropId = it) } }
            }
            PropertyDropdown(stringResource(R.string.add_db_done_prop), c.done, choice.donePropId, allowNone = true) { id ->
                viewModel.updateChoice { it.copy(donePropId = id) }
            }
            PropertyDropdown(stringResource(R.string.add_db_color_prop), c.colors, choice.colorPropId, allowNone = true) { id ->
                viewModel.updateChoice { it.copy(colorPropId = id) }
            }
            PropertyDropdown(stringResource(R.string.add_db_sort_prop), c.sorts, choice.sortPropId, allowNone = true) { id ->
                viewModel.updateChoice { it.copy(sortPropId = id) }
            }

            Text(stringResource(R.string.add_db_color), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NotionColors.palette.forEach { (name, hex) ->
                    val selected = ui.colorHex == hex
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(NotionColors.argb(hex)))
                            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .semantics {
                                contentDescription = name
                                this.selected = selected
                            }
                            .clickable { viewModel.setColor(hex) },
                    )
                }
            }
            Button(onClick = viewModel::register, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_db_register))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertyDropdown(
    label: String,
    options: List<PropertyOption>,
    selectedId: String?,
    allowNone: Boolean,
    onSelect: (String?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val none = stringResource(R.string.add_db_none)
    val selectedName = options.firstOrNull { it.id == selectedId }?.name ?: none
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text(none) }, onClick = {
                    onSelect(null)
                    expanded = false
                })
            }
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.name) }, onClick = {
                    onSelect(option.id)
                    expanded = false
                })
            }
        }
    }
}
