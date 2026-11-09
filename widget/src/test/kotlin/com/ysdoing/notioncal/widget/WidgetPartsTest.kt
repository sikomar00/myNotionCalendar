package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.layout.Column
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/** 공통 부품: 눌림·낭독·태그가 모든 위젯에서 같게 나온다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class WidgetPartsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val palette get() = WidgetPalette.of(WidgetTheme.DARK, 100, context)
    private val refresh get() = actionRunCallback<RefreshAction>()

    @Test
    fun iconButtonIsDescribedTaggedAndClickable() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 200.dp))
        provideComposable {
            Column {
                WidgetIconButton(R.drawable.ic_widget_refresh, "새로고침", "refresh", refresh, palette)
                WidgetIconButton(R.drawable.ic_widget_add, "추가", "add", refresh, palette, style = IconButtonStyle.FILLED)
                WidgetIconButton(R.drawable.ic_widget_next, "다음", "next", refresh, palette, style = IconButtonStyle.TONAL)
            }
        }
        listOf("refresh" to "새로고침", "add" to "추가", "next" to "다음").forEach { (tag, description) ->
            onNode(hasTestTag(tag)).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
            onNode(hasContentDescriptionEqualTo(description)).assertExists()
        }
    }

    @Test
    fun checkIsDescribedAndClickableInBothStates() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 200.dp))
        provideComposable {
            Column {
                WidgetCheck(true, "장보기 체크됨, 눌러서 해제", "on", refresh, palette)
                WidgetCheck(false, "보고서 체크 안 됨, 눌러서 체크", "off", refresh, palette)
            }
        }
        onNode(hasTestTag("on")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
        onNode(hasContentDescriptionEqualTo("보고서 체크 안 됨, 눌러서 체크")).assertExists()
    }

    @Test
    fun headerShowsTitleSubtitleAndItsActions() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 200.dp))
        provideComposable {
            WidgetHeader(R.drawable.ic_widget_habit, "습관", "11월 3일 화요일", palette, 48.dp, 15f, 11f) {
                WidgetIconButton(R.drawable.ic_widget_refresh, "새로고침", "refresh", refresh, palette)
            }
        }
        onNode(hasTextEqualTo("습관")).assertExists()
        onNode(hasTextEqualTo("11월 3일 화요일")).assertExists()
        onNode(hasTestTag("refresh")).assertExists()
    }

    @Test
    fun narrowWidgetDropsTheHeaderChipSoTheTitleKeepsItsRoom() {
        assertTrue(headerShowsChip(250.dp))
        assertTrue(!headerShowsChip(150.dp)) // 2×2: 칩 40dp + 버튼 48dp가 제목 자리를 거의 다 먹는다
    }

    @Test
    fun emptyStateShrinksToOneTappableLineInALowWidget() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 110.dp))
        provideComposable { WidgetEmpty(R.drawable.ic_widget_checklist, "표시할 행이 없습니다", palette, 12f, ctaText = "첫 항목 만들기", ctaAction = refresh) }
        onNode(hasTextEqualTo("표시할 행이 없습니다. 첫 항목 만들기")).assertExists()
        onNode(hasTestTag("emptyAction")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
    }

    @Test
    fun statusLineWritesTheProblemInWords() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 200.dp))
        provideComposable { WidgetStatusLine("동기화 실패, 마지막 성공 11/3 09:15", error = true, palette = palette, fontSp = 11f) }
        onNode(hasTextEqualTo("동기화 실패, 마지막 성공 11/3 09:15")).assertExists()
    }

    @Test
    fun emptyStateOffersAnAction() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(DpSize(320.dp, 200.dp))
        provideComposable { WidgetEmpty(R.drawable.ic_widget_checklist, "표시할 행이 없습니다", palette, 12f, ctaText = "첫 항목 만들기", ctaAction = refresh) }
        onNode(hasTextEqualTo("표시할 행이 없습니다")).assertExists()
        onNode(hasTestTag("emptyAction")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
        onNode(hasContentDescriptionEqualTo("첫 항목 만들기")).assertExists()
    }
}
