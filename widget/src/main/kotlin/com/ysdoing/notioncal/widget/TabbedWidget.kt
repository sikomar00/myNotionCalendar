package com.ysdoing.notioncal.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTab

/**
 * WG-11 통합 탭 위젯: 위쪽 탭 버튼(📅 일정 · ✅ 습관 · 💰 가계부)으로 내용을 바꾼다. 📅 탭은 월간 위젯([WidgetContent]),
 * ✅ 탭은 습관 위젯([HabitWidgetContent]), 💰 탭은 가계부 위젯([LedgerWidgetContent]) 내용을 그대로 쓰고, 탭 줄을 뺀 크기를 안쪽에 알려 각자 크기 단계를 정한다.
 * 고른 탭은 위젯마다 저장하고(widget_configs.selectedTab) 그 위젯만 다시 그린다. 좌우 밀기는 위젯 라이브러리가 지원하지 않는다.
 */
class TabbedWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** WG-08 위젯 선택 화면 미리보기: 4×4 크기에 예시 일정(개인 일정 아님). */
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetPreviews.MONTH_SIZE))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.monthState()
        provideContent {
            TabbedWidgetContent(state.config, systemFontScale = 1f) { WidgetContent(state, systemFontScale = 1f, framed = false) }
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        // 검증 메모 11: 두 탭의 초기값을 미리 읽어 탭을 바꿀 때 빈 화면이 나오지 않게 한다.
        val month = MonthSession.load(deps, initialConfig)
        val habits = HabitSession.load(deps)
        val ledger = LedgerSession.load(deps)
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val fontScale by SystemFontScale.flow.collectAsState()
            val scale = fontScale ?: SystemFontScale.current(context)
            // 고른 탭의 세션만 그리므로 그 탭의 Flow만 모은다.
            TabbedWidgetContent(config, scale) { tab ->
                when (tab) {
                    WidgetTab.CALENDAR -> MonthSession.Content(deps, month, config, scale, framed = false)
                    WidgetTab.HABIT -> HabitSession.Content(deps, habits, config, scale, framed = false, showHeader = false)
                    WidgetTab.LEDGER -> LedgerSession.Content(deps, ledger, config, scale, framed = false)
                }
            }
        }
    }

    companion object {
        /** 탭 줄 높이. 글자 칸(실측 규칙) + 선택 막대, 최소 36dp. */
        fun tabRowHeight(config: WidgetConfig, systemFontScale: Float): Dp {
            val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
            return maxOf(36.dp, (TAB_FONT * config.fontScale / 100f * textScale * WidgetMetrics.TEXT_BOX).dp + 8.dp)
        }

        /** 안쪽 내용이 쓸 크기(탭 줄 제외). */
        fun contentSize(size: DpSize, config: WidgetConfig, systemFontScale: Float): DpSize =
            DpSize(size.width, (size.height - tabRowHeight(config, systemFontScale)).coerceAtLeast(0.dp))

        internal const val TAB_FONT = 12f
    }
}

/** 통합 위젯 틀: 탭 줄 + 고른 탭 내용. 탭 줄 자식은 3개(📅·✅·💰). */
@Composable
fun TabbedWidgetContent(
    config: WidgetConfig,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
    content: @Composable (WidgetTab) -> Unit,
) {
    val context = LocalContext.current
    val palette = WidgetPalette.of(config.theme, config.opacity, context)
    val size = LocalSize.current
    val tabHeight = TabbedWidget.tabRowHeight(config, systemFontScale)
    val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
    val fontSp = TabbedWidget.TAB_FONT * config.fontScale / 100f * textScale / systemFontScale
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background),
    ) {
        Row(GlanceModifier.fillMaxWidth().height(tabHeight).padding(horizontal = 6.dp)) {
            WidgetTab.entries.forEach { tab -> TabButton(tab, selected = tab == config.selectedTab, tabHeight, fontSp, palette) }
        }
        Box(GlanceModifier.fillMaxWidth().defaultWeight()) {
            // 검증 메모 11: 안쪽 내용은 LocalSize로 크기 단계를 정하므로 탭 줄을 뺀 크기를 알려 준다.
            CompositionLocalProvider(LocalSize provides TabbedWidget.contentSize(size, config, systemFontScale)) {
                content(config.selectedTab)
            }
        }
    }
}

/** 탭 버튼. 고른 탭은 굵은 먹색 + 강조색 막대(선택 상태라 강조색을 쓴다). */
@Composable
private fun RowScope.TabButton(tab: WidgetTab, selected: Boolean, height: Dp, fontSp: Float, palette: WidgetPalette) {
    val context = LocalContext.current
    val label = context.getString(
        when (tab) {
            WidgetTab.CALENDAR -> R.string.tabbed_widget_tab_calendar
            WidgetTab.HABIT -> R.string.tabbed_widget_tab_habit
            WidgetTab.LEDGER -> R.string.tabbed_widget_tab_ledger
        },
    )
    Column(
        modifier = GlanceModifier
            .defaultWeight()
            .height(height)
            .clickable(actionRunCallback<TabAction>(actionParametersOf(TabAction.TabKey to tab.name)))
            .semantics {
                contentDescription = if (selected) context.getString(R.string.tabbed_widget_tab_selected, label) else label
                testTag = "tab_${tab.name}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(top = 6.dp),
            style = TextStyle(
                color = if (selected) palette.text else palette.subtle,
                fontSize = fontSp.sp,
                fontWeight = if (selected) FontWeight.Bold else null,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
        if (selected) {
            Box(GlanceModifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp)) {
                Box(GlanceModifier.fillMaxSize().cornerRadius(1.dp).background(palette.accent)) {}
            }
        } else {
            Spacer(GlanceModifier.height(2.dp))
        }
    }
}

/** WG-11 탭 바꾸기: 그 위젯의 탭만 저장하고 그 위젯만 다시 그린다(0.5초). */
class TabAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val started = SystemClock.elapsedRealtime()
        val tab = parameters[TabKey]?.let { name -> WidgetTab.entries.firstOrNull { it.name == name } } ?: return
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        WidgetDependencies.from(context).widgetConfigs().selectTab(appWidgetId, tab)
        TabbedWidget().update(context, glanceId)
        Log.d(GlanceWidgetRefresher.TAG, "탭 전환 반영: ${SystemClock.elapsedRealtime() - started}ms")
    }

    companion object {
        val TabKey = ActionParameters.Key<String>("tab")
    }
}
