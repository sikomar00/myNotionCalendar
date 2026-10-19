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
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTab
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.ledger.BudgetRow
import com.ysdoing.notioncal.core.data.ledger.Flow
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.data.ledger.LedgerMath
import com.ysdoing.notioncal.core.data.ledger.LedgerRules
import com.ysdoing.notioncal.core.data.ledger.LedgerTx
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * LD-03 가계부 달력 위젯: 4×4 6주 그리드 / 4×2 이번 주 한 줄, 위쪽 요약과 주 예산 줄(LD-04), 금액 숨김(LD-06),
 * WG-11 통합 위젯 💰 탭. RemoteViews 변환으로 Glance 자식 10개 제한을 확인한다.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class LedgerWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    companion object {
        val today: LocalDate = LocalDate.of(2026, 10, 21)
        val LARGE = DpSize(358.dp, 380.dp)
        val MEDIUM = DpSize(358.dp, 168.dp)

        private fun tx(id: String, day: Int, amount: Long, flow: Flow = Flow.OUT, planned: Boolean = false) =
            LedgerTx(id, LocalDate.of(2026, 10, day), "12:00", amount, flow, "식비", id, "카드", planned, manual = false, createdAt = 0)

        fun board(): LedgerBoard {
            val rows = listOf(
                tx("a", 19, -12_300), tx("b", 20, -9_800), tx("pay", 17, 2_500_000, Flow.IN), tx("plan", 24, -50_000, planned = true),
            )
            val budgets = listOf(BudgetRow("주", "2026-W43", "전체", 100_000), BudgetRow("월", "2026-10", "전체", 1_000_000))
            val s = LedgerMath.summarize(rows, mapOf("식비" to "생활비"), emptySet(), today, DayOfWeek.SUNDAY, YearMonth.of(2026, 10), budgets, LedgerRules())
            return LedgerBoard(YearMonth.of(2026, 10), today, DayOfWeek.SUNDAY, s, registered = true)
        }

        fun state(config: WidgetConfig = WidgetConfig(1)) = LedgerWidgetState(board(), config)
    }

    @Test
    fun largeShowsSummaryWeekLineAndSixWeeks() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(LARGE)
        provideComposable { LedgerWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTextEqualTo("10월 지출 2.2만")).assertExists()
        onNode(hasTextEqualTo("10/20까지 반영")).assertExists()
        onNode(hasTextEqualTo("예정 5만 · 수입 +250만 · 예산 92.8만 남음")).assertExists()
        onNode(hasTextEqualTo("이번 주 2.8만 남음 · 4일")).assertExists()
        onAllNodes(hasTestTag("ledgerDay")).assertCountEquals(42)
        onNode(hasContentDescriptionEqualTo("10월 19일, 지출 12,300원")).assertExists()
        onNode(hasContentDescriptionEqualTo("10월 24일, 예정 50,000원")).assertExists()
        onNode(hasTestTag("ledgerTitle")).assert(hasClickAction())
        // 지출은 '-'(파랑), 예정도 '-', 수입은 '+'.
        onNode(hasTextEqualTo("-1.2만")).assertExists()
        onNode(hasTextEqualTo("-5만")).assertExists()
        onNode(hasTextEqualTo("+250만")).assertExists()
    }

    /** 6주가 안 들어가면 들어가는 만큼 주를 보이되 이번 주를 맨 아래에 둔다(최근 쓴 돈부터). */
    @Test
    fun mediumShowsRecentWeeksEndingThisWeek() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(MEDIUM)
        val rows = LedgerMetrics.of(MEDIUM, WidgetConfig(1), 1f).rows
        assertTrue(rows in 1..5)
        provideComposable { LedgerWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("ledgerDay")).assertCountEquals(rows * 7)
        onNode(hasContentDescriptionEqualTo("10월 24일, 예정 50,000원")).assertExists() // 이번 주 토요일이 마지막 칸
        onNode(hasTextEqualTo("이번 주 2.8만 남음 · 4일")).assertExists()
    }

    /** LD-07: 머리줄 '+'는 오늘, 날짜 칸은 그 날짜로 입력 시트를 연다(위젯은 네트워크 없이 인텐트만). */
    @Test
    fun addButtonAndDayCellsOpenInputSheet() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(LARGE)
        provideComposable { LedgerWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("ledgerAdd")).assert(hasStartActivityClickAction(ledgerInputIntent(context, today)))
        onNode(hasContentDescriptionEqualTo("10월 24일, 예정 50,000원"))
            .assert(hasStartActivityClickAction(ledgerInputIntent(context, LocalDate.of(2026, 10, 24))))
        assertEquals(AppIntents.ACTION_LEDGER_INPUT, ledgerInputIntent(context, today).action)
        assertEquals(today.toString(), ledgerInputIntent(context, today).getStringExtra(AppIntents.EXTRA_DATE))
    }

    @Test
    fun weekRowsFillAvailableHeight() {
        val tall = LedgerMetrics.of(DpSize(358.dp, 240.dp), WidgetConfig(1), 1f)
        val short = LedgerMetrics.of(DpSize(358.dp, 130.dp), WidgetConfig(1), 1f)
        assertTrue("$tall", tall.rows > short.rows)
        assertEquals(1, short.rows)
    }

    /** LD-06: 금액 숨김이면 숫자 대신 ●●●, 주 예산 막대는 남는다. 낭독에도 금액을 읽지 않는다. */
    @Test
    fun hiddenAmountsShowDotsButKeepBar() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(LARGE)
        provideComposable { LedgerWidgetContent(state(WidgetConfig(1, hideAmounts = true)), systemFontScale = 1f) }
        onNode(hasTextEqualTo("10월 지출 ●●●")).assertExists()
        onNode(hasTextEqualTo("이번 주 ●●● 남음 · 4일")).assertExists()
        onAllNodes(hasText("만")).assertCountEquals(0)
        onAllNodes(hasText("2,")).assertCountEquals(0)
        onNode(hasTestTag("ledgerBar")).assertExists()
        onNode(hasContentDescriptionEqualTo("10월 19일")).assertExists()
    }

    @Test
    fun tabbedWidgetHasLedgerTab() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(LARGE)
        val config = WidgetConfig(1, selectedTab = WidgetTab.LEDGER)
        provideComposable {
            TabbedWidgetContent(config, systemFontScale = 1f) { LedgerWidgetContent(LedgerWidgetState(board(), config), systemFontScale = 1f, framed = false) }
        }
        onNode(hasTestTag("tab_LEDGER")).assertExists()
        onAllNodes(hasTestTag("ledgerDay")).assertCountEquals(42) // 탭 줄을 빼고도 큰 그리드
    }

    @Test
    fun metricsKeepTextInsideRowsAtMaxFont() {
        for (size in listOf(LARGE, MEDIUM)) {
            val m = LedgerMetrics.of(size, WidgetConfig(1, fontScale = 150), systemFontScale = 2f)
            assertTrue(m.toString(), m.cellHeight.value >= (m.dateFontSp + m.amountFontSp) * 2f * WidgetMetrics.TEXT_BOX - 0.5f)
        }
        assertEquals(LedgerWidgetMode.LARGE, ledgerWidgetMode(LARGE))
        assertEquals(LedgerWidgetMode.COMPACT, ledgerWidgetMode(MEDIUM))
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
        val configs = listOf(WidgetConfig(1), WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 60, hideAmounts = true))
        for (config in configs) {
            for (size in listOf(LARGE, MEDIUM)) {
                render(size) { LedgerWidgetContent(LedgerWidgetState(board(), config), systemFontScale = 1.3f) }
                val tabbed = config.copy(selectedTab = WidgetTab.LEDGER)
                render(size) {
                    TabbedWidgetContent(tabbed, systemFontScale = 1.3f) { LedgerWidgetContent(LedgerWidgetState(board(), tabbed), systemFontScale = 1.3f, framed = false) }
                }
            }
        }
    }
}
