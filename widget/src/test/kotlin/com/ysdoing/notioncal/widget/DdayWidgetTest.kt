package com.ysdoing.notioncal.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.dday.Dday
import com.ysdoing.notioncal.core.data.dday.DdayMath
import com.ysdoing.notioncal.core.data.dday.DdayMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * DD-03 1×1(숫자 크게 + 제목), DD-04 2×1(숫자 + 제목 + 날짜, 탭 → 편집 시트), DD-05 목록(가까운 순 스크롤, 줄 탭 → 편집 시트,
 * '+' → 디데이 화면, 비면 안내). 글꼴 최대에서 숫자(세 자리·네 자리) 잘림 0을 치수로 확인한다.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class DdayWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.of(2026, 10, 19)
    private val small = DpSize(70.dp, 70.dp)
    private val wide = DpSize(150.dp, 70.dp)
    private val list = DpSize(150.dp, 160.dp)

    private fun dday(id: String, title: String, start: LocalDate, end: LocalDate? = null, mode: DdayMode = DdayMode.DOWN) =
        Dday(id, title, start, end, mode, 7, "#0B6E99", DdayMath.label(start, end, today, mode))

    private val exam = dday("exam", "기말고사", today.plusDays(12))
    private val trip = dday("trip", "제주 여행", today.minusDays(1), today.plusDays(1))

    @Test
    fun smallShowsBigNumberAndTitleAndOpensEditSheet() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(small)
        val state = DdayWidgetState(listOf(trip, exam), WidgetConfig(1, ddayPageId = "exam"), today)
        provideComposable { DdayWidgetContent(state, systemFontScale = 1f) }
        onNode(hasTextEqualTo("D-12")).assertExists()
        onNode(hasTextEqualTo("기말고사")).assertExists()
        onNode(hasTestTag("dday")).assert(hasStartActivityClickAction(ddayEditIntent(context, "exam", exam.start!!)))
        onNode(hasContentDescriptionEqualTo("기말고사, D-12, 10월 31일")).assertExists()
    }

    @Test
    fun wideShowsDateAndProgress() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        // 고른 디데이가 없으면 가장 가까운 디데이(진행 중인 여행).
        provideComposable { DdayWidgetContent(DdayWidgetState(listOf(trip, exam), WidgetConfig(1), today), systemFontScale = 1f) }
        onNode(hasTextEqualTo("D-Day")).assertExists()
        onNode(hasTextEqualTo("제주 여행")).assertExists()
        onNode(hasTextEqualTo("2/3일째")).assertExists()
    }

    @Test
    fun wideShowsWeekdayDate() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { DdayWidgetContent(DdayWidgetState(listOf(exam), WidgetConfig(1, ddayPageId = "exam"), today), systemFontScale = 1f) }
        onNode(hasTextEqualTo("10/31 토")).assertExists()
    }

    @Test
    fun emptyOpensDdayScreen() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(small)
        provideComposable { DdayWidgetContent(DdayWidgetState(emptyList(), WidgetConfig(1), today), systemFontScale = 1f) }
        onNode(hasTextEqualTo("디데이 없음")).assertExists()
        onNode(hasTestTag("dday")).assert(hasStartActivityClickAction(ddayScreenIntent(context)))
        assertEquals(Intent.ACTION_VIEW, ddayScreenIntent(context).action)
        assertEquals(Uri.parse("notioncal://dday"), ddayScreenIntent(context).data)
    }

    /** 글꼴 최대(위젯 150%, 기기 2.0)에서도 숫자·제목 칸이 위젯 안에 들어가고, 네 자리는 한 단계 작다. */
    @Test
    fun metricsFitAtMaxFont() {
        val labels = listOf("D-12", "D-123", "D-1234", "D-Day", "100일째", "D+3")
        for (size in listOf(DpSize(40.dp, 40.dp), small, wide, DpSize(110.dp, 40.dp))) {
            for (label in labels) {
                for ((font, system) in listOf(100 to 1f, 150 to 2f)) {
                    val m = DdayMetrics.of(size, WidgetConfig(1, fontScale = font), system, label)
                    assertTrue("$size $label $m", m.fitsHeight(size))
                    assertTrue("$size $label $m", m.fitsWidth(size, label))
                }
            }
        }
        val two = DdayMetrics.of(small, WidgetConfig(1), 1f, "D-12")
        val four = DdayMetrics.of(small, WidgetConfig(1), 1f, "D-1234")
        assertTrue("$two $four", four.numberSp < two.numberSp)
        assertTrue(DdayMetrics.of(wide, WidgetConfig(1), 1f, "D-12").wide)
        assertTrue(!DdayMetrics.of(small, WidgetConfig(1), 1f, "D-12").wide)
    }

    @Test
    fun listShowsTwentyNearestFirstAndAddButton() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(list)
        val many = (1..20).map { dday("p$it", "일정 $it", today.plusDays(it * 3L)) }
        provideComposable { DdayListWidgetContent(DdayWidgetState(many, WidgetConfig(1), today), systemFontScale = 1f) }
        onAllNodes(hasTestTag("ddayRow")).assertCountEquals(20)
        onNode(hasTextEqualTo("D-3")).assertExists()
        onNode(hasTestTag("ddayAdd")).assert(hasStartActivityClickAction(ddayScreenIntent(context)))
        onNode(hasContentDescriptionEqualTo("일정 1, D-3, 10월 22일"))
            .assert(hasStartActivityClickAction(ddayEditIntent(context, "p1", today.plusDays(3))))
    }

    @Test
    fun emptyListExplains() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(list)
        provideComposable { DdayListWidgetContent(DdayWidgetState(emptyList(), WidgetConfig(1), today), systemFontScale = 1f) }
        onNode(hasTextEqualTo("고정한 디데이가 없습니다. +를 눌러 고정하세요")).assertExists()
    }

    private suspend fun render(size: DpSize, content: @Composable () -> Unit) {
        val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        frame.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
    }

    @Test
    fun translatesToRemoteViews() = runTest(timeout = 3.minutes) {
        val many = (1..20).map { dday("p$it", "일정 $it", today.plusDays(it * 100L)) }
        for (config in listOf(WidgetConfig(1), WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 60))) {
            for (size in listOf(small, wide)) render(size) { DdayWidgetContent(DdayWidgetState(many, config, today), systemFontScale = 1.3f) }
            render(list) { DdayListWidgetContent(DdayWidgetState(many, config, today), systemFontScale = 1.3f) }
            render(list) { DdayListWidgetContent(DdayWidgetState(emptyList(), config, today), systemFontScale = 1.3f) }
        }
    }
}
