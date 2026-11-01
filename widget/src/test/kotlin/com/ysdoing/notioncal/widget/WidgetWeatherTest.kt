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
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.weather.Sky
import com.ysdoing.notioncal.core.data.weather.WeatherDay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * WX-04 위젯 날씨: 월간·'달력 + 일정' 위젯 머리에 오늘 날씨(위젯별 켜기), '달력 + 일정' 목록 날짜 머리에 일별 아이콘.
 * 켜지 않은 위젯에는 없고, 날씨를 켜도 2×2~4×4에서 RemoteViews로 바뀐다(잘림 검사는 치수·변환으로).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class WidgetWeatherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = WidgetSamples.today
    private val weather = WidgetWeather(
        mapOf(today to WeatherDay(today, Sky.CLEAR, 21, 12, 10), today.plusDays(2) to WeatherDay(today.plusDays(2), Sky.RAIN, 17, 11, 80)),
    )
    private val sizes = listOf(DpSize(110.dp, 110.dp), DpSize(180.dp, 190.dp), DpSize(358.dp, 168.dp), DpSize(380.dp, 420.dp))

    private fun month(on: Boolean) = WidgetSamples.state(config = WidgetConfig(1, showWeather = on)).copy(weather = weather)
    private fun split(on: Boolean) =
        SplitWidget.uiState(today, AppSettings(weekStart = DayOfWeek.SUNDAY), WidgetConfig(1, showWeather = on), WidgetSamples.events).copy(weather = weather)

    @Test
    fun monthHeaderShowsTodayOnlyWhenEnabled() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(380.dp, 420.dp))
        provideComposable { WidgetContent(month(on = true), systemFontScale = 1f) }
        onNode(hasContentDescriptionEqualTo("맑음, 최고 21도 최저 12도")).assertExists()
    }

    @Test
    fun monthHeaderHiddenWhenOff() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(380.dp, 420.dp))
        provideComposable { WidgetContent(month(on = false), systemFontScale = 1f) }
        onAllNodes(hasTestTag("weather")).assertCountEquals(0)
    }

    @Test
    fun splitHeaderAndDayHeaders() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(358.dp, 380.dp))
        provideComposable { SplitWidgetContent(split(on = true), systemFontScale = 1f) }
        // 머리(오늘) + 목록 날짜 머리(오늘·일정 있는 10/15). 일정 없는 날은 목록에 머리가 없다
        onAllNodes(hasContentDescriptionEqualTo("맑음, 최고 21도 최저 12도")).assertCountEquals(2)
        onNode(hasContentDescriptionEqualTo("비, 최고 17도 최저 11도")).assertExists()
    }

    private suspend fun render(size: DpSize, content: @Composable () -> Unit) {
        val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        frame.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
    }

    @Test
    fun translatesToRemoteViewsAtAllSizes() = runTest(timeout = 3.minutes) {
        for (font in listOf(100, 150)) {
            for (size in sizes) {
                val m = month(true).let { it.copy(config = it.config.copy(fontScale = font, theme = WidgetTheme.DARK)) }
                render(size) { WidgetContent(m, systemFontScale = 1.3f) }
                val s = split(true).let { it.copy(agenda = it.agenda.copy(config = it.config.copy(fontScale = font))) }
                render(size) { SplitWidgetContent(s, systemFontScale = 1.3f) }
            }
        }
    }
}
