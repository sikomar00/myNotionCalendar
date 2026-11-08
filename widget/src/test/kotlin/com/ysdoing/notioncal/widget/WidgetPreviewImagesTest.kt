package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.APP_ZONE
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * WG-08 위젯 고르기 미리보기 그림(`previewImage`). 삼성 런처처럼 생성 미리보기를 쓰지 않는 런처는 이 그림을 보인다.
 * 예시 데이터(개인 데이터 아님)를 고정 날짜로 그려 `src/main/res/drawable-nodpi`에 기록하고, verifyRoborazziDebug가 코드와 어긋나면 잡는다.
 * 크기는 각 위젯의 생성 미리보기([androidx.glance.appwidget.GlanceAppWidget.previewSizeMode])와 같다. xhdpi로 그려 파일을 작게 둔다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xhdpi")
class WidgetPreviewImagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.of(2026, 11, 10)
    private val now = today.atTime(12, 0).atZone(APP_ZONE).toInstant().toEpochMilli()
    private val square = DpSize(180.dp, 180.dp)

    private suspend fun image(name: String, size: DpSize, content: @Composable () -> Unit) {
        renderRemoteViews(context, size, staticLists = true, content = content).captureRoboImage("src/main/res/drawable-nodpi/widget_preview_$name.png")
    }

    @Test
    fun calendarWidgets() = runTest(timeout = 3.minutes) {
        val month = WidgetPreviews.monthState(today)
        image("month", WidgetPreviews.MONTH_SIZE) { WidgetContent(month, systemFontScale = 1f) }
        image("agenda", WidgetPreviews.AGENDA_SIZE) { AgendaWidgetContent(WidgetPreviews.agendaState(today), systemFontScale = 1f) }
        image("split", WidgetPreviews.SPLIT_SIZE) { SplitWidgetContent(WidgetPreviews.splitState(today), systemFontScale = 1f) }
        image("tabbed", WidgetPreviews.MONTH_SIZE) {
            TabbedWidgetContent(month.config, systemFontScale = 1f) { WidgetContent(month, systemFontScale = 1f, framed = false) }
        }
    }

    @Test
    fun habitLedgerAndDday() = runTest(timeout = 3.minutes) {
        image("habit", WidgetPreviews.HABIT_SIZE) { HabitWidgetContent(WidgetPreviews.habitState(today), systemFontScale = 1f) }
        image("ledger", WidgetPreviews.MONTH_SIZE) { LedgerWidgetContent(WidgetPreviews.ledgerState(today), systemFontScale = 1f) }
        val dday = WidgetPreviews.ddayState(today)
        image("dday_small", DpSize(70.dp, 70.dp)) { DdayWidgetContent(dday, systemFontScale = 1f) }
        image("dday_wide", DpSize(150.dp, 70.dp)) { DdayWidgetContent(dday, systemFontScale = 1f) }
        image("dday_list", square) { DdayListWidgetContent(dday, systemFontScale = 1f) }
    }

    @Test
    fun notionViewWidgets() = runTest(timeout = 3.minutes) {
        image("view", square) { ViewWidgetContent(WidgetPreviews.viewState(now), systemFontScale = 1f, staticRows = true) }
        image("layout", square) { LayoutWidgetContent(WidgetPreviews.layoutState(now), systemFontScale = 1f, staticRows = true) }
        image("book", square) { BookWidgetContent(WidgetPreviews.bookState(now), systemFontScale = 1f) }
    }
}
