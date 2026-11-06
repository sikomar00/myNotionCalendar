package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * 구글 캘린더식 상단바: ☰(서랍), 제목▾(월 보기에서 연·월 선택), 검색, 오늘 아이콘.
 * 보기 전환·필터·설정은 서랍([MonthDrawerContent])에 있다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthTopBar(
    title: String,
    pickable: Boolean,
    filterActive: Boolean,
    today: LocalDate,
    onMenu: () -> Unit,
    onPickMonth: () -> Unit,
    onSearch: () -> Unit,
    onToday: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            // 필터는 서랍 안에 있으므로, 켜져 있으면 ☰에 점을 달아 일정이 숨겨진 이유를 알린다.
            val menu = stringResource(if (filterActive) R.string.month_menu_filter_active else R.string.month_menu)
            IconButton(onClick = onMenu) {
                BadgedBox(badge = { if (filterActive) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                    Icon(Icons.Filled.Menu, contentDescription = menu)
                }
            }
        },
        title = {
            // 제목은 먹색(색은 일정에만, 파랑은 '오늘'·보기 같은 동작에만).
            TextButton(
                onClick = { if (pickable) onPickMonth() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (pickable) Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_title))
            }
            TodayButton(today, onToday)
        },
    )
}

/** 오늘 날짜 숫자가 든 네모 아이콘(구글 캘린더의 '오늘'). 글꼴이 커지면 네모도 같이 커진다. */
@Composable
private fun TodayButton(today: LocalDate, onClick: () -> Unit) {
    val description = stringResource(R.string.month_today)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Box(
            Modifier.sizeIn(minWidth = 24.dp, minHeight = 24.dp).border(2.dp, LocalContentColor.current, TodayShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(today.dayOfMonth.toString(), fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
