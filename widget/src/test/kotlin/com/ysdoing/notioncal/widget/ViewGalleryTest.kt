package com.ysdoing.notioncal.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 12d VR-GAL 갤러리 카드: 표지(없으면 빈 타일) + 제목 + 속성 줄 + 별점 글자, 카드 탭 → 행 편집 시트, 넘치면 '더 보기',
 * 2×2는 목록으로 폴백한다. 표지 비트맵은 시험에서 직접 만든 작은 이미지다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class ViewGalleryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val wide = DpSize(501.dp, 213.dp)
    private val full = DpSize(501.dp, 581.dp)
    private val compact = DpSize(234.dp, 213.dp)

    private val columns = listOf(
        ViewColumn("t", "이름", "title"), ViewColumn("a", "저자", "rich_text"), ViewColumn("r", "별점", "number"),
    )

    private fun row(i: Int, cover: Boolean = true) = ViewRow(
        "p$i", "https://notion.so/p$i", listOf("책 $i", "저자 $i"), if (cover) "https://img.example/$i.jpg" else "",
        typed = mapOf("r" to kotlinx.serialization.json.JsonPrimitive((i % 5 + 1).toDouble())),
    )

    private fun board(rows: List<ViewRow>, viewConfig: String = "", complete: Boolean = true) =
        ViewBoard("g1", "책장", "gallery", listOf("이름", "저자"), rows, now, null, columns, viewConfig = viewConfig, rowsComplete = complete)

    private fun covers(rows: List<ViewRow>) = Covers.of(rows.filter { it.coverUrl.isNotEmpty() }.associate { it.pageId to Bitmap.createBitmap(10, 15, Bitmap.Config.RGB_565) })

    private fun state(rows: List<ViewRow>, covers: Covers = covers(rows), rating: String = "", viewConfig: String = "", complete: Boolean = true, config: WidgetConfig = WidgetConfig(1, viewId = "g1")) =
        ViewWidgetState(board(rows, viewConfig, complete), config, now, covers = covers, ratingProp = rating)

    @Test
    fun drawsACardPerRowWithItsCoverTitleAndPropertyLine() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        val rows = (1..6).map { row(it) }
        provideComposable { ViewWidgetContent(state(rows), systemFontScale = 1f) }
        onAllNodes(hasTestTag("galleryCard")).assertCountEquals(6)
        onAllNodes(hasTestTag("galleryNoCover")).assertCountEquals(0)
        onNode(hasTextEqualTo("책 1")).assertExists()
        onNode(hasTextEqualTo("저자 1")).assertExists()
        onNode(hasContentDescriptionEqualTo("책 1, 저자 1")).assertExists()
    }

    @Test
    fun aRowWithoutACoverGetsAnEmptyTileButStillItsTitle() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        val rows = listOf(row(1), row(2, cover = false), row(3))
        provideComposable { ViewWidgetContent(state(rows), systemFontScale = 1f) }
        onAllNodes(hasTestTag("galleryCard")).assertCountEquals(3)
        onAllNodes(hasTestTag("galleryNoCover")).assertCountEquals(1)
        onNode(hasTextEqualTo("책 2")).assertExists()
    }

    @Test
    fun aCardOpensTheRowSheetAndTheRatingShowsAsStars() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        val rows = listOf(row(1), row(2))
        provideComposable { ViewWidgetContent(state(rows, rating = "r"), systemFontScale = 1f) }
        onAllNodes(hasTestTag("galleryCard"))[0].assert(hasStartActivityClickAction(viewRowIntent(context, "g1", "p1")))
        onNode(hasTextEqualTo("★★☆☆☆")).assertExists() // p1: 1 % 5 + 1 = 2
        onNode(hasContentDescriptionEqualTo("책 1, 별점 2점 (5점 만점), 저자 1")).assertExists()
    }

    @Test
    fun moreRowsThanCardsLeaveAMoreLineThatOpensTheViewer() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        val rows = (1..30).map { row(it) }
        provideComposable { ViewWidgetContent(state(rows), systemFontScale = 1f) }
        onNode(hasTestTag("viewMore")).assertExists()
        onAllNodes(hasTestTag("galleryCard")).assertCountEquals(8) // 4열 × 2줄
    }

    @Test
    fun theCompactSizeFallsBackToTheRowList() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(compact)
        provideComposable { ViewWidgetContent(state((1..3).map { row(it) }), systemFontScale = 1f) }
        onAllNodes(hasTestTag("galleryCard")).assertCountEquals(0)
        onAllNodes(hasTestTag("viewRow")).assertCountEquals(3)
    }

    @Test
    fun aNonGalleryViewIsNeverDrawnAsCards() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        val rows = (1..3).map { row(it) }
        val table = ViewWidgetState(board(rows).copy(type = "table"), WidgetConfig(1, viewId = "g1"), now, covers = covers(rows))
        provideComposable { ViewWidgetContent(table, systemFontScale = 1f) }
        onAllNodes(hasTestTag("galleryCard")).assertCountEquals(0)
    }

    @Test
    fun theNotionCardSizeChangesTheColumnCount() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(full)
        val rows = (1..6).map { row(it) }
        provideComposable { ViewWidgetContent(state(rows, viewConfig = """{"type":"gallery","cover_size":"large"}"""), systemFontScale = 1f) }
        onAllNodes(hasTestTag("galleryCard")).assertCountEquals(6) // 큰 카드는 3열 × 2줄(같은 6장이 보통 크기에서는 4열이라 2줄로 8장 자리)
    }

    @Test
    fun theShortWideSizeShowsOneRowOfCards() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { ViewWidgetContent(state((1..9).map { row(it) }), systemFontScale = 1f) }
        onAllNodes(hasTestTag("galleryCard")).assertCountEquals(4)
    }
}
