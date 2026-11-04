package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
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
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle

internal val TAB_STRIP_PADDING = 6.dp
internal val TAB_MIN_WIDTH = TAP_TARGET
private val NARROW_ARROW_WIDTH = 28.dp
private val ARROW_FULL_MIN_WIDTH = 200.dp
private val TAB_LABEL_MIN_WIDTH = 72.dp

/** 화살표 눌림 폭. 48dp를 지키되 가장 좁은 위젯(일반 런처 2×2, 150dp 안팎)은 탭 자리를 위해 줄인다(A11Y-02의 2×2 예외). */
internal fun arrowWidth(width: Dp): Dp = if (width < ARROW_FULL_MIN_WIDTH) NARROW_ARROW_WIDTH else TAP_TARGET

/**
 * 탭 줄에 그릴 탭 구간. [first]부터 [visible]개를 그리고, 탭이 다 안 들어가면 [arrows]로 앞뒤 탭으로 옮긴다(화살표 폭 [arrow]).
 * [allLabels]가 거짓이면 고른 탭만 이름이고 나머지는 첫 글자 칩이다.
 */
internal data class TabWindow(val first: Int, val visible: Int, val arrows: Boolean, val allLabels: Boolean, val arrow: Dp)

/**
 * WL-02 탭 줄 폭 규칙. 눌림 폭은 탭마다 [TAB_MIN_WIDTH](48dp) 이상이고, 다 못 넣으면 화살표 두 개를 두고 들어가는 만큼만 보인다(창은 고른 탭 가운데).
 * shortcut: 위젯 최소 크기(110dp)에서 탭 3개 이상이면 탭 하나가 42dp라 48dp보다 좁다, 최소 크기를 키울 때 같이 푼다.
 */
internal fun tabWindow(selected: Int, count: Int, width: Dp): TabWindow {
    val room = width - TAB_STRIP_PADDING * 2
    val arrows = room < TAB_MIN_WIDTH * count
    val arrow = arrowWidth(width)
    val tabsRoom = if (arrows) room - arrow * 2 else room
    val visible = if (arrows) (tabsRoom / TAB_MIN_WIDTH).toInt().coerceIn(1, count) else count
    val first = (selected.coerceIn(0, count - 1) - (visible - 1) / 2).coerceIn(0, count - visible)
    return TabWindow(first, visible, arrows, allLabels = tabsRoom / visible >= TAB_LABEL_MIN_WIDTH, arrow = arrow)
}

/** 이름의 첫 글자(이모지 같은 두 칸짜리 글자도 통째로). 이름이 비면 물음표다. */
internal fun tabInitial(title: String): String {
    val text = title.trim()
    return if (text.isEmpty()) "?" else text.substring(0, text.offsetByCodePoints(0, 1))
}

/** 탭 줄의 모양: 줄 높이, 글자 크기(sp), 색. */
internal data class TabLook(val height: Dp, val fontSp: Float, val palette: WidgetPalette)

/** 탭 하나의 내용. [text]는 그릴 글자(이름 또는 첫 글자), [chip]이면 눌림 폭만 차지하는 첫 글자 칩이다. */
private data class TabCell(val index: Int, val title: String, val text: String, val selected: Boolean, val chip: Boolean)

/** 탭 줄. 자식은 탭 5개 + 화살표 2개 이하라 Row 자식 10개 제한 안이다. */
@Composable
internal fun LayoutTabStrip(titles: List<String>, selected: Int, width: Dp, look: TabLook) {
    val context = LocalContext.current
    val window = tabWindow(selected, titles.size, width)
    Row(GlanceModifier.fillMaxWidth().height(look.height).padding(horizontal = TAB_STRIP_PADDING)) {
        if (window.arrows) {
            LayoutTabArrow(R.drawable.ic_widget_prev, context.getString(R.string.layout_widget_tab_prev), "layoutTabPrev", (selected - 1).takeIf { it >= 0 }, window.arrow, look)
        }
        for (index in window.first until window.first + window.visible) {
            val isSelected = index == selected
            val chip = !isSelected && !window.allLabels
            LayoutTab(TabCell(index, titles[index], if (chip) tabInitial(titles[index]) else titles[index], isSelected, chip), look)
        }
        if (window.arrows) {
            LayoutTabArrow(R.drawable.ic_widget_next, context.getString(R.string.layout_widget_tab_next), "layoutTabNext", (selected + 1).takeIf { it < titles.size }, window.arrow, look)
        }
    }
}

/**
 * 탭 버튼. 첫 글자 칩이어도 접근성 이름은 보기 이름이다. 고른 탭은 굵은 먹색 + 강조색 막대(선택 상태라 강조색을 쓴다).
 * 칩은 눌림 폭(48dp)만 차지하고 고른 탭이 남는 폭을 모두 가져가, 이름이 칩 폭에 갇혀 잘리지 않는다.
 */
@Composable
private fun RowScope.LayoutTab(cell: TabCell, look: TabLook) {
    val context = LocalContext.current
    val color = if (cell.selected) look.palette.text else look.palette.subtle
    Column(
        modifier = (if (cell.chip) GlanceModifier.width(TAB_MIN_WIDTH) else GlanceModifier.defaultWeight()).height(look.height)
            .clickable(actionRunCallback<LayoutTabAction>(actionParametersOf(LayoutTabAction.IndexKey to cell.index)))
            .semantics {
                contentDescription = if (cell.selected) context.getString(R.string.tabbed_widget_tab_selected, cell.title) else cell.title
                testTag = "layoutTab_${cell.index}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            GlanceModifier.fillMaxWidth().defaultWeight().padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = cell.text,
                style = TextStyle(color = color, fontSize = look.fontSp.sp, fontWeight = if (cell.selected) FontWeight.Bold else null, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
        if (cell.selected) {
            Box(GlanceModifier.fillMaxWidth().height(2.dp).padding(horizontal = 8.dp)) {
                Box(GlanceModifier.fillMaxSize().cornerRadius(1.dp).background(look.palette.accent)) {}
            }
        } else {
            Spacer(GlanceModifier.height(2.dp))
        }
    }
}

/** 앞·뒤 탭으로 옮기는 화살표. 끝이라 갈 곳이 없으면([target]이 null) 흐리게 그리고 누를 수 없다. */
@Composable
private fun LayoutTabArrow(icon: Int, description: String, tag: String, target: Int?, width: Dp, look: TabLook) {
    val press = target?.let { GlanceModifier.clickable(actionRunCallback<LayoutTabAction>(actionParametersOf(LayoutTabAction.IndexKey to it))) } ?: GlanceModifier
    Box(
        GlanceModifier.width(width).height(look.height).then(press).semantics {
            contentDescription = description
            testTag = tag
        },
        contentAlignment = Alignment.Center,
    ) {
        WidgetIcon(icon, if (target != null) look.palette.text else look.palette.subtle, 16.dp)
    }
}

/** WL-02 탭 바꾸기: 그 위젯의 탭 번호만 저장하고(마지막으로 본 탭을 기억) 그 위젯만 다시 그린다. */
class LayoutTabAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val index = parameters[IndexKey] ?: return
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        WidgetDependencies.from(context).widgetConfigs().selectLayoutTab(appWidgetId, index)
        LayoutWidget().update(context, glanceId)
    }

    companion object {
        val IndexKey = ActionParameters.Key<Int>("layoutTab")
    }
}
