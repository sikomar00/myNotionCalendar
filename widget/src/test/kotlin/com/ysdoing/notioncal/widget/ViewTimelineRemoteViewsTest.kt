package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
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
import kotlin.time.Duration.Companion.minutes

/**
 * Phase 12e VR-TML 타임라인 보기의 실제 RemoteViews 변환. Glance Column 자식이 10개를 넘거나 view id가 500개를 넘으면 여기서 예외가 나고(PRD §13),
 * 크기 단계별(주: 4×2·4×4, 월: 전체) 기준 이미지를 남긴다. (오늘 = 2027-01-15, 주는 월요일에 시작해 1/11~1/17이다.)
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewTimelineRemoteViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)
    private val stockSizes = listOf(DpSize(160.dp, 160.dp), DpSize(358.dp, 152.dp), DpSize(358.dp, 420.dp), DpSize(358.dp, 640.dp))

    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "기간", "date")
    private val status = ViewColumn(
        "s", "상태", "status",
        listOf(ViewChoice("o1", "할 일", "To-do", "gray"), ViewChoice("o2", "진행 중", "In progress", "blue"), ViewChoice("o3", "완료", "Complete", "green"), ViewChoice("o4", "중요", "To-do", "red")),
    )

    private fun row(id: String, name: String, start: String, end: String? = null, option: String = "o1") = ViewRow(
        id, "https://notion.so/$id", listOf(name),
        typed = mapOf(
            "t" to JsonPrimitive(name),
            "d" to JsonObject(buildMap { put("start", JsonPrimitive(start)); if (end != null) put("end", JsonPrimitive(end)) }),
            "s" to JsonPrimitive(option),
        ),
    )

    private fun board(rows: List<ViewRow>, config: String = "") =
        ViewBoard("v1", "제품 로드맵", "timeline", listOf("이름", "기간", "상태"), rows, now, null, listOf(title, date, status), viewConfig = config)

    // 예시 일정(개인 일정 아님).
    private val sample = board(
        listOf(
            row("1", "기획 정리", "2027-01-05", "2027-01-12", "o2"), row("2", "디자인 시안", "2027-01-11", "2027-01-14", "o4"), row("3", "개발 착수", "2027-01-13", "2027-01-20", "o2"),
            row("4", "내부 테스트", "2027-01-15", "2027-01-16"), row("5", "출시 점검", "2027-01-17"), row("6", "회고", "2027-01-18T15:00:00+09:00", option = "o3"),
            row("7", "베타 공개", "2027-01-22", "2027-01-26", "o4"), row("8", "문서화", "2027-01-27", "2027-01-31", "o3"),
        ),
    )

    private val dense = board((0 until 100).map { i -> row("d$i", "일정 $i", "2027-01-%02d".format(1 + i % 28), "2027-01-%02d".format(minOf(31, 3 + i % 28)), "o${1 + i % 4}") })

    private fun state(board: ViewBoard, config: WidgetConfig = WidgetConfig(1, viewId = "v1")) =
        ViewWidgetState(board, config, now, CalendarInfo(DayOfWeek.MONDAY))

    private suspend fun render(size: DpSize, state: ViewWidgetState) = renderRemoteViews(context, size) { ViewWidgetContent(state) }

    @Test
    fun translatesInEverySizeAndSettingWithinGlancesLimits() = runTest(timeout = 3.minutes) {
        val configs = listOf(
            WidgetConfig(1, viewId = "v1"), WidgetConfig(1, fontScale = 70, viewId = "v1"), WidgetConfig(1, fontScale = 150, viewId = "v1"),
            WidgetConfig(1, theme = WidgetTheme.DARK, opacity = 0, viewId = "v1"), WidgetConfig(1, monthOffset = 1, viewId = "v1"),
        )
        val failures = mutableListOf<String>()
        for (size in listOf(compact, wide, large, full, DpSize(180.dp, 400.dp), DpSize(400.dp, 180.dp)) + stockSizes) {
            for (config in configs) {
                for ((name, board) in listOf("dense" to dense, "month" to board(dense.rows, """{"preference":{"zoom_level":"month"}}"""), "sample" to sample)) {
                    // 한 번 실패해도 나머지 조합을 계속 돌려 어느 크기·설정이 한도를 넘는지 한꺼번에 알린다.
                    runCatching {
                        val ids = glanceIdsUsed(render(size, state(board, config)))
                        check(ids <= GLANCE_VIEW_ID_BUDGET) { "uses $ids view ids" }
                    }.onFailure { failures += "$name ${size.width}x${size.height} font=${config.fontScale} theme=${config.theme} offset=${config.monthOffset}: ${it.message}" }
                }
            }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    @Test
    fun screenshotWeek4x2() = runTest { render(wide, state(sample)).captureRoboImage("src/test/screenshots/viewtimeline_week_4x2.png") }

    @Test
    fun screenshotWeek4x4() = runTest { render(large, state(sample)).captureRoboImage("src/test/screenshots/viewtimeline_week_4x4.png") }

    @Test
    fun screenshotMonthFull() = runTest { render(full, state(sample)).captureRoboImage("src/test/screenshots/viewtimeline_month_full.png") }

    @Test
    fun screenshotWeek4x4DarkFont150() = runTest {
        render(large, state(sample, WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 100, viewId = "v1")))
            .captureRoboImage("src/test/screenshots/viewtimeline_week_4x4_dark_font150.png")
    }
}
