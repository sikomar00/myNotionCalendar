package com.ysdoing.notioncal.widget

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import org.robolectric.Robolectric

// RemoteViews 변환·스크린샷 시험이 함께 쓰는 도구(월 캘린더·차트 보기 시험).

/** 시험이 쓰는 view id 상한. Glance 위젯 하나는 500개(glance_view_id_000~499)까지만 두고 넘으면 'There are too many views'로 오류 화면이 된다. 어림이 틀려도 남는 여유 20개. */
internal const val GLANCE_VIEW_ID_BUDGET = 480

/**
 * [content]를 실제 RemoteViews로 변환해 [size] 크기의 View로 붙인다. 변환이 한도를 넘으면 여기서 예외가 나고(PRD §13),
 * 스크린샷 도구가 Activity에 붙은 View를 요구하니 제목 표시줄 없는 Activity에 위젯 크기 그대로 붙인다.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
internal suspend fun renderRemoteViews(context: Context, size: DpSize, content: @Composable () -> Unit): View {
    val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
    val activity = Robolectric.buildActivity(Activity::class.java).create().get()
    activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
    val density = activity.resources.displayMetrics.density
    val w = (size.width.value * density).toInt()
    val h = (size.height.value * density).toInt()
    val widget = FrameLayout(activity)
    widget.addView(remoteViews.apply(activity, widget))
    val root = FrameLayout(activity)
    root.addView(widget, FrameLayout.LayoutParams(w, h))
    activity.setContentView(root)
    widget.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
    widget.layout(0, 0, w, h)
    return widget
}

/** Glance가 쓴 view id의 개수(glance_view_id_NNN의 가장 큰 번호 + 1). 한도는 500이다. */
internal fun glanceIdsUsed(view: View): Int {
    val own = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()?.removePrefix("glance_view_id_")?.toIntOrNull()?.plus(1) ?: 0
    val children = (view as? ViewGroup)?.let { g -> (0 until g.childCount).maxOfOrNull { glanceIdsUsed(g.getChildAt(it)) } } ?: 0
    return maxOf(own, children)
}
