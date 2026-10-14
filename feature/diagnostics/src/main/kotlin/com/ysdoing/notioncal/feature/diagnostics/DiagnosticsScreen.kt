package com.ysdoing.notioncal.feature.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppFailure
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 진단 화면. [onBack]이 null이면(안전 모드로 시작) 뒤로 버튼을 숨긴다.
 * [onContinue]: 초기화 실패 안내를 확인한 뒤 월 화면으로 간다.
 */
@Composable
fun DiagnosticsRoute(
    onBack: (() -> Unit)?,
    onContinue: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refreshSystem()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        viewModel.share.collect { uri -> context.shareReport(uri) }
    }
    DiagnosticsScreen(
        state = state,
        debugLabels = viewModel.debugActions.map { it.label },
        onBack = onBack,
        onContinue = { viewModel.acknowledgeFailure(onContinue) },
        onOpenBatteryOptimization = { context.openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        onOpenAppSettings = {
            context.openSettings(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        onExport = viewModel::export,
        onClearCache = viewModel::clearCache,
        onDebug = { index -> viewModel.runDebug(viewModel.debugActions[index]) },
        onDismissNotice = viewModel::dismissNotice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    debugLabels: List<String>,
    onBack: (() -> Unit)?,
    onContinue: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onExport: () -> Unit,
    onClearCache: () -> Unit,
    onDebug: (Int) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let { noticeText(it, state.noticeArg) }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            onDismissNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.failure?.let { FailureCard(it, onContinue) }
            SyncSection(state)
            PowerSection(state.power, onOpenBatteryOptimization, onOpenAppSettings)
            ExitSection(state.exits)
            Section(stringResource(R.string.diag_section_tools)) {
                Button(onClick = onExport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diag_export))
                }
                OutlinedButton(onClick = { confirmClear = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diag_clear_cache))
                }
            }
            if (debugLabels.isNotEmpty()) {
                Section(stringResource(R.string.diag_section_debug)) {
                    debugLabels.forEachIndexed { index, label ->
                        OutlinedButton(onClick = { onDebug(index) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(label)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diag_clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.diag_clear_cache_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearCache()
                }) { Text(stringResource(R.string.diag_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.diag_cancel)) } },
        )
    }
}

@Composable
private fun noticeText(notice: DiagnosticsNotice, arg: String?): String = when (notice) {
    DiagnosticsNotice.CACHE_CLEARED -> stringResource(R.string.diag_notice_cache_cleared)
    DiagnosticsNotice.RESET_ON_RESTART -> stringResource(R.string.diag_notice_reset_on_restart)
    DiagnosticsNotice.EXPORT_FAILED -> stringResource(R.string.diag_notice_export_failed)
    DiagnosticsNotice.DEBUG_DONE -> stringResource(R.string.diag_notice_debug_done, arg.orEmpty())
}

@Composable
private fun FailureCard(failure: AppFailure, onContinue: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diag_failure_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (failure.safeMode) Text(stringResource(R.string.diag_failure_safe_mode))
            Text(stringResource(R.string.diag_failure_kind, failure.kind), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.diag_failure_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onContinue) { Text(stringResource(R.string.diag_failure_continue)) }
        }
    }
}

@Composable
private fun SyncSection(state: DiagnosticsUiState) {
    val sync = state.sync
    Section(stringResource(R.string.diag_section_sync)) {
        InfoRow(stringResource(R.string.diag_last_success), sync?.lastSuccess?.let { time(it.at) } ?: stringResource(R.string.diag_none))
        InfoRow(stringResource(R.string.diag_last_failure), sync?.lastFailure?.let { failureText(it) } ?: stringResource(R.string.diag_none))
        InfoRow(stringResource(R.string.diag_next_periodic), sync?.nextPeriodicAt?.let(::time) ?: stringResource(R.string.diag_not_scheduled))
        InfoRow(stringResource(R.string.diag_pending_writes), stringResource(R.string.diag_count, sync?.pendingWrites ?: 0))
        InfoRow(stringResource(R.string.diag_unseen_conflicts), stringResource(R.string.diag_count, sync?.unseenConflicts ?: 0))
        InfoRow(
            stringResource(R.string.diag_holiday_source),
            stringResource(
                when (sync?.holidaySource) {
                    "GOOGLE" -> R.string.diag_holiday_google
                    "SAMSUNG" -> R.string.diag_holiday_samsung
                    else -> R.string.diag_none
                },
            ),
        )
    }
}

@Composable
private fun failureText(log: SyncLogEntity): String =
    stringResource(R.string.diag_failure_entry, time(log.at), log.trigger, log.error ?: log.result)

@Composable
private fun PowerSection(power: PowerStatus?, onOpenBatteryOptimization: () -> Unit, onOpenAppSettings: () -> Unit) {
    Section(stringResource(R.string.diag_section_power)) {
        if (power != null) {
            InfoRow(
                stringResource(R.string.diag_battery_optimization),
                stringResource(if (power.ignoringOptimizations) R.string.diag_battery_exempt else R.string.diag_battery_optimized),
                warn = !power.ignoringOptimizations,
            )
            InfoRow(
                stringResource(R.string.diag_standby_bucket),
                stringResource(standbyBucketLabel(power.standbyBucket)),
                warn = power.standbyBucket >= android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE,
            )
        }
        Text(stringResource(R.string.diag_power_guide), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpenBatteryOptimization, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.diag_open_battery_optimization))
        }
        OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.diag_open_app_settings))
        }
    }
}

@Composable
private fun ExitSection(exits: List<ExitRecord>) {
    Section(stringResource(R.string.diag_section_exits)) {
        if (exits.isEmpty()) Text(stringResource(R.string.diag_none), style = MaterialTheme.typography.bodyMedium)
        exits.forEach { e ->
            val label = stringResource(if (e.memoryLimit) R.string.diag_exit_memory_limit else exitReasonLabel(e.reason))
            Column {
                InfoRow(time(e.at), label, warn = e.memoryLimit)
                e.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

private fun time(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(APP_ZONE).format(timeFormat)

private fun Context.openSettings(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun Context.shareReport(uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(send, getString(R.string.diag_export_chooser))) }
}
