package com.ysdoing.notioncal.feature.diagnostics

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** S25-03: 배터리 최적화 예외가 아니면 월 화면 위에 띄우는 안내 카드. 닫으면 다시 띄우지 않는다. */
@HiltViewModel
class BatteryGuideViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val reader = SystemStatusReader(context)
    private val power = MutableStateFlow<PowerStatus?>(null)

    val visible: StateFlow<Boolean> = combine(settings.settings, power) { s, p ->
        !s.batteryGuideDismissed && p != null && !p.ignoringOptimizations
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 설정 화면에서 돌아올 때마다 다시 확인한다. */
    fun refresh() {
        viewModelScope.launch { power.value = withContext(io) { runCatching { reader.power() }.getOrNull() } }
    }

    fun dismiss() {
        viewModelScope.launch { settings.dismissBatteryGuide() }
    }
}

@Composable
fun BatteryGuideCard(viewModel: BatteryGuideViewModel = hiltViewModel()) {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    if (visible) {
        BatteryGuideContent(
            onOpenSettings = { context.openBatteryOptimization() },
            onDismiss = viewModel::dismiss,
        )
    }
}

@Composable
fun BatteryGuideContent(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.battery_guide_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.battery_guide_body), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.battery_guide_dismiss)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.battery_guide_open)) }
            }
        }
    }
}

private fun Context.openBatteryOptimization() {
    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
