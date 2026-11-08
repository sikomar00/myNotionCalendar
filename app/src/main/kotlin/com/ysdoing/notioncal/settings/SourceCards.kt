package com.ysdoing.notioncal.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.sourceKind

/** 설정 DB 탭의 묶음 종류(소제목 순서). */
internal enum class SourceGroupKind { CALENDAR, HABIT, LEDGER }

/**
 * 설정 DB 탭의 카드 하나: 함께 켜고 지우는 DB 묶음. 저장소가 짝(✅ 습관 기록 ↔ 🌱 습관, 거래내역 ↔ 예산·카테고리)을 함께 켜고 지우므로
 * (`DataSourceRepository.pairOf`) 화면도 짝을 카드 하나·스위치 하나·삭제 하나로 보인다. 일정 DB는 하나씩. [main]이 스위치·삭제를 대표한다.
 */
internal data class SourceGroup(val kind: SourceGroupKind, val main: DataSourceEntity, val members: List<DataSourceEntity>)

/** 등록 순서를 지키며 종류별로 묶는다. 짝을 잃은 DB(예: 거래내역 없이 남은 예산)는 혼자 카드가 된다. */
internal fun groupSources(sources: List<DataSourceEntity>): List<SourceGroup> {
    val byId = sources.associateBy { it.dataSourceId }
    val linkedHabits = sources.filter { it.sourceKind == SourceKind.HABIT_LOG }.mapNotNull { it.linkDataSourceId }.toSet()
    val ledgerIds = sources.filter { it.sourceKind == SourceKind.LEDGER }.map { it.dataSourceId }.toSet()
    val groups = sources.mapNotNull { source ->
        when (source.sourceKind) {
            SourceKind.HABIT_LOG -> SourceGroup(SourceGroupKind.HABIT, source, listOfNotNull(source, source.linkDataSourceId?.let(byId::get)))
            SourceKind.HABIT -> source.takeIf { it.dataSourceId !in linkedHabits }?.let { SourceGroup(SourceGroupKind.HABIT, it, listOf(it)) }
            SourceKind.LEDGER -> SourceGroup(SourceGroupKind.LEDGER, source, listOf(source) + sources.filter { it.linkDataSourceId == source.dataSourceId })
            SourceKind.BUDGET, SourceKind.CATEGORY ->
                source.takeIf { it.linkDataSourceId !in ledgerIds }?.let { SourceGroup(SourceGroupKind.LEDGER, it, listOf(it)) }
            else -> SourceGroup(SourceGroupKind.CALENDAR, source, listOf(source))
        }
    }
    return groups.sortedBy { it.kind } // 안정 정렬이라 같은 종류 안에서는 등록 순서 그대로다
}

/** 카드 제목: 묶음 DB 이름을 가운뎃점으로 잇는다(예: '✅ 습관 기록 · 🌱 습관'). */
internal fun SourceGroup.title(): String = members.joinToString(" · ") { it.name }

/** 카드 동작. 색·속성은 일정 DB만 쓴다. */
internal class SourceCardActions(
    val onToggle: (Boolean) -> Unit,
    val onChooseProps: () -> Unit,
    val onChooseColor: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * DB 카드: 윗줄은 [색 점] 이름 · ⚠ · 스위치(줄 전체를 눌러 켜고 끔, DB 이름으로 읽힘), 아랫줄은 색·강조 · 속성 … 삭제(오른쪽, error 색).
 * 글꼴을 키워도 이름이 사라지거나 삭제가 스위치 옆에 붙지 않게 두 줄로 나눈다(부록 A-5·A-6).
 */
@Composable
internal fun SourceCard(group: SourceGroup, actions: SourceCardActions) {
    val main = group.main
    val calendar = main.sourceKind == SourceKind.CALENDAR
    val title = group.title()
    val enabledLabel = stringResource(R.string.settings_source_enabled, title)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).testTag("sourceCard"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (calendar) {
                // EX-03: 색 점을 누르면 DB 색·색 기준을 고른다(48dp). 켜고 끄는 줄 밖에 두어 색을 바꾸려다 DB가 꺼지지 않게 한다.
                val colorLabel = stringResource(R.string.settings_color_button, title)
                IconButton(onClick = actions.onChooseColor, modifier = Modifier.semantics { contentDescription = colorLabel }) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(NotionColors.argb(main.colorHex))))
                }
            }
            Row(
                // 일정이 아닌 카드는 색 점 자리가 없으니 글자 버튼 줄과 이름 들여쓰기를 맞춘다.
                Modifier.weight(1f).padding(start = if (calendar) 0.dp else 12.dp).heightIn(min = 48.dp)
                    .toggleable(value = main.enabled, role = Role.Switch, onValueChange = actions.onToggle)
                    .semantics { contentDescription = enabledLabel },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // NS-09: 매핑한 속성이 사라지거나 종류가 바뀌었으면 경고(자세한 안내는 월 화면 배너).
                if (remember(group) { group.members.any(::hasSchemaProblem) }) {
                    Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.settings_schema_warning), tint = MaterialTheme.colorScheme.error)
                }
                Switch(checked = main.enabled, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (calendar) {
                // EX-03·EX-11: 색 점만으로는 찾기 어려워 글자 버튼으로 연다(디자인 리뷰 2026-10-16).
                TextButton(onClick = actions.onChooseColor, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.settings_color_emphasis)) }
                TextButton(onClick = actions.onChooseProps, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.settings_extra_props)) }
            }
            Spacer(Modifier.weight(1f))
            val deleteLabel = stringResource(R.string.settings_delete_database, title)
            TextButton(
                onClick = actions.onDelete,
                modifier = Modifier.semantics { contentDescription = deleteLabel },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), // A-13: 지우는 동작은 error 색
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(stringResource(R.string.settings_delete)) }
        }
    }
}

/** 묶음 소제목(일정·습관·가계부, 일정 추가 기본 DB). 구역 제목([Section])보다 한 단계 작다. */
@Composable
internal fun SubSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}
