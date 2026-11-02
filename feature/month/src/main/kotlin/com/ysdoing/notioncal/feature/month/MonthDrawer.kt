package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.calendar.CalendarView

/** MV-06 보기 전환(일정·일·주·월), MV-09 필터, 설정. 상단바에서 옮겨 온 메뉴다. */
@Composable
internal fun MonthDrawerContent(
    view: CalendarView,
    filterActive: Boolean,
    onSelectView: (CalendarView) -> Unit,
    onFilter: () -> Unit,
    onSettings: () -> Unit,
) {
    val itemPadding = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    ModalDrawerSheet {
        // 가로 화면·큰 글꼴에서도 필터·설정까지 닿도록 스크롤을 둔다(설정으로 가는 길이 서랍뿐이다).
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.month_drawer_views),
                modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CalendarView.entries.forEach { option ->
                NavigationDrawerItem(
                    label = { Text(stringResource(viewLabel(option))) },
                    selected = option == view,
                    onClick = { onSelectView(option) },
                    modifier = itemPadding,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            NavigationDrawerItem(
                label = { Text(stringResource(if (filterActive) R.string.filter_active else R.string.filter_title)) },
                selected = filterActive,
                onClick = onFilter,
                icon = { Icon(FilterIcon, contentDescription = null) },
                modifier = itemPadding,
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.month_settings)) },
                selected = false,
                onClick = onSettings,
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                modifier = itemPadding,
            )
        }
    }
}
