package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.ViewBoard

// Phase 11a VR-BRD: 노션 보드 보기를 `group_by` 열로 나눠 그린다. 위젯은 가로 스크롤이 없어 열 2~3개만 보이고 ‹ ›로 넘긴다.

private val COLUMN_GAP = 6.dp
private val NAV_HEIGHT = 48.dp // 누름 영역 최소 48dp(보이는 동그라미는 28dp)

/** 열 넘김 위치를 [BoardBody]가 걸러 쓴다(범위를 벗어나면 가장 가까운 끝). 한 번에 한 열씩 넘긴다. */
@Composable
internal fun BoardBody(board: ViewBoard, groups: List<BoardGroup>, layout: BoardLayout, page: Int, look: ViewLook) {
    val maxStart = (groups.size - layout.columns).coerceAtLeast(0)
    val start = page.coerceIn(0, maxStart)
    Column(GlanceModifier.fillMaxSize()) {
        if (maxStart > 0) BoardNav(start, maxStart, groups.size, layout.columns, look)
        Row(GlanceModifier.fillMaxSize()) {
            groups.drop(start).take(layout.columns).forEachIndexed { i, group ->
                if (i > 0) Spacer(GlanceModifier.width(COLUMN_GAP))
                BoardColumn(board, group, layout.cardsPerColumn, look, GlanceModifier.defaultWeight())
            }
        }
    }
}

/** ‹ 3–5 / 7 › : 넘길 열이 있을 때만 한 줄을 쓴다. 끝에서는 그쪽 화살표를 뺀다. */
@Composable
private fun BoardNav(start: Int, maxStart: Int, total: Int, columns: Int, look: ViewLook) {
    val context = LocalContext.current
    val range = context.getString(R.string.view_widget_board_range, start + 1, minOf(start + columns, total), total)
    Row(GlanceModifier.fillMaxWidth().height(NAV_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        if (start > 0) NavButton(R.drawable.ic_widget_prev, context.getString(R.string.view_widget_board_prev), "boardPrev", start - 1, look)
        Text(range, modifier = GlanceModifier.defaultWeight(), style = look.sub.copy(fontSize = look.pillSp.sp), maxLines = 1)
        if (start < maxStart) NavButton(R.drawable.ic_widget_next, context.getString(R.string.view_widget_board_next), "boardNext", start + 1, look)
    }
}

@Composable
private fun NavButton(icon: Int, description: String, tag: String, targetPage: Int, look: ViewLook) {
    WidgetIconButton(
        icon, description, tag, actionRunCallback<BoardPageAction>(actionParametersOf(BoardPageAction.PageKey to targetPage)), look.palette,
        visual = 28.dp, height = NAV_HEIGHT,
    )
}

/** 열 하나: 머리(옵션 색 점 + 이름 + 개수) 아래 카드 [cap]장과, 넘치면 '+N 더 보기'(뷰어로). */
@Composable
private fun BoardColumn(board: ViewBoard, group: BoardGroup, cap: Int, look: ViewLook, modifier: GlanceModifier) {
    val context = LocalContext.current
    val name = group.name.ifEmpty { context.getString(R.string.view_widget_board_none) }
    val count = if (board.rowsComplete) "${group.rows.size}" else "${group.rows.size}+" // 100행만 받았다면 '적어도 N'
    val description = context.getString(if (board.rowsComplete) R.string.view_widget_board_column else R.string.view_widget_board_column_plus, name, group.rows.size)
    Column(modifier.semantics { testTag = "boardColumn"; contentDescription = description }) {
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val dotColor = group.color?.let(::dot)
            if (dotColor != null) {
                Box(GlanceModifier.size(8.dp).background(dotColor).cornerRadius(4.dp)) {}
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(name, modifier = GlanceModifier.defaultWeight(), style = look.title.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Text(count, style = look.sub)
        }
        group.rows.take(cap).forEach { row ->
            Box(GlanceModifier.fillMaxWidth().padding(bottom = 3.dp)) { BoardCard(row.cells.firstOrNull().orEmpty(), rowSheetAction(context, board.viewId, row), look) }
        }
        val rest = group.rows.size - cap
        if (rest > 0) MoreLine(context.getString(R.string.view_widget_more, rest), board.viewId, look)
    }
}

@Composable
private fun BoardCard(title: String, action: androidx.glance.action.Action?, look: ViewLook) {
    val base = GlanceModifier.fillMaxWidth().tile(look.palette).padding(horizontal = 6.dp, vertical = 4.dp)
    Text(title, modifier = (action?.let { base.clickable(it) } ?: base).semantics { testTag = "boardCard" }, style = look.cell, maxLines = 2)
}

/** 노션 옵션 색 점. 노션 기본 색(`default`)이나 모르는 이름이면 점을 그리지 않는다. */
private fun dot(color: String): ColorProvider? =
    NotionColors.hexFor(color)?.takeIf { color != "default" }?.let { ColorProvider(Color(NotionColors.argb(it))) }

/**
 * ‹ › 누르기: 보드의 첫 열 위치([com.ysdoing.notioncal.core.data.WidgetConfig.viewPage])를 [PageKey]로 바꾸고 다시 그린다.
 * 저장값이 아니라 화면에 보이는 위치에서 한 칸 옮긴 절대 위치를 받아, 저장값이 끝을 넘어 있어도 눌림이 헛돌지 않는다.
 */
class BoardPageAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val page = parameters[PageKey] ?: return
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val configs = WidgetDependencies.from(context).widgetConfigs()
        val current = configs.get(appWidgetId)
        configs.save(current.copy(viewPage = page.coerceAtLeast(0)))
        WidgetKinds.update(context, glanceId)
    }

    companion object {
        val PageKey = ActionParameters.Key<Int>("boardPage")
    }
}

/** 열 아래의 '+N 더 보기'. 눌러서 뷰어 화면에서 그 보기를 연다(VR-02). */
@Composable
internal fun MoreLine(text: String, viewId: String, look: ViewLook) {
    val context = LocalContext.current
    Box(
        GlanceModifier.fillMaxWidth().height(MORE_HEIGHT).clickable(actionStartActivity(viewerIntent(context, viewId))).semantics { testTag = "viewMore" },
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = look.sub, maxLines = 1) }
}

private val MORE_HEIGHT = 32.dp // 글자만 누르게 두면 누를 곳이 10dp라 키운다(밀집 행은 PRD UX-02 예외지만 이 줄은 하나뿐이다)
