package com.ysdoing.notioncal.widget

import android.content.Context
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.WidgetLayout

/** 홈 화면에 놓인 위젯 하나(설정의 '위젯' 목록). [kindLabel]은 위젯 종류 이름, [summary]는 고른 보기·배치 요약(없으면 빈 글자)이다. */
data class PlacedWidget(val appWidgetId: Int, val kindLabel: String, val summary: String)

/** 위젯 종류[receiver]와 설정으로 요약 한 줄을 만든다. [viewNames]는 등록한 보기 ID → 이름. */
internal fun widgetSummary(context: Context, receiver: String, config: WidgetConfig, viewNames: Map<String, String>): String = when (receiver) {
    LayoutWidgetReceiver::class.java.name -> layoutSummary(context, config, viewNames)
    ViewWidgetReceiver::class.java.name, BookWidgetReceiver::class.java.name -> config.viewId
        ?.let { viewNames[it]?.ifBlank { null } ?: context.getString(R.string.placed_view_untitled) }
        ?: context.getString(R.string.placed_view_none)
    else -> ""
}

private fun layoutSummary(context: Context, config: WidgetConfig, viewNames: Map<String, String>): String {
    val layout = WidgetLayout.parse(config.layoutJson)
    val tiles = layout.tiles.size.takeIf { layout.mode == LayoutMode.SECTIONS && it > 0 }
    if (layout.items.isEmpty() && tiles == null) return context.getString(R.string.placed_view_none)
    val names = layout.items.joinToString(", ") { it.title(viewNames[it.viewId].orEmpty()) }
    val body = when {
        layout.items.isEmpty() -> null
        layout.mode == LayoutMode.TABS -> context.getString(R.string.placed_layout_tabs, layout.items.size, names)
        layout.mode == LayoutMode.SECTIONS -> context.getString(R.string.placed_layout_sections, layout.items.size, names)
        else -> names
    }
    return listOfNotNull(body, tiles?.let { context.getString(R.string.placed_layout_tiles, it) }).joinToString(" · ")
}
