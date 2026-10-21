package com.ysdoing.notioncal.feature.month

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.designsystem.WeatherBadge
import com.ysdoing.notioncal.core.designsystem.WeatherCredit
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val syncTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

@Composable
internal fun SyncStatusLine(header: SyncHeader, weather: MonthWeather, today: LocalDate) {
    val text = when {
        header.syncing -> stringResource(R.string.month_syncing)
        header.lastSuccessAt != null -> stringResource(
            R.string.month_last_sync,
            Instant.ofEpochMilli(header.lastSuccessAt).atZone(APP_ZONE).format(syncTimeFormat),
        )
        else -> stringResource(R.string.month_never_synced)
    }
    val pending = if (header.pendingWrites > 0) stringResource(R.string.month_pending_writes, header.pendingWrites) else null
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOfNotNull(text, pending).forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        // WX-03 머리: 오늘 날씨(아이콘·최고·최저). WX-05 출처 링크를 함께 둔다.
        weather.days[today]?.let { day ->
            WeatherBadge(day, weather.stale, iconSize = 14.dp, fontSize = 11.sp)
            val uri = LocalUriHandler.current
            Text(
                stringResource(R.string.month_weather_credit),
                modifier = Modifier.padding(start = 6.dp).clickable { uri.openUri(WeatherCredit.SITE) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** NS-05: 노션 반영 실패(되돌림)·충돌(노션 값 우선) 알림. 확인을 누르면 내용을 보여주고 읽음 처리한다. */
@Composable
internal fun WriteIssuesBanner(header: SyncHeader, onAcknowledge: () -> Unit) {
    val issues = header.writeIssues
    if (issues.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val rejected = issues.count { it.kind == "REJECTED" }
    val conflicts = issues.size - rejected
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.month_write_issues, rejected, conflicts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.month_write_issues_show)) }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.month_write_issues_title)) },
            text = {
                Column {
                    issues.take(20).forEach { issue ->
                        val what = when (issue.kind) {
                            "REJECTED" -> stringResource(R.string.month_issue_rejected)
                            "REMOTE_DELETED" -> stringResource(R.string.month_issue_remote_deleted)
                            else -> stringResource(R.string.month_issue_conflict, issue.localValue.orEmpty(), issue.remoteValue.orEmpty())
                        }
                        Text("• ${issue.title.ifBlank { "(제목 없음)" }}: $what", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAcknowledge()
                    open = false
                }) { Text(stringResource(R.string.month_write_issues_ack)) }
            },
        )
    }
}

/**
 * NS-09: 노션에서 매핑한 속성이 사라지거나 바뀌었을 때. 제목·날짜가 없으면 그 DB는 동기화를 멈추고(캐시는 그대로)
 * '다시 연결'로 매핑을 다시 고르게 한다. 이름만 바뀐 경우는 동작이 그대로라 알림만 한다.
 */
@Composable
internal fun SchemaBanner(alerts: List<SchemaAlert>, onReconnect: (String, String) -> Unit, onAcknowledge: (String) -> Unit) {
    alerts.forEach { alert ->
        val blocked = alert.issues.any { it.blocksSync }
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (blocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                alert.issues.forEach { issue ->
                    Text(schemaMessage(alert.name, issue), style = MaterialTheme.typography.bodyMedium)
                }
                Row {
                    if (alert.issues.any { it.kind != SchemaIssue.RENAMED }) {
                        TextButton(onClick = { onReconnect(alert.databaseId, alert.dataSourceId) }) {
                            Text(stringResource(R.string.schema_reconnect))
                        }
                    }
                    TextButton(onClick = { onAcknowledge(alert.dataSourceId) }) { Text(stringResource(R.string.schema_ack)) }
                }
            }
        }
    }
}

@Composable
private fun schemaMessage(dbName: String, issue: SchemaIssue): String {
    val role = stringResource(
        when (issue.role) {
            SchemaIssue.TITLE -> R.string.schema_role_title
            SchemaIssue.DATE -> R.string.schema_role_date
            SchemaIssue.DONE -> R.string.schema_role_done
            SchemaIssue.COLOR -> R.string.schema_role_color
            else -> R.string.schema_role_sort
        },
    )
    val prop = issue.oldName?.let { "'$it'" } ?: role
    return when {
        issue.kind == SchemaIssue.RENAMED -> stringResource(R.string.schema_renamed, dbName, issue.oldName.orEmpty(), issue.newName.orEmpty())
        issue.blocksSync && issue.kind == SchemaIssue.MISSING -> stringResource(R.string.schema_missing_required, dbName, role, prop)
        issue.blocksSync -> stringResource(R.string.schema_type_required, dbName, role, prop)
        issue.kind == SchemaIssue.MISSING -> stringResource(R.string.schema_missing_optional, dbName, role, prop)
        else -> stringResource(R.string.schema_type_optional, dbName, role, prop)
    }
}

/** PF-05: 인증·공유 오류여도 화면 내용은 그대로 두고 안내만 띄운다. 로그인 화면으로 강제 이동하지 않는다. */
@Composable
internal fun Banner(header: SyncHeader, onOpenSettings: () -> Unit) {
    val message = when {
        !header.hasSources -> null
        header.lastError == "Unauthorized" -> R.string.month_banner_auth
        header.lastError == "NotShared" -> R.string.month_banner_not_shared
        else -> null
    }
    if (!header.hasSources) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.month_onboarding_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.month_onboarding_body), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.month_banner_action)) }
            }
        }
    } else if (message != null) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.month_banner_action)) }
            }
        }
    }
}
