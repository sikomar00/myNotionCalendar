package com.ysdoing.notioncal.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.sync.SharedDatabase

/**
 * NS-02 '노션에서 고르기': 이 연결에 공유된 DB 목록. 줄을 누르면 링크를 넣은 것처럼 조회해 속성 고르기로 간다.
 * 이미 등록한 DB는 흐린 글자 '등록됨'으로 두고 누를 수 없다(같은 DB를 두 번 등록하지 않게, 속성을 바꾸려면 설정 카드의 '속성 연결').
 */
@Composable
internal fun SharedDatabaseList(state: SharedListUi, onPick: (SharedDatabase) -> Unit, onRetry: () -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SubSection(stringResource(R.string.add_db_pick_title))
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error != null -> {
                Text(state.error.text(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.add_db_pick_retry)) }
            }
            state.databases.isNullOrEmpty() -> if (state.databases != null) {
                Text(stringResource(R.string.add_db_pick_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                SearchableRows(state.databases, enabled, onPick)
                if (state.truncated) {
                    Text(stringResource(R.string.add_db_pick_truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 이름 검색 칸 + 걸러진 줄. 받아 둔 목록 안에서만 거른다(노션을 다시 부르지 않는다). */
@Composable
private fun SearchableRows(databases: List<SharedDatabase>, enabled: Boolean, onPick: (SharedDatabase) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth().testTag("sharedSearch"),
        label = { Text(stringResource(R.string.add_db_pick_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
    )
    val shown = filterShared(databases, query)
    if (shown.isEmpty()) {
        Text(stringResource(R.string.add_db_pick_no_match), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    shown.forEach { database -> SharedDatabaseRow(database, enabled, onPick) }
}

/** 이름에 [query]가 든 DB(대소문자·띄어쓰기 무시). 비어 있으면 전부. */
internal fun filterShared(databases: List<SharedDatabase>, query: String): List<SharedDatabase> {
    val key = query.normalized()
    return if (key.isEmpty()) databases else databases.filter { it.name.normalized().contains(key) }
}

private fun String.normalized(): String = filterNot { it.isWhitespace() }.lowercase()

@Composable
private fun SharedDatabaseRow(database: SharedDatabase, enabled: Boolean, onPick: (SharedDatabase) -> Unit) {
    val name = database.name.ifBlank { stringResource(R.string.add_db_pick_untitled) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = enabled && !database.registered, role = Role.Button) { onPick(database) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("sharedDatabase"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (database.registered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (database.registered) {
            Text(stringResource(R.string.add_db_pick_registered), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
