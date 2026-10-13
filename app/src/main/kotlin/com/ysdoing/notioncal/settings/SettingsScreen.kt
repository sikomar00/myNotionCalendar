package com.ysdoing.notioncal.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.sync.ConnectionResult
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(onBack: () -> Unit, onAddDatabase: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val tokenSaved by viewModel.tokenSaved.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            Section(stringResource(R.string.settings_section_connection))
            TokenSection(tokenSaved, connection, onSave = viewModel::saveToken, onTest = viewModel::test)

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_databases))
            if (sources.isEmpty()) {
                Text(stringResource(R.string.settings_no_databases), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sources.forEach { source ->
                SourceRow(source, onToggle = { viewModel.setEnabled(source.dataSourceId, it) }, onDelete = { pendingDelete = source.dataSourceId })
            }
            OutlinedButton(onClick = onAddDatabase) { Text(stringResource(R.string.settings_add_database)) }

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_general))
            Text(stringResource(R.string.settings_week_start))
            SingleChoiceSegmentedButtonRow {
                listOf(DayOfWeek.SUNDAY to R.string.settings_sunday, DayOfWeek.MONDAY to R.string.settings_monday)
                    .forEachIndexed { index, (day, label) ->
                        SegmentedButton(
                            selected = settings.weekStart == day,
                            onClick = { viewModel.setWeekStart(day) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(stringResource(label)) }
                    }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_hide_done))
                    Text(
                        stringResource(R.string.settings_hide_done_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.hideDone, onCheckedChange = viewModel::setHideDone)
            }
        }
    }

    pendingDelete?.let { id ->
        val name = sources.firstOrNull { it.dataSourceId == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_confirm_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.settings_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TokenSection(tokenSaved: Boolean?, connection: ConnectionUi, onSave: (String) -> Unit, onTest: () -> Unit) {
    // 토큰은 저장 상태(Bundle)에 남기지 않도록 remember만 쓰고, 저장 후 바로 지운다. 표시는 항상 가린다.
    var input by remember { mutableStateOf("") }
    Text(
        stringResource(if (tokenSaved == true) R.string.settings_token_saved else R.string.settings_token_none),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_token_label)) },
        supportingText = { Text(stringResource(R.string.settings_token_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    val testing = connection is ConnectionUi.Testing
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(input)
                input = ""
            },
            enabled = input.isNotBlank() && !testing,
        ) { Text(stringResource(R.string.settings_save_and_test)) }
        if (tokenSaved == true) {
            OutlinedButton(onClick = onTest, enabled = !testing) { Text(stringResource(R.string.settings_test)) }
        }
    }
    when (connection) {
        ConnectionUi.Idle -> Unit
        ConnectionUi.Testing -> Text(stringResource(R.string.settings_testing))
        is ConnectionUi.Done -> when (val result = connection.result) {
            is ConnectionResult.Connected -> Text(
                result.botName?.let { stringResource(R.string.settings_connected, it) }
                    ?: stringResource(R.string.settings_connected_unnamed),
                color = MaterialTheme.colorScheme.primary,
            )
            is ConnectionResult.Failed -> Text(result.error.text(), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SourceRow(source: DataSourceEntity, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(NotionColors.argb(source.colorHex))))
        Text(source.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = source.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_delete_database, source.name))
        }
    }
}
