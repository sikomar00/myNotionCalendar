package com.ysdoing.notioncal.settings

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.SettingsTab
import com.ysdoing.notioncal.core.designsystem.SwitchRow
import com.ysdoing.notioncal.core.sync.ConnectionResult
import com.ysdoing.notioncal.reminders.notificationPermissionGranted
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.provider.Settings
import android.net.Uri
import android.content.pm.PackageManager
import android.content.Intent
import android.Manifest
import androidx.lifecycle.compose.LifecycleResumeEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onAddDatabase: () -> Unit,
    onAddView: () -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDday: () -> Unit = {},
    startTab: SettingsTab? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val tokenSaved by viewModel.tokenSaved.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val exactAlarm by viewModel.exactAlarm.collectAsStateWithLifecycle()
    val schemaProblem by viewModel.schemaProblem.collectAsStateWithLifecycle()
    val syncProblem by viewModel.syncProblem.collectAsStateWithLifecycle()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingProps by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingColor by rememberSaveable { mutableStateOf<String?>(null) }
    // ST-05: 바로가기 인자 > 토큰 없음이면 연결 > 마지막으로 본 카테고리. 토큰 여부를 읽을 때까지는 고르지 않는다.
    var tabId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (tabId == null) tabId = viewModel.startTab(startTab).id }
    val context = LocalContext.current
    // 알림 권한·시스템 알림·정확한 알람은 시스템 설정에서 바꾸고 돌아올 때 달라지니 화면이 다시 보일 때마다 새로 본다.
    var notificationsOk by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshExactAlarm()
        notificationsOk = notificationPermissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val facts = SettingsFacts(
        tokenSaved = tokenSaved,
        connectionFailed = (connection as? ConnectionUi.Done)?.result is ConnectionResult.Failed,
        schemaProblem = schemaProblem,
        remindersBlocked = settings.remindersEnabled && (!exactAlarm || !notificationsOk),
        syncProblem = syncProblem,
    )
    val actions = SettingsActions(
        onAddDatabase = onAddDatabase, onAddView = onAddView, onOpenRecipes = onOpenRecipes, onOpenDiagnostics = onOpenDiagnostics, onOpenDday = onOpenDday,
        onChooseProps = { choosingProps = it }, onChooseColor = { choosingColor = it }, onDelete = { pendingDelete = it },
        tokenInput = remember { mutableStateOf("") },
    )

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
        SettingsTab.fromId(tabId)?.let { current ->
            SettingsCategoryNav(current, settingsBadges(facts), onSelect = { tabId = it.id; viewModel.rememberTab(it) }, modifier = Modifier.padding(padding)) {
                key(current) { SettingsPage(current, viewModel, actions) }
            }
        }
    }

    choosingColor?.let { id ->
        sources.firstOrNull { it.dataSourceId == id }?.let { source ->
            ColorDialog(
                source = source,
                onSave = { hex, useOptionColors, emphasis ->
                    viewModel.setColorStyle(id, hex, useOptionColors)
                    viewModel.setEmphasis(id, emphasis.first, emphasis.second)
                    choosingColor = null
                },
                onDismiss = { choosingColor = null },
            )
        }
    }

    choosingProps?.let { id ->
        sources.firstOrNull { it.dataSourceId == id }?.let { source ->
            ExtraPropsDialog(
                source = source,
                onSave = { ids ->
                    viewModel.setExtraProps(id, ids)
                    choosingProps = null
                },
                onDismiss = { choosingProps = null },
            )
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
                }) { Text(stringResource(R.string.settings_delete), color = MaterialTheme.colorScheme.error) } // A-13
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
internal fun Section(title: String) {
    // 구역 제목은 먹색 굵은 글자(파랑은 누르는 것에만 쓴다).
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun TokenSection(tokenSaved: Boolean?, connection: ConnectionUi, inputState: MutableState<String>, onSave: (String) -> Unit, onTest: () -> Unit) {
    // 토큰은 저장 상태(Bundle)에 남기지 않도록 remember만 쓰고(상태는 화면이 들고 있어 탭을 바꿔도 안 사라진다), 저장 후 바로 지운다. 표시는 항상 가린다.
    var input by inputState
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

/**
 * EX-02 공휴일 표시. 켜면 READ_CALENDAR를 요청하고, 허용하면 폰의 공휴일 캘린더를 읽는다.
 * 거부하면 꺼 둔 채 안내하고, 앱 설정에서 허용할 수 있게 버튼을 보인다. 노션 일정은 권한과 무관하다.
 */
@Composable
internal fun HolidaySetting(show: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) onChange(true)
    }
    // 권한을 앱 설정에서 거둬 갔으면 꺼진 것으로 보인다.
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    SwitchRow(stringResource(R.string.settings_holidays), show && granted, note = stringResource(R.string.settings_holidays_note)) { on ->
        when {
            !on -> onChange(false)
            granted -> onChange(true)
            else -> launcher.launch(Manifest.permission.READ_CALENDAR)
        }
    }
    if (denied && !granted) {
        Text(
            stringResource(R.string.settings_holidays_denied),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text(stringResource(R.string.settings_holidays_open_app_settings)) }
    }
}
