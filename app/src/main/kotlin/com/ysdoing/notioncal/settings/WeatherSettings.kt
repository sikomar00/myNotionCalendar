package com.ysdoing.notioncal.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.weather.Region
import com.ysdoing.notioncal.core.data.weather.WeatherLocationMode
import com.ysdoing.notioncal.core.data.weather.WeatherStatus
import com.ysdoing.notioncal.core.designsystem.WeatherAttribution
import java.time.format.DateTimeFormatter

/** 설정 화면 날씨 항목의 동작. */
data class WeatherSettingsActions(
    val onEnable: (Boolean) -> Unit = {},
    val onMode: (WeatherLocationMode) -> Unit = {},
    val onSearch: suspend (String) -> List<Region> = { emptyList() },
    val onPickRegion: (Region) -> Unit = {},
    val onLocation: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    val onMonthCells: (Boolean) -> Unit = {},
)

private val fetchedFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

/**
 * WX-01·02·05 설정 → 날씨: 켜기, 위치(지역 직접 선택 기본·대략 위치), 월 보기 모든 칸 아이콘, 받아온 시각, 출처.
 * 대략 위치는 앱 사용 중에 한 번 읽어 소수 둘째 자리로 줄여 저장한다. 거부하면 지역 선택으로 돌아간다(권한 없이 동작).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherSection(settings: AppSettings, status: WeatherStatus, actions: WeatherSettingsActions) {
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var locationFailed by rememberSaveable { mutableStateOf(false) }
    val readLocation = {
        locationFailed = false
        readCoarseLocation(context, onFailure = { locationFailed = true }) { lat, lon -> actions.onLocation(lat, lon) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) {
            readLocation()
        } else {
            actions.onMode(WeatherLocationMode.REGION)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_weather))
                Text(stringResource(R.string.settings_weather_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = settings.weatherEnabled,
                onCheckedChange = { on ->
                    actions.onEnable(on)
                    if (on && settings.weatherPlace == null) picking = true // 위치를 먼저 고른다
                },
                modifier = Modifier.testTag("weatherSwitch"),
            )
        }
        if (!settings.weatherEnabled) return@Column

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(WeatherLocationMode.REGION to R.string.settings_weather_region, WeatherLocationMode.COARSE to R.string.settings_weather_coarse)
                .forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = settings.weatherLocationMode == mode,
                        onClick = {
                            actions.onMode(mode)
                            if (mode == WeatherLocationMode.COARSE) {
                                if (hasCoarse(context)) readLocation() else launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = {},
                    ) { Text(stringResource(label), maxLines = 1) }
                }
        }
        Text(
            settings.weatherPlace?.name?.let { stringResource(R.string.settings_weather_place, it) } ?: stringResource(R.string.settings_weather_no_place),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (denied) {
            Text(stringResource(R.string.settings_weather_denied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (locationFailed) {
            Text(stringResource(R.string.settings_weather_location_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (settings.weatherLocationMode == WeatherLocationMode.REGION) {
            OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.settings_weather_pick_region)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_weather_month_cells), Modifier.weight(1f))
            Switch(checked = settings.weatherMonthCellIcons, onCheckedChange = actions.onMonthCells)
        }
        status.fetchedAt?.let {
            Text(
                stringResource(R.string.settings_weather_fetched, it.atZone(APP_ZONE).format(fetchedFormat)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WeatherAttribution()
    }
    if (picking) {
        RegionPicker(
            onSearch = actions.onSearch,
            onPick = {
                picking = false
                actions.onPickRegion(it)
            },
            onDismiss = { picking = false },
        )
    }
}

/** 지역 고르기: 앱에 넣은 시·군·구 목록에서 찾는다(네트워크 없음). */
@Composable
private fun RegionPicker(onSearch: suspend (String) -> List<Region>, onPick: (Region) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by androidx.compose.runtime.remember { mutableStateOf(emptyList<Region>()) }
    LaunchedEffect(query) { results = onSearch(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_weather_pick_region)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.settings_weather_region_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("regionSearch"),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.name }) { region ->
                        Text(
                            region.name,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(region) }.heightIn(min = 48.dp).padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_weather_cancel)) } },
    )
}

private fun hasCoarse(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * WX-02 대략 위치를 한 번 읽는다(앱 사용 중). 마지막으로 알려진 위치가 있으면 바로 쓰고, 새 위치를 한 번 더 받아 맞춘다.
 * 둘 다 없으면(위치 서비스 꺼짐 등) [onFailure]. 권한은 호출 전에 확인한다. 좌표는 저장할 때 소수 둘째 자리로 줄인다.
 */
@SuppressLint("MissingPermission")
private fun readCoarseLocation(context: Context, onFailure: () -> Unit, onResult: (Double, Double) -> Unit) {
    val manager = context.getSystemService(LocationManager::class.java)
    if (!hasCoarse(context) || manager == null) return onFailure()
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    last?.let { onResult(it.latitude, it.longitude) }
    val provider = providers.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (provider == null) {
        if (last == null) onFailure()
        return
    }
    manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { location ->
        when {
            location != null -> onResult(location.latitude, location.longitude)
            last == null -> onFailure()
        }
    }
}
