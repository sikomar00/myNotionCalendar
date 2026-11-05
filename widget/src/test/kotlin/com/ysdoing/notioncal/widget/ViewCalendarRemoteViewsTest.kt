package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * Phase 12a VR-CAL 캘린더 보기의 실제 RemoteViews 변환. Glance Row·Column 자식이 10개를 넘으면 여기서 예외가 나고(PRD §13),
 * Binder 한도(약 1MB)에 여유가 있는지 크기를 재며, 크기 단계별(2×2·4×2·4×4·전체) 기준 이미지를 남긴다. (오늘 = 2027-01-15.)
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewCalendarRemoteViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L

    // 삼성 런처가 보고하는 크기(S25 실측 2×2 234×213, 4×2 501×213, 4×4 501×458, 4×5 501×581). 일반 런처 크기는 아래 변환 시험에서 함께 돈다.
    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)
    private val stockSizes = listOf(DpSize(160.dp, 160.dp), DpSize(358.dp, 152.dp), DpSize(358.dp, 420.dp), DpSize(358.dp, 640.dp))

    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "날짜", "date")
    private val status = ViewColumn(
        "s", "상태", "status",
        listOf(
            ViewChoice("o1", "할 일", "To-do", "gray"), ViewChoice("o2", "진행 중", "In progress", "blue"), ViewChoice("o3", "완료", "Complete", "green"),
            ViewChoice("o4", "중요", "To-do", "red"), ViewChoice("o5", "개인", "To-do", "purple"),
        ),
    )

    private fun row(id: String, name: String, start: String, end: String? = null, option: String = "o1") = ViewRow(
        id, "https://notion.so/$id", listOf(name),
        typed = mapOf(
            "t" to JsonPrimitive(name),
            "d" to JsonObject(buildMap { put("start", JsonPrimitive(start)); if (end != null) put("end", JsonPrimitive(end)) }),
            "s" to JsonPrimitive(option),
        ),
    )

    private fun board(rows: List<ViewRow>) = ViewBoard("v1", "일정", "calendar", listOf("이름", "날짜", "상태"), rows, now, null, listOf(title, date, status))

    // 예시 일정(개인 일정 아님).
    private val sample = board(
        listOf(
            row("1", "새해 계획 점검", "2027-01-04"), row("2", "치과", "2027-01-06T10:00:00+09:00", option = "o4"), row("3", "독서모임", "2027-01-08T19:30:00+09:00", option = "o5"),
            row("4", "팀 회의", "2027-01-11T14:00:00+09:00", option = "o2"), row("5", "병원", "2027-01-12", option = "o4"), row("6", "점심 약속", "2027-01-14T12:00:00+09:00", option = "o5"),
            row("7", "장보기", "2027-01-15"), row("8", "발표 준비", "2027-01-15", option = "o2"), row("9", "운동", "2027-01-15", option = "o3"),
            row("10", "치과 예약", "2027-01-15T10:00:00+09:00", option = "o4"), row("11", "출장", "2027-01-18", "2027-01-20", "o2"),
            row("12", "부모님 댁", "2027-01-22", option = "o5"), row("13", "프로젝트 마감", "2027-01-25", "2027-01-27", "o4"), row("14", "회식", "2027-01-28T18:00:00+09:00", option = "o5"),
        ),
    )

    // 매일 여러 개 + 여러 날 일정: 칸마다 넘치는 경우.
    private val dense = board(
        (0 until 100).map { i ->
            val day = 1 + i % 28
            row("d$i", "일정 $i", "2027-01-%02d".format(day), if (i % 7 == 0) "2027-01-%02d".format(minOf(28, day + 2)) else null, "o${1 + i % 5}")
        },
    )

    private fun state(board: ViewBoard, config: WidgetConfig = WidgetConfig(1, viewId = "v1"), info: CalendarInfo = CalendarInfo()) =
        ViewWidgetState(board, config, now, info)

    private suspend fun render(size: DpSize, state: ViewWidgetState) = renderRemoteViews(context, size) { ViewWidgetContent(state) }

    @Test
    fun translatesInEverySizeAndSetting() = runTest(timeout = 3.minutes) {
        val configs = listOf(
            WidgetConfig(1, viewId = "v1"),
            WidgetConfig(1, fontScale = 70, viewId = "v1"),
            WidgetConfig(1, fontScale = 150, maxPerCell = 6, viewId = "v1"),
            WidgetConfig(1, theme = WidgetTheme.DARK, opacity = 0, viewId = "v1"),
            WidgetConfig(1, monthOffset = 1, viewId = "v1"),
        )
        val info = CalendarInfo(DayOfWeek.MONDAY, showWeekNumbers = true, holidays = mapOf(LocalDate.of(2027, 1, 1) to "신정"))
        val failures = mutableListOf<String>()
        for (size in listOf(compact, wide, large, full, DpSize(180.dp, 400.dp), DpSize(400.dp, 180.dp)) + stockSizes) {
            for (config in configs) {
                for ((name, state) in listOf("dense" to state(dense, config), "sample+weekNumbers" to state(sample, config, info))) {
                    // 한 번 실패해도 나머지 조합을 계속 돌려 어느 크기·설정이 한도를 넘는지 한꺼번에 알린다.
                    runCatching { render(size, state) }.onFailure { failures += "$name ${size.width}x${size.height} font=${config.fontScale} theme=${config.theme} offset=${config.monthOffset}: ${it.message}" }
                }
            }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    @Test
    fun denseCalendarStaysFarBelowTheBinderLimit() = runTest(timeout = 3.minutes) {
        // 칸마다 타일을 깔지 않고(주당 하나) 일정 줄을 합쳐 RemoteViews를 작게 둔다. Binder(약 1MB, 프로세스 공용)에 가장 큰 경우도 여유를 둔다.
        for (size in listOf(large, full)) {
            val remoteViews = GlanceRemoteViews().compose(context, size) { ViewWidgetContent(state(dense, WidgetConfig(1, maxPerCell = 6, viewId = "v1"))) }.remoteViews
            val parcel = android.os.Parcel.obtain()
            remoteViews.writeToParcel(parcel, 0)
            val bytes = parcel.dataSize()
            parcel.recycle()
            val ids = glanceIdsUsed(render(size, state(dense, WidgetConfig(1, maxPerCell = 6, viewId = "v1"))))
            println("VIEWCAL_PARCEL ${size.width}x${size.height} = $bytes bytes, $ids view ids")
            assertTrue("$size uses $ids of 500 Glance view ids", ids <= GLANCE_VIEW_ID_BUDGET)
            assertTrue("$size RemoteViews $bytes bytes", bytes < 400_000)
        }
    }

    // 날마다 서로 다른 일정 6개가 42칸 모두에: 칸당 줄 수가 많은 설정에서도 줄을 줄여 Glance 뷰 한도(500)에 들어와야 한다.
    private val saturated = board((0 until 42).flatMap { d -> (0 until 6).map { n -> row("s$d-$n", "일정 $d-$n", LocalDate.of(2026, 12, 27).plusDays(d.toLong()).toString(), option = "o${1 + n % 5}") } })

    @Test
    fun aSaturatedCalendarStillFitsGlancesViewLimitInEverySetting() = runTest(timeout = 3.minutes) {
        val info = CalendarInfo(DayOfWeek.SUNDAY, showWeekNumbers = true)
        val failures = mutableListOf<String>()
        for (size in listOf(large, full, DpSize(358.dp, 640.dp), DpSize(411.dp, 800.dp))) {
            for (font in listOf(70, 100, 150)) {
                val ids = glanceIdsUsed(render(size, state(saturated, WidgetConfig(1, fontScale = font, viewId = "v1"), info)))
                if (ids > GLANCE_VIEW_ID_BUDGET) failures += "${size.width}x${size.height} font=$font uses $ids view ids"
            }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    @Test
    fun screenshot2x2() = runTest { render(compact, state(sample)).captureRoboImage("src/test/screenshots/viewcalendar_2x2.png") }

    @Test
    fun screenshot4x2() = runTest { render(wide, state(sample)).captureRoboImage("src/test/screenshots/viewcalendar_4x2.png") }

    @Test
    fun screenshot4x4() = runTest { render(large, state(sample)).captureRoboImage("src/test/screenshots/viewcalendar_4x4.png") }

    @Test
    fun screenshotFull() = runTest { render(full, state(sample)).captureRoboImage("src/test/screenshots/viewcalendar_full.png") }

    @Test
    fun screenshot4x4DarkFont150() = runTest {
        render(large, state(sample, WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 100, viewId = "v1")))
            .captureRoboImage("src/test/screenshots/viewcalendar_4x4_dark_font150.png")
    }
}
