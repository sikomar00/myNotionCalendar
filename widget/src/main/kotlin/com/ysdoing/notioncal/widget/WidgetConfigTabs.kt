package com.ysdoing.notioncal.widget

import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.dday.Dday
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.WidgetLayout

/**
 * 어떤 위젯의 설정인지. 보조(WG-06)·달력 + 일정(WG-09)은 그 모양으로 미리보기를 보이고, 월 위젯 전용 설정은 숨긴다.
 * 통합 위젯(WG-11)은 📅 탭이 월 위젯 내용이라 MONTH로 다룬다. 습관 위젯(HB-03)은 모양 설정(테마·투명도·글자)만 있다.
 */
internal enum class ConfigKind { MONTH, AGENDA, SPLIT, LEDGER, DDAY, DDAY_LIST, VIEW, LAYOUT, HABIT, BOOK }

/** Phase 11g ST-07 설정 화면의 탭: 내용(위젯이 보여 줄 대상) · 모양(테마·글자·표시 방식) · 동작(숫자 단계·프리셋·입력 양식). */
internal enum class ConfigTab { CONTENT, LOOK, ACTION }

/** 일정 DB 고르기·완료 숨김을 쓰는 위젯. */
internal val CALENDAR_KINDS = setOf(ConfigKind.MONTH, ConfigKind.AGENDA, ConfigKind.SPLIT)

/** 설정 화면이 한 번 읽어 둔 것(미리보기와 설정 목록이 함께 쓴다). */
internal data class ConfigData(
    val settings: AppSettings,
    val events: List<CalendarEvent>,
    val sources: List<DataSourceEntity>,
    val ledger: LedgerBoard? = null,
    val ddays: List<Dday> = emptyList(),
    val views: List<ViewSnapshotEntity> = emptyList(),
    val boards: Map<String, ViewBoard?> = emptyMap(),
)

/** 이 위젯 설정에 보일 탭. 항목이 없는 탭은 뺀다(모양은 모든 위젯에 있다). 탭이 하나뿐이면 화면이 탭 줄을 그리지 않는다. */
internal fun configTabs(kind: ConfigKind): List<ConfigTab> = buildList {
    if (kind in CALENDAR_KINDS || kind in CONTENT_KINDS) add(ConfigTab.CONTENT)
    add(ConfigTab.LOOK)
    if (kind in ACTION_KINDS) add(ConfigTab.ACTION)
}

private val CONTENT_KINDS = setOf(ConfigKind.DDAY, ConfigKind.VIEW, ConfigKind.BOOK, ConfigKind.LAYOUT)
private val ACTION_KINDS = setOf(ConfigKind.VIEW, ConfigKind.LAYOUT)

/**
 * 프리셋 줄이 그려지는 위젯인가: 보기 위젯, 그리고 보기 하나(단일 배치)인 노션 위젯. 탭·섹션은 보기마다 값이 달라 프리셋 줄을 그리지 않는다.
 * 배치를 정하지 않은 노션 위젯은 단일이다.
 */
internal fun presetsApply(kind: ConfigKind, config: WidgetConfig): Boolean = kind == ConfigKind.VIEW || (kind == ConfigKind.LAYOUT && layoutOf(config).mode == LayoutMode.SINGLE)

/** '프리셋 만들기'가 새 항목 시트를 열 보기. [presetsApply]이고 보기를 골랐을 때만 있다. */
internal fun presetViewId(kind: ConfigKind, config: WidgetConfig): String? = when {
    !presetsApply(kind, config) -> null
    kind == ConfigKind.VIEW -> config.viewId
    else -> layoutOf(config).items.firstOrNull()?.viewId
}

/** 입력 양식을 고칠 보기들: 새 항목을 만드는 보기 위젯의 보기, 노션 위젯이 배치에 담은 보기(집계 타일의 보기는 새 항목이 없다). */
internal fun formViewIds(kind: ConfigKind, config: WidgetConfig): List<String> = when (kind) {
    ConfigKind.VIEW -> listOfNotNull(config.viewId)
    ConfigKind.LAYOUT -> layoutOf(config).items.map { it.viewId }
    else -> emptyList()
}

private fun layoutOf(config: WidgetConfig) = WidgetLayout.parse(config.layoutJson)

/** 설정 화면의 탭 줄(내용·모양·동작). */
@Composable
internal fun ConfigTabRow(tabs: List<ConfigTab>, selected: ConfigTab, onSelect: (ConfigTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = tabs.indexOf(selected)) {
        tabs.forEach { tab -> Tab(selected = tab == selected, onClick = { onSelect(tab) }, text = { Text(stringResource(tab.label)) }) }
    }
}

private val ConfigTab.label: Int
    get() = when (this) {
        ConfigTab.CONTENT -> R.string.widget_config_tab_content
        ConfigTab.LOOK -> R.string.widget_config_tab_look
        ConfigTab.ACTION -> R.string.widget_config_tab_action
    }
