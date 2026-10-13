package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.calendar.gridStart
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate

/** WS-03 크기 단계별 구성과 WI-04 버튼을 Glance 단위 테스트로 확인한다. */
@RunWith(RobolectricTestRunner::class)
class WidgetContentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun render(size: DpSize, state: WidgetUiState, block: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(size)
            provideComposable { WidgetContent(state) }
            block()
        }

    @Test
    fun largeShowsTitlesAndNavButtons() = render(DpSize(380.dp, 420.dp), WidgetSamples.state()) {
        onNode(hasTestTag("prev")).assertExists()
        onNode(hasTestTag("next")).assertExists()
        onNode(hasTestTag("today")).assertExists()
        onNode(hasTestTag("add")).assertExists()
        onNode(hasTestTag("refresh")).assertExists()
        // 날짜 칸 42개 모두 앱을 여는 클릭 동작이 붙어 있다(Spacer는 클릭이 안 붙어 Box로 바꿈).
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
        onAllNodes(hasTestTag("day")).assertAll(hasClickAction())
        // 표본 7개 중 2개는 주 경계를 넘는 기간 일정이라 막대가 두 주에 걸쳐 그려진다(4: 10/30~11/2는 한 주 안).
        onAllNodes(hasTestTag("chip")).assertCountEquals(8)
        onAllNodes(hasTestTag("dot")).assertCountEquals(0)
    }

    @Test
    fun smallShowsDotsOnly() {
        // 그리드 42일마다 그 날에 걸친 일정 수(최대 3)만큼 점이 찍힌다.
        val start = gridStart(WidgetSamples.october, DayOfWeek.SUNDAY)
        val expected = (0 until 42).sumOf { offset ->
            val date = start.plusDays(offset.toLong())
            minOf(3, WidgetSamples.events.count { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) })
        }
        render(DpSize(180.dp, 190.dp), WidgetSamples.state()) {
            onAllNodes(hasTestTag("chip")).assertCountEquals(0)
            onAllNodes(hasTestTag("dot")).assertCountEquals(expected)
        }
    }

    @Test
    fun mediumShowsTitleAndPlusNOnOneLine() = render(DpSize(250.dp, 250.dp), WidgetSamples.state()) {
        // 중간(WS-03): 10/13처럼 일정이 2개인 날도 제목 1개와 +1이 함께 보인다.
        onNode(hasTextEqualTo("영어 스터디")).assertExists()
        onAllNodes(hasTextEqualTo("+1")).assertCountEquals(2) // 10/13(2개), 10/31(완료 일정 + 기간 일정)
        onAllNodes(hasTestTag("chip")).assertCountEquals(daysWithEvents(WidgetSamples.events))
    }

    @Test
    fun maxPerCellOneUsesSingleLinePerDay() {
        // 큰 위젯이라도 칸당 1줄로 설정하면 날짜마다 첫 일정 + N만 보인다(WS-02).
        val config = WidgetConfig(1, maxPerCell = 1)
        val oct14 = LocalDate.of(2026, 10, 14)
        val countOn14 = WidgetSamples.dense.count { !it.startDate.isAfter(oct14) && !it.lastDate.isBefore(oct14) }
        render(DpSize(380.dp, 420.dp), WidgetSamples.state(WidgetSamples.dense, config)) {
            onAllNodes(hasTestTag("chip")).assertCountEquals(daysWithEvents(WidgetSamples.dense))
            onNode(hasTextEqualTo("+${countOn14 - 1}")).assertExists()
        }
    }

    /** 그리드 42일 중 일정이 하나라도 걸친 날 수. */
    private fun daysWithEvents(events: List<CalendarEvent>): Int {
        val start = gridStart(WidgetSamples.october, DayOfWeek.SUNDAY)
        return (0 until 42).count { offset ->
            val date = start.plusDays(offset.toLong())
            events.any { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) }
        }
    }
}
