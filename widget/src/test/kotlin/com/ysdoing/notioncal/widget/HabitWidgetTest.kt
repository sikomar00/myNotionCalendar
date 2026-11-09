package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.db.HabitEntity
import com.ysdoing.notioncal.core.data.habit.HabitBoard
import com.ysdoing.notioncal.core.data.habit.HabitStats
import com.ysdoing.notioncal.core.data.habit.HabitSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

/** HB-03 습관 위젯: 2×2 오늘 체크만 / 4×2 7일 / 4×4 7일 + 이번 달 잔디, 칸을 누르면 그날 토글, 넘치는 줄은 목록 스크롤. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class HabitWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    companion object {
        val today: LocalDate = LocalDate.of(2026, 10, 21) // 수요일

        private val names = listOf("영양제먹기" to "💊", "명상" to "🧘", "하루정리" to "📝", "내일계획" to "🗓️", "점심먹고산책" to "🚶", "독서" to "📚", "스트레칭" to "🤸", "물 2리터" to "💧")

        fun board(count: Int = 5): HabitBoard {
            val summaries = names.take(count).mapIndexed { i, (name, emoji) ->
                // i번째 습관은 i+1일 연속, 주 3회 습관(4번)은 오늘만.
                val done = if (i == 4) setOf(today) else (0 until i + 1).map { today.minusDays(it.toLong()) }.toSet()
                HabitSummary(
                    HabitEntity("h$i", "habits", name, emoji, if (i == 4) 3 else 7, true, LocalDate.of(2026, 10, 1), i.toDouble()),
                    HabitStats.status(done, today, DayOfWeek.SUNDAY, if (i == 4) 3 else 7, LocalDate.of(2026, 10, 1)),
                )
            }
            return HabitBoard(today, summaries, summaries.associate { s -> s.habit.pageId to s.status.last7.filter { it.done }.map { it.date }.toSet() })
        }

        fun state(count: Int = 5, config: WidgetConfig = WidgetConfig(1)) = HabitWidgetState(board(count), config, DayOfWeek.SUNDAY)

        val SMALL = DpSize(170.dp, 170.dp)
        val MEDIUM = DpSize(358.dp, 152.dp)
        val LARGE = DpSize(358.dp, 380.dp)
    }

    @Test
    fun sizeModes() {
        assertEquals(HabitWidgetMode.SMALL, habitWidgetMode(SMALL))
        assertEquals(HabitWidgetMode.MEDIUM, habitWidgetMode(MEDIUM))
        assertEquals(HabitWidgetMode.LARGE, habitWidgetMode(LARGE))
    }

    @Test
    fun smallShowsOnlyTodayChecks() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(SMALL)
        provideComposable { HabitWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("habitRow")).assertCountEquals(5)
        onAllNodes(hasTestTag("habitToday")).assertCountEquals(5)
        onAllNodes(hasTestTag("habitCell")).assertCountEquals(0)
        onNode(hasTextEqualTo("오늘 5/5 완료")).assertExists() // 머리 부제(2×2는 히어로가 없다)
        onAllNodes(hasTestTag("habitHero")).assertCountEquals(0)
        onAllNodes(hasTestTag("habitRefresh")).assertCountEquals(0)
        // 오늘 칸을 누르면 그 습관의 오늘을 토글한다.
        onNode(hasTestTag("habitToday").and(hasContentDescriptionEqualTo("명상, 오늘, 함")))
            .assert(hasRunCallbackClickAction<HabitToggleAction>(actionParametersOf(HabitToggleAction.HabitKey to "h1", HabitToggleAction.DayKey to today.toEpochDay())))
    }

    @Test
    fun mediumShowsSevenDaysAndWeekCount() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(MEDIUM)
        provideComposable { HabitWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("habitRow")).assertCountEquals(5)
        onAllNodes(hasTestTag("habitCell")).assertCountEquals(5 * 6)
        onAllNodes(hasTestTag("habitToday")).assertCountEquals(5)
        onAllNodes(hasTestTag("habitCell")).assertAll(hasClickAction())
        onNode(hasTextEqualTo("1/3")).assertExists() // 주 3회 습관: 이번 주(일~) 오늘 한 번
        onNode(hasTextEqualTo("4/7")).assertExists() // 일·월·화·수 연속
        onAllNodes(hasTestTag("habitGrass")).assertCountEquals(0)
        // 지난 날 칸도 그날을 토글한다(HB-02 오늘과 지난 6일).
        val monday = today.minusDays(2)
        onNode(hasContentDescriptionEqualTo("명상, 10월 19일 월, 안 함"))
            .assert(hasRunCallbackClickAction<HabitToggleAction>(actionParametersOf(HabitToggleAction.HabitKey to "h1", HabitToggleAction.DayKey to monday.toEpochDay())))
    }

    @Test
    fun largeAddsMonthGrass() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(LARGE)
        provideComposable { HabitWidgetContent(state(), systemFontScale = 1f) }
        onAllNodes(hasTestTag("habitGrass")).assertCountEquals(1)
        onAllNodes(hasTestTag("habitGrassDay")).assertCountEquals(31)
        onAllNodes(hasTestTag("habitRow")).assertCountEquals(5)
    }

    /** HB-03 완료 기준: 습관 8개·글꼴 최대에서도 줄 높이가 글자를 품는다(넘치는 줄은 목록이 스크롤). */
    @Test
    fun eightHabitsAtMaxFontKeepRowsUnclipped() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(MEDIUM)
        val config = WidgetConfig(1, fontScale = 150)
        provideComposable { HabitWidgetContent(state(8, config), systemFontScale = 2f) }
        onAllNodes(hasTestTag("habitRow")).assertCountEquals(8)
        for (size in listOf(SMALL, MEDIUM, LARGE)) {
            val m = HabitMetrics.of(size, config, systemFontScale = 2f)
            assertTrue(m.toString(), m.rowHeight.value >= m.nameFontSp * (if (m.showMessage) 2f else 1f) * WidgetMetrics.TEXT_BOX)
            assertTrue(m.toString(), m.headerHeight.value >= m.headerFontSp * 2f * WidgetMetrics.TEXT_BOX)
        }
    }

    /** WG-07: 동기화가 실패하면 습관 위젯도 머리 부제로 알린다(전에는 아무 표시가 없었다). */
    @Test
    fun aFailedSyncIsShownInTheHeader() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(MEDIUM)
        provideComposable { HabitWidgetContent(state().copy(sync = WidgetSync(failed = true)), systemFontScale = 1f) }
        // 오늘 진행도는 남기고 오류를 덧붙인다.
        onNode(hasTextEqualTo(context.getString(R.string.habit_widget_subtitle, board().doneToday, 5) + " · " + context.getString(R.string.widget_sync_error_short))).assertExists()
    }

    @Test
    fun emptyBoardExplainsRegistration() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(MEDIUM)
        provideComposable { HabitWidgetContent(HabitWidgetState(HabitBoard(today, emptyList()), WidgetConfig(1), DayOfWeek.SUNDAY), systemFontScale = 1f) }
        onNode(hasTestTag("habitEmpty")).assert(hasClickAction())
    }

    @Test
    fun largeShowsHeroAndStreakPills() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(LARGE)
        provideComposable { HabitWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("habitHero")).assert(hasContentDescriptionEqualTo("오늘 습관 5개 중 5개 완료"))
        // 매일 습관 중 2일 이상 이어 가는 것만 알약을 단다(1일째는 연속이 아니다, 주 목표 습관은 n/목표로 보인다).
        listOf("연속 2일", "연속 3일", "연속 4일").forEach { onNode(hasTextEqualTo(it)).assertExists() }
        onNode(hasTextEqualTo("연속 1일")).assertDoesNotExist()
        onNode(hasTestTag("habitRefresh")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
    }

    @Test
    fun streakPillIsLeftOutWhereTheNameColumnCannotHoldIt() {
        // 알약(약 76dp)이 이름 칸에 안 들어가는 좁은 위젯은 알약을 그리지 않아 중간에서 잘리지 않는다.
        fun shows(size: DpSize) = HabitMetrics.of(size, WidgetConfig(1), 1f).showMessage
        assertTrue(shows(LARGE))
        assertTrue(!shows(MEDIUM)) // 밀도 우선: 4×2·2×2는 알약 줄을 두지 않는다
        assertTrue(!shows(SMALL))
        assertTrue(!shows(DpSize(250.dp, 380.dp))) // 4×4여도 이름 칸이 좁으면 뺀다
    }

    @Test
    fun mediumHasRefreshButNoHero() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(MEDIUM)
        provideComposable { HabitWidgetContent(state(), systemFontScale = 1f) }
        onNode(hasTestTag("habitRefresh")).assertExists()
        onAllNodes(hasTestTag("habitHero")).assertCountEquals(0)
    }

    /** 밀도 우선: 4×2·2×2 습관 줄은 36dp 이하(한 줄 + 원형 칸), 알약 두 줄이 있는 4×4도 46dp 이하다. 눌림 폭은 2×2에서 48dp. */
    @Test
    fun rowsStayCompactForDensity() {
        assertTrue(HabitMetrics.of(SMALL, WidgetConfig(1), 1f).rowHeight.value <= 36f)
        assertTrue(HabitMetrics.of(MEDIUM, WidgetConfig(1), 1f).rowHeight.value <= 36f)
        assertTrue(HabitMetrics.of(LARGE, WidgetConfig(1), 1f).rowHeight.value <= 46f)
        assertTrue(HabitMetrics.of(SMALL, WidgetConfig(1), 1f).cellWidth.value >= 48f)
    }

    @Test
    fun emptyBoardOffersToOpenTheHabitTab() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(MEDIUM)
        provideComposable { HabitWidgetContent(HabitWidgetState(HabitBoard(today, emptyList()), WidgetConfig(1), DayOfWeek.SUNDAY), systemFontScale = 1f) }
        onNode(hasContentDescriptionEqualTo("습관 보기")).assertExists()
    }
}
