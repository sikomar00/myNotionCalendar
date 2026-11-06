package com.ysdoing.notioncal.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.LayoutItem
import com.ysdoing.notioncal.core.data.view.LayoutMode
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes

/**
 * Phase 12d VR-GAL 갤러리의 실제 RemoteViews 변환: Glance 자식 10개·위젯당 view id 500개 한도, 표지 9장이 든 위젯의 Binder 크기와 표지 비트맵 합계 예산을 재고,
 * 크기별 기준 이미지를 남긴다(합성 표지: 개인 데이터·실제 책 표지가 아니다). 삼성 보고 크기: 2×2 234×213, 4×2 501×213, 4×4 501×458, 4×5 501×581.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewGalleryRemoteViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)

    private val columns = listOf(
        ViewColumn("t", "이름", "title"), ViewColumn("a", "저자", "rich_text"), ViewColumn("r", "별점", "number"),
        ViewColumn("s", "분류", "select", listOf(ViewChoice("o1", "소설"))),
    )
    private val titles = listOf("달의 정원", "긴 여름의 끝", "바다 위의 집", "작은 도서관", "겨울 이야기", "별을 세는 밤", "오래된 지도", "느린 아침", "구름 사이로", "마지막 기차")

    private fun row(i: Int, cover: Boolean = true) = ViewRow(
        "p$i", "https://notion.so/p$i", listOf(titles[(i - 1) % titles.size], "저자 $i", "20${10 + i}"), if (cover) "https://img.example/$i.jpg" else "",
        typed = mapOf("r" to JsonPrimitive(((i + 2) % 5 + 1).toDouble())),
    )

    private fun board(rows: List<ViewRow>) = ViewBoard("g1", "책장", "gallery", listOf("이름", "저자", "연도"), rows, now, null, columns)

    /** 표지 대신 쓰는 합성 그림: 카드마다 다른 색의 세로 그라데이션 느낌 줄무늬(240×360을 줄인 크기). */
    private fun cover(i: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val hue = (i * 47f) % 360f
        for (y in 0 until 180 step 6) {
            paint.color = Color.HSVToColor(floatArrayOf(hue, 0.35f + 0.4f * y / 180f, 0.95f - 0.35f * y / 180f))
            canvas.drawRect(0f, y.toFloat(), 120f, y + 6f, paint)
        }
        return bitmap
    }

    private fun covers(rows: List<ViewRow>) = Covers.of(rows.filter { it.coverUrl.isNotEmpty() }.associate { it.pageId to cover(it.pageId.removePrefix("p").toInt()) })

    private fun state(rows: List<ViewRow>, font: Int = 100, rating: String = "r", covers: Covers = covers(rows)) =
        ViewWidgetState(board(rows), WidgetConfig(1, fontScale = font, viewId = "g1"), now, covers = covers, ratingProp = rating)

    private suspend fun render(size: DpSize, state: ViewWidgetState, staticRows: Boolean = false) =
        renderRemoteViews(context, size) { ViewWidgetContent(state, staticRows = staticRows) }

    @Test
    fun translatesInEverySizeAndFontScaleWithinTheViewIdBudget() = runTest(timeout = 5.minutes) {
        val rows = (1..30).map { row(it) }
        val failures = mutableListOf<String>()
        for (size in listOf(compact, wide, large, full, DpSize(160.dp, 160.dp), DpSize(411.dp, 800.dp))) for (font in listOf(70, 100, 150)) {
            runCatching { glanceIdsUsed(render(size, state(rows, font))) }
                .onSuccess {
                    println("GALLERY ${size.width}x${size.height} font=$font: $it view ids")
                    if (it > GLANCE_VIEW_ID_BUDGET) failures += "${size.width}x${size.height} font=$font uses $it view ids"
                }
                .onFailure { failures += "${size.width}x${size.height} font=$font: ${it.message}" }
        }
        assertTrue(failures.joinToString(System.lineSeparator()), failures.isEmpty())
    }

    @Test
    fun nineCoversStayInsideTheBinderAndBitmapBudgets() = runTest(timeout = 5.minutes) {
        val rows = (1..30).map { row(it) }
        val parcel = android.os.Parcel.obtain()
        GlanceRemoteViews().compose(context, DpSize(411.dp, 800.dp)) { ViewWidgetContent(state(rows)) }.remoteViews.writeToParcel(parcel, 0)
        val bytes = parcel.dataSize()
        parcel.recycle()
        println("GALLERY_PARCEL $bytes bytes")
        // 카드 크기 비트맵 9장(RGB_565) + 글자·구조. 표지 합계 상한(700KB)과 위젯 구조 여유 안이다.
        assertTrue("RemoteViews $bytes bytes", bytes < 400_000L + CoverBudget.GALLERY_TOTAL_BYTES * 2)
    }

    @Test
    fun aLayoutWidgetWithTwoGalleriesAndAChartStillTranslates() = runTest(timeout = 5.minutes) {
        val rowsA = (1..9).map { row(it) }
        val boardB = board((1..9).map { row(it + 20) }).copy(viewId = "g2")
        val layout = WidgetLayout(LayoutMode.SECTIONS, listOf(LayoutItem("g1"), LayoutItem("g2", ratingProp = "r")))
        val boards = mapOf("g1" to board(rowsA), "g2" to boardB)
        val covers = covers(rowsA.take(4) + boardB.rows.take(5))
        val state = LayoutWidgetState(WidgetConfig(1), layout, boards, now, covers = covers)
        val ids = glanceIdsUsed(renderRemoteViews(context, DpSize(411.dp, 800.dp)) { LayoutWidgetContent(state, staticRows = false) })
        assertTrue("$ids view ids", ids <= GLANCE_VIEW_ID_BUDGET)
    }

    @Test
    fun screenshotWideOneRow() = runTest {
        render(wide, state((1..9).map { row(it) })).captureRoboImage("src/test/screenshots/viewgallery_4x2.png")
    }

    @Test
    fun screenshotLargeWithRatings() = runTest {
        render(large, state((1..8).map { row(it) })).captureRoboImage("src/test/screenshots/viewgallery_4x4.png")
    }

    @Test
    fun screenshotFullWithMoreLineAndAMissingCover() = runTest {
        val rows = (1..20).map { row(it, cover = it != 3) }
        render(full, state(rows)).captureRoboImage("src/test/screenshots/viewgallery_full_more.png")
    }

    @Test
    fun screenshotCompactFallsBackToTheList() = runTest {
        render(compact, state((1..4).map { row(it) }), staticRows = true).captureRoboImage("src/test/screenshots/viewgallery_2x2_list.png")
    }
}
