package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.LayoutItem
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** 새 위젯 한 번 그리기에 필요한 값. [boards]에 없는 보기는 등록이 지워진 보기다. */
internal data class LayoutWidgetState(val config: WidgetConfig, val layout: WidgetLayout, val boards: Map<String, ViewBoard?>, val nowMillis: Long) {
    /** 저장된 탭 번호가 탭 수를 넘으면(탭을 줄인 뒤) 마지막 탭으로 읽는다. */
    val activeIndex: Int get() = config.lastTab.coerceIn(0, (layout.items.size - 1).coerceAtLeast(0))

    /** 칸 하나를 [ViewPane]이 그릴 값으로. 칸의 보기가 곧 위젯의 보기다. */
    fun paneState(item: LayoutItem) = ViewWidgetState(boards[item.viewId], config.copy(viewId = item.viewId), nowMillis)
}

/** 칸마다 같은 그리기 값. [fontScale]은 시스템 글꼴 배율, [staticRows]는 설정 화면 미리보기용([PaneSpec]). */
internal data class LayoutLook(val palette: WidgetPalette, val metrics: ViewMetrics, val fontScale: Float, val staticRows: Boolean)

/**
 * Phase 11d WL-01~03 '노션 위젯': 등록한 보기를 하나로, 탭으로, 또는 섹션(위아래로 쌓기)으로 담는다. 틀(`appWidgetBackground`)은 여기서 한 번만 두고
 * 본문은 보기 위젯과 같은 [ViewPane]을 쓴다. 탭·섹션은 칸의 크기를 [LocalSize]로 알려 줘 크기 단계·행 수가 그 칸에 맞는다.
 * 프리셋은 한 보기의 값이라 단일일 때만 둔다.
 */
@Composable
internal fun LayoutWidgetContent(
    state: LayoutWidgetState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
    staticRows: Boolean = false,
) {
    val config = state.config
    val palette = WidgetPalette.of(config.theme, config.opacity, LocalContext.current)
    val look = LayoutLook(palette, viewMetrics(config.fontScale / 100f, systemFontScale), systemFontScale, staticRows)
    val size = LocalSize.current
    Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius).background(palette.background)) {
        when {
            state.layout.items.isEmpty() -> Column(GlanceModifier.fillMaxSize().padding(horizontal = PADDING_H, vertical = VIEW_PADDING_V)) {
                ViewNote(R.string.layout_widget_choose, config.appWidgetId, look.metrics, palette, tag = "layoutNote")
            }
            state.layout.mode == LayoutMode.TABS -> LayoutTabsBody(state, look, size)
            state.layout.mode == LayoutMode.SECTIONS -> LayoutSectionsBody(state, look, size)
            else -> LayoutPane(state, state.layout.items.first(), look, size, PaneSpec(look.staticRows), weighted = false)
        }
    }
}

/**
 * 칸 하나: 안쪽 여백 + 크기를 [area]로 덮어 쓴 [ViewPane]. 크기 단계·열 수는 [LocalSize]로 정하니 칸마다 덮어 쓴다(통합 위젯과 같은 요령).
 * [weighted]면 위의 탭 줄을 뺀 남은 높이를, 아니면 전체를 차지한다.
 */
@Composable
internal fun ColumnScope.LayoutPane(state: LayoutWidgetState, item: LayoutItem, look: LayoutLook, area: DpSize, spec: PaneSpec, weighted: Boolean) {
    val modifier = if (weighted) GlanceModifier.fillMaxWidth().defaultWeight() else GlanceModifier.fillMaxSize()
    Column(modifier.padding(horizontal = PADDING_H, vertical = VIEW_PADDING_V)) {
        CompositionLocalProvider(LocalSize provides area) { ViewPane(state.paneState(item), look.palette, look.metrics, spec) }
    }
}

/** WL-02 탭: 탭 줄 + 고른 탭의 칸. 칸은 탭 줄을 뺀 크기다. */
@Composable
private fun ColumnScope.LayoutTabsBody(state: LayoutWidgetState, look: LayoutLook, size: DpSize) {
    val config = state.config
    val tabHeight = TabbedWidget.tabRowHeight(config, look.fontScale)
    val textScale = minOf(look.fontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
    val fontSp = TabbedWidget.TAB_FONT * config.fontScale / 100f * textScale / look.fontScale
    val items = state.layout.items
    LayoutTabStrip(items.map { it.title(state.boards[it.viewId]?.name.orEmpty()) }, state.activeIndex, size.width, tabHeight, fontSp, look.palette)
    val area = DpSize(size.width, (size.height - tabHeight).coerceAtLeast(0.dp))
    val spec = PaneSpec(look.staticRows, height = area.height - VIEW_PADDING_V * 2, presets = false)
    LayoutPane(state, items[state.activeIndex], look, area, spec, weighted = true)
}

/** WL-01 노션 위젯. Room 스냅샷만 읽는다(WG-02). */
class LayoutWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 180.dp)))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.layoutState()
        provideContent { LayoutWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val viewIds = WidgetLayout.parse(initialConfig.layoutJson).items.map { it.viewId }
        // 탭을 바꿀 때 빈 화면이 나오지 않게 모든 보기의 처음 값을 미리 읽는다(통합 위젯과 같은 요령).
        val initialBoards = viewIds.associateWith { deps.views().observe(it).first() }
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val layout = remember(config.layoutJson) { WidgetLayout.parse(config.layoutJson) }
            // 탭 이름이 보기 이름이라 숨은 탭의 보기도 함께 지켜본다(최대 5개, 바뀐 보기만 다시 읽는다).
            val boards by remember(layout) { boardsOf(deps, layout.items.map { it.viewId }) }.collectAsState(initialBoards)
            val fontScale by SystemFontScale.flow.collectAsState()
            LayoutWidgetContent(LayoutWidgetState(config, layout, boards, System.currentTimeMillis()), fontScale ?: SystemFontScale.current(context))
        }
    }

    private fun boardsOf(deps: WidgetDependencies, viewIds: List<String>): Flow<Map<String, ViewBoard?>> =
        if (viewIds.isEmpty()) flowOf(emptyMap())
        else combine(viewIds.map { id -> deps.views().observe(id).map { id to it } }) { it.toMap() }
}
