package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

/** WG-08: 위젯 선택 화면 미리보기는 예시 일정만 쓰고(개인 일정 아님), 두 위젯 모두 실제 모양으로 그려진다. */
@RunWith(RobolectricTestRunner::class)
class WidgetPreviewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun usesOnlySampleEvents() {
        val events = WidgetPreviews.monthState(today).events + WidgetPreviews.agendaState(today).days.flatMap { day -> day.items.map { it.first } }
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.pageId.startsWith("preview-") && it.dataSourceId == "preview" })
    }

    @Test
    fun monthPreviewRenders() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(WidgetPreviews.MONTH_SIZE)
        provideComposable { WidgetContent(WidgetPreviews.monthState(today), systemFontScale = 1f) }
        onNode(hasTextEqualTo("10:00 팀 회의")).assertExists()
        onAllNodes(hasTestTag("day")).assertCountEquals(42)
    }

    @Test
    fun agendaPreviewRenders() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(WidgetPreviews.AGENDA_SIZE)
        provideComposable { AgendaWidgetContent(WidgetPreviews.agendaState(today), systemFontScale = 1f) }
        onNode(hasTextEqualTo("팀 회의")).assertExists()
        onAllNodes(hasTextEqualTo("출장")).assertCountEquals(3) // 3일짜리 기간 일정은 날마다 한 줄
    }
}
