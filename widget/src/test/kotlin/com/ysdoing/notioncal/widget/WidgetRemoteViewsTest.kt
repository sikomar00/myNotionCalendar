package com.ysdoing.notioncal.widget

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import kotlin.time.Duration.Companion.minutes

/**
 * 실제 RemoteViews 변환. Glance Row·Column 자식이 10개를 넘으면 여기서 예외가 난다(PRD §13).
 * 변환 결과를 View에 적용해 크기 단계별 스크린샷도 남긴다(WS-03).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class WidgetRemoteViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun renderView(size: DpSize, state: WidgetUiState): View = renderView(size) { WidgetContent(state) }

    private suspend fun renderView(size: DpSize, content: @Composable () -> Unit): View {
        val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
        // 스크린샷 도구가 Activity에 붙은 View를 요구한다. 제목 표시줄 없는 Activity에 위젯 크기 그대로 붙인다.
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

    private val small = DpSize(110.dp, 110.dp)
    private val medium = DpSize(280.dp, 299.dp)
    private val large = DpSize(380.dp, 420.dp)
    private val huge = DpSize(411.dp, 800.dp)

    @Test
    // 30번 렌더한다. 전체 병렬 빌드 중에는 1분(기본)을 넘길 때가 있어 넉넉히 둔다.
    fun denseDataTranslatesInEverySizeAndSetting() = runTest(timeout = 3.minutes) {
        val configs = listOf(
            WidgetConfig(1),
            WidgetConfig(1, fontScale = 70),
            WidgetConfig(1, fontScale = 150, maxPerCell = 6),
            WidgetConfig(1, theme = WidgetTheme.DARK, opacity = 0),
            WidgetConfig(1, theme = WidgetTheme.MATERIAL_YOU, opacity = 60),
        )
        for (size in listOf(small, medium, large, huge, DpSize(180.dp, 400.dp), DpSize(400.dp, 180.dp))) {
            for (config in configs) {
                renderView(size, WidgetSamples.state(WidgetSamples.dense, config))
            }
        }
    }

    @Test
    fun screenshotMaterialYou() = runTest {
        renderView(large, WidgetSamples.state(config = WidgetConfig(1, theme = WidgetTheme.MATERIAL_YOU)))
            .captureRoboImage("src/test/screenshots/widget_material_you.png")
    }

    @Test
    fun screenshotSmall() = runTest { renderView(small, WidgetSamples.state()).captureRoboImage("src/test/screenshots/widget_small.png") }

    @Test
    fun screenshotSmallS25() = runTest {
        renderView(DpSize(180.dp, 190.dp), WidgetSamples.state()).captureRoboImage("src/test/screenshots/widget_small_s25.png")
    }

    @Test
    fun screenshotWideShort4x2() = runTest {
        renderView(DpSize(358.dp, 152.dp), WidgetSamples.state()).captureRoboImage("src/test/screenshots/widget_4x2.png")
    }

    @Test
    fun screenshotMedium() = runTest { renderView(medium, WidgetSamples.state()).captureRoboImage("src/test/screenshots/widget_medium.png") }

    @Test
    fun screenshotLarge() = runTest { renderView(large, WidgetSamples.state()).captureRoboImage("src/test/screenshots/widget_large.png") }

    @Test
    fun screenshotLargeDense() = runTest {
        renderView(large, WidgetSamples.state(WidgetSamples.dense)).captureRoboImage("src/test/screenshots/widget_large_dense.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun screenshotDarkFont150() = runTest {
        renderView(large, WidgetSamples.state(config = WidgetConfig(1, fontScale = 150)))
            .captureRoboImage("src/test/screenshots/widget_large_dark_font150.png")
    }

    // WG-09 '달력 + 일정'. 빽빽한 날(8개)·여러 날 일정이 섞여도 모든 크기·설정에서 변환된다(자식 10개 제한).
    private fun splitState(config: WidgetConfig = WidgetConfig(1), events: List<com.ysdoing.notioncal.core.data.CalendarEvent> = WidgetSamples.events) =
        SplitWidget.uiState(WidgetSamples.today, AppSettings(weekStart = DayOfWeek.SUNDAY), config, events)

    @Test
    fun splitTranslatesInEverySizeAndSetting() = runTest(timeout = 3.minutes) {
        val configs = listOf(WidgetConfig(1), WidgetConfig(1, fontScale = 150), WidgetConfig(1, theme = WidgetTheme.MATERIAL_YOU, opacity = 60))
        for (size in listOf(DpSize(250.dp, 110.dp), DpSize(358.dp, 152.dp), DpSize(358.dp, 380.dp), huge)) {
            for (config in configs) renderView(size) { SplitWidgetContent(splitState(config, WidgetSamples.dense + WidgetSamples.events)) }
        }
    }

    @Test
    fun screenshotSplit4x2() = runTest {
        renderView(DpSize(358.dp, 168.dp)) { SplitWidgetContent(splitState(), systemFontScale = 1f) }
            .captureRoboImage("src/test/screenshots/split_4x2.png")
    }

    @Test
    fun screenshotSplit4x4() = runTest {
        renderView(DpSize(358.dp, 380.dp)) { SplitWidgetContent(splitState(), systemFontScale = 1f) }
            .captureRoboImage("src/test/screenshots/split_4x4.png")
    }
}
