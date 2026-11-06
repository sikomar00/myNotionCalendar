package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
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
 * Phase 12e WL-04 합쳐 보기의 실제 RemoteViews 변환. Glance 자식이 10개, view id가 500개를 넘으면 여기서 예외가 나고(PRD §13),
 * 세 소스가 모두 일정이 가득한 달에서도 예산 안에 들어와야 한다. 크기별 기준 이미지도 남긴다. (오늘 = 2027-01-15.)
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class LayoutMergedRemoteViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)
    private val stockSizes = listOf(DpSize(160.dp, 160.dp), DpSize(358.dp, 152.dp), DpSize(358.dp, 420.dp), DpSize(358.dp, 640.dp))

    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "날짜", "date")
    private val status = ViewColumn(
        "s", "상태", "status",
        listOf(ViewChoice("o1", "할 일", "To-do", "gray"), ViewChoice("o2", "진행 중", "In progress", "blue"), ViewChoice("o3", "완료", "Complete", "green")),
    )

    private fun row(id: String, name: String, start: String, end: String? = null, option: String = "o1") = ViewRow(
        id, "", listOf(name),
        typed = mapOf(
            "t" to JsonPrimitive(name), "s" to JsonPrimitive(option),
            "d" to JsonObject(buildMap { put("start", JsonPrimitive(start)); if (end != null) put("end", JsonPrimitive(end)) }),
        ),
    )

    private fun board(id: String, name: String, rows: List<ViewRow>) = ViewBoard(id, name, "table", listOf("이름", "날짜"), rows, now, null, listOf(title, date, status))

    // 예시 일정(개인 일정 아님): 업무·운동·모임 세 보기.
    private val work = board("v1", "업무", listOf(row("w1", "기획 회의", "2027-01-05"), row("w2", "배포", "2027-01-14", option = "o2"), row("w3", "스프린트", "2027-01-18", "2027-01-22", "o2"), row("w4", "회고", "2027-01-15", option = "o3")))
    private val fit = board("v2", "운동", listOf(row("f1", "러닝", "2027-01-06T07:00:00+09:00"), row("f2", "수영", "2027-01-13T19:00:00+09:00"), row("f3", "요가", "2027-01-15T08:00:00+09:00"), row("f4", "등산", "2027-01-23", "2027-01-24")))
    private val social = board("v3", "모임", listOf(row("s1", "독서모임", "2027-01-08T19:30:00+09:00"), row("s2", "친구 약속", "2027-01-15"), row("s3", "가족 식사", "2027-01-17"), row("s4", "송년회", "2027-01-29")))
    private val layout = WidgetLayout(LayoutMode.MERGED).add("v1")!!.add("v2")!!.add("v3")!!
    private val sample = mapOf("v1" to work, "v2" to fit, "v3" to social)

    // 날마다 서로 다른 일정이 소스마다 여러 개: 칸당 줄 수가 많은 설정에서도 줄여 한도에 들어와야 한다.
    private fun dense(id: String, name: String) = board(id, name, (0 until 100).map { i -> row("$id-$i", "$name $i", "2027-01-%02d".format(1 + i % 28), if (i % 6 == 0) "2027-01-%02d".format(minOf(28, 3 + i % 28)) else null, "o${1 + i % 3}") })
    private val crowded = mapOf("v1" to dense("v1", "업무"), "v2" to dense("v2", "운동"), "v3" to dense("v3", "모임"))

    private fun state(boards: Map<String, ViewBoard?>, config: WidgetConfig = WidgetConfig(1)) =
        LayoutWidgetState(config, layout, boards, now, DayOfWeek.MONDAY, CalendarInfo(DayOfWeek.MONDAY, showWeekNumbers = true))

    private suspend fun render(size: DpSize, state: LayoutWidgetState) = renderRemoteViews(context, size) { LayoutWidgetContent(state) }

    @Test
    fun translatesInEverySizeAndSettingWithinGlancesLimits() = runTest(timeout = 3.minutes) {
        val configs = listOf(
            WidgetConfig(1), WidgetConfig(1, fontScale = 70), WidgetConfig(1, fontScale = 150, maxPerCell = 6),
            WidgetConfig(1, theme = WidgetTheme.DARK, opacity = 0), WidgetConfig(1, monthOffset = 1),
        )
        val failures = mutableListOf<String>()
        for (size in listOf(compact, wide, large, full, DpSize(180.dp, 400.dp), DpSize(400.dp, 180.dp)) + stockSizes) {
            for (config in configs) {
                for ((name, boards) in listOf("crowded" to crowded, "sample" to sample)) {
                    // 한 번 실패해도 나머지 조합을 계속 돌려 어느 크기·설정이 한도를 넘는지 한꺼번에 알린다.
                    runCatching {
                        val ids = glanceIdsUsed(render(size, state(boards, config)))
                        check(ids <= GLANCE_VIEW_ID_BUDGET) { "uses $ids view ids" }
                    }.onFailure { failures += "$name ${size.width}x${size.height} font=${config.fontScale} theme=${config.theme} offset=${config.monthOffset}: ${it.message}" }
                }
            }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    @Test
    fun screenshot4x4() = runTest { render(large, state(sample)).captureRoboImage("src/test/screenshots/layout_merged_4x4.png") }

    @Test
    fun screenshot4x2() = runTest { render(wide, state(sample)).captureRoboImage("src/test/screenshots/layout_merged_4x2.png") }

    @Test
    fun screenshotFullDarkFont150() = runTest {
        render(full, state(sample, WidgetConfig(1, fontScale = 150, theme = WidgetTheme.DARK, opacity = 100)))
            .captureRoboImage("src/test/screenshots/layout_merged_full_dark_font150.png")
    }
}
