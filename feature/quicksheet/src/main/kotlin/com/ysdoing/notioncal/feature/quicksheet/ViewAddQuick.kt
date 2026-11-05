package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.QuickChip

/** 끈 칩의 키 목록을 저장할 수 있는 글자 하나(`|`로 이음)로 다룬다. */
internal fun ignoredKeys(text: String): Set<String> = text.split('|').filter { it.isNotEmpty() }.toSet()

/** 한 줄 입력에서 읽은 날짜 칩의 키. */
internal const val QUICK_DATE_KEY = "date"

/** [key]를 끈 칩에 더한 글자(이미 있으면 그대로). */
internal fun withKey(text: String, key: String): String = (ignoredKeys(text) + key).joinToString("|")

/** [key]를 끈 칩에서 뺀 글자(켠다). */
internal fun withoutKey(text: String, key: String): String = (ignoredKeys(text) - key).joinToString("|")

/** [key] 칩을 끄고 켠 뒤의 글자. */
internal fun toggled(text: String, key: String): String = ignoredKeys(text).let { if (key in it) it - key else it + key }.joinToString("|")

/**
 * Phase 11g AD-03 한 줄 입력에서 읽은 값 칩 줄. 켠 것은 선택 상태이고 누르면 끈다(그 글자는 제목에 남는다). 끈 칩도 남아 다시 켤 수 있다.
 * 읽은 게 없으면 아무것도 그리지 않는다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickChips(chips: List<QuickChip>, ignored: Set<String>, onToggle: (String) -> Unit) {
    if (chips.isEmpty()) return
    Text(stringResource(R.string.view_add_parsed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip -> FilterChip(selected = chip.key !in ignored, onClick = { onToggle(chip.key) }, label = { Text(chip.label) }) }
    }
}
