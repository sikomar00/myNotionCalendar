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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.sync.ViewListResult
import com.ysdoing.notioncal.core.sync.ViewOption

/** VW-04 설정 › 보기 추가: DB 링크 → 보기 목록 → 등록. 등록한 보기는 아래에서 지운다(노션에는 영향 없음). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddViewRoute(onBack: () -> Unit, viewModel: AddViewViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // 처음 값이 오기 전에는 null이다. 빈 목록으로 시작하면 '아직 등록한 보기가 없습니다'가 잠깐 깜빡여 데이터가 사라진 것처럼 보인다.
    val registered by viewModel.registered.collectAsStateWithLifecycle(initialValue = null)
    var link by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_view_title)) },
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
            Text(stringResource(R.string.add_view_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text(stringResource(R.string.add_view_link)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.load(link) }, enabled = link.isNotBlank() && !ui.loading) {
                Text(stringResource(R.string.add_view_load))
            }
            when (val result = ui.result) {
                null -> if (ui.loading) Text(stringResource(R.string.add_view_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ViewListResult.InvalidLink -> Text(stringResource(R.string.add_view_invalid_link), color = MaterialTheme.colorScheme.error)
                is ViewListResult.Failed -> Text(result.error.text(), color = MaterialTheme.colorScheme.error)
                is ViewListResult.Ok -> ViewList(result, ui.busyId) { viewModel.register(result.dataSourceId, it) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.add_view_my_views), style = MaterialTheme.typography.titleSmall)
            if (registered?.isEmpty() == true) Text(stringResource(R.string.add_view_none_registered), color = MaterialTheme.colorScheme.onSurfaceVariant)
            registered.orEmpty().forEach { view ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(view.name.ifBlank { stringResource(R.string.add_view_untitled) }, Modifier.weight(1f))
                    TextButton(onClick = { viewModel.remove(view) }) { Text(stringResource(R.string.add_view_remove)) }
                }
            }
        }
    }
}

@Composable
private fun ViewList(result: ViewListResult.Ok, busyId: String?, onRegister: (ViewOption) -> Unit) {
    Text(stringResource(R.string.add_view_database, result.databaseName), style = MaterialTheme.typography.titleSmall)
    if (result.views.isEmpty()) Text(stringResource(R.string.add_view_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
    result.views.forEach { view ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(view.name.ifBlank { stringResource(R.string.add_view_untitled) })
                val note = when {
                    !view.supported -> stringResource(R.string.add_view_unsupported)
                    view.shownAsList -> stringResource(R.string.add_view_as_list, view.type.orEmpty())
                    else -> view.type.orEmpty()
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                view.registered -> Text(stringResource(R.string.add_view_registered), color = MaterialTheme.colorScheme.primary)
                view.supported -> OutlinedButton(onClick = { onRegister(view) }, enabled = busyId == null) { Text(stringResource(R.string.add_view_register)) }
            }
        }
    }
}
