package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.CoverSize
import com.ysdoing.notioncal.core.data.view.GalleryConfig
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12d VR-GAL 갤러리 격자 계획과 카드 캡션(순수). 2×2·낮은 칸은 목록으로 폴백, 카드는 9장을 넘지 않고,
 * 높이가 모자라면 속성 줄 → 표지 높이 순으로 줄이며, 다 못 보이면 '더 보기' 자리를 먼저 뺀다.
 */
class ViewGalleryModelTest {
    private val m = viewMetrics(1f, 1f)
    private val compact = DpSize(234.dp, 213.dp)
    private val wide = DpSize(501.dp, 213.dp)
    private val large = DpSize(501.dp, 458.dp)
    private val full = DpSize(501.dp, 581.dp)
    private val spec = PaneSpec()

    private val columns = listOf(
        ViewColumn("t", "이름", "title"),
        ViewColumn("a", "저자", "rich_text"),
        ViewColumn("y", "연도", "number"),
        ViewColumn("f", "표지", "files"),
        ViewColumn("r", "별점", "number"),
        ViewColumn("s", "평가", "select", listOf(ViewChoice("o1", "좋음"))),
    )

    private fun row(i: Int, vararg cells: String, typed: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
        ViewRow("p$i", "", listOf("책 $i") + cells.toList(), typed = typed)

    private fun board(rows: Int = 20, complete: Boolean = true, visible: List<String> = listOf("이름", "저자", "연도")) =
        ViewBoard("g1", "책장", "gallery", visible, (1..rows).map { row(it, "저자 $it", "20$it") }, 1L, null, columns, rowsComplete = complete)

    private fun layout(board: ViewBoard, size: DpSize, config: GalleryConfig = GalleryConfig(), above: androidx.compose.ui.unit.Dp = 0.dp) =
        galleryLayoutFor(board, size, spec, m, above, config)

    @Test
    fun theCompactSizeAndTooLowASpaceFallBackToTheList() {
        assertNull(layout(board(), compact))
        assertNull(layout(board(), DpSize(501.dp, 90.dp))) // 읽을 표지 한 줄도 안 들어간다
        assertNull(layout(board(), large, above = 600.dp)) // 위에 줄이 너무 많다
    }

    @Test
    fun columnsFollowTheWidthAndTheNotionCardSize() {
        assertEquals(2, galleryColumns(150.dp, CoverSize.MEDIUM)) // 좁아도 2열
        assertEquals(4, galleryColumns(480.dp, CoverSize.MEDIUM))
        assertEquals(3, galleryColumns(480.dp, CoverSize.LARGE))
        assertEquals(4, galleryColumns(480.dp, CoverSize.SMALL)) // 상한 4열
    }

    @Test
    fun cardsNeverExceedNineAndEveryRowIsFullWidth() {
        for (size in listOf(wide, large, full, DpSize(411.dp, 800.dp), DpSize(358.dp, 640.dp))) {
            val l = layout(board(), size)!!
            assertTrue("$size ${l.cards}장", l.cards in 1..GALLERY_COVER_CAP)
            assertTrue(l.cardWidth * l.columns + GALLERY_GAP * (l.columns - 1) <= size.width - PADDING_H * 2 + 0.5.dp)
        }
    }

    @Test
    fun fullSizeShowsMoreRowsThanTheShortWideSize() {
        val short = layout(board(), wide)!!
        val tall = layout(board(), full)!!
        assertTrue("${short.rows} < ${tall.rows}", short.rows < tall.rows)
        assertEquals(1, short.propLines) // 낮은 4×2는 속성 한 줄
        assertEquals(2, tall.propLines)
    }

    @Test
    fun anOverflowingBoardLeavesRoomForTheMoreLine() {
        val everything = layout(board(rows = 4, complete = true), full)!!
        val overflowing = layout(board(rows = 40, complete = true), full)!!
        assertTrue(overflowing.rows <= everything.rows)
        // 행을 일부만 받았어도(노션에 더 있다) 다 보이는 행 수에서도 더 보기 자리를 뺀다.
        assertTrue(layout(board(rows = 4, complete = false), full)!!.rows <= everything.rows)
    }

    @Test
    fun spareHeightIsUsedForOneMoreRowByLoweringTheCoverTowardASquare() {
        val l = layout(board(rows = 30), large)!!
        assertEquals(2, l.rows) // 표지를 4:5로 두면 한 줄뿐이던 4×4
        assertTrue(l.coverHeight >= l.cardWidth * 0.9f && l.coverHeight < l.cardWidth * 1.25f)
        // 행이 한 줄로 다 보이면 더 넣지 않고 표지를 그대로 둔다.
        val few = layout(board(rows = 3), large)!!
        assertEquals(1, few.rows)
        assertEquals(few.cardWidth * 1.25f, few.coverHeight)
    }

    @Test
    fun aLowButUsableHeightShrinksTheCoverBeforeGivingUp() {
        val l = layout(board(rows = 3), DpSize(501.dp, 190.dp))
        assertNotNull(l)
        assertEquals(1, l!!.rows)
        assertTrue(l.coverHeight >= 56.dp && l.coverHeight <= l.cardWidth * 1.25f + 0.1.dp)
    }

    @Test
    fun aPaneHeightLimitsTheGridLikeATabOrSectionCell() {
        val pane = PaneSpec(height = 300.dp)
        val inPane = galleryLayoutFor(board(), full, pane, m, 0.dp, GalleryConfig())!!
        assertTrue(inPane.rows <= layout(board(), full)!!.rows)
    }

    // --- 캡션

    private val b = board()

    @Test
    fun theCaptionIsTheTitleThenTheVisiblePropertyValuesWithoutFilesOrEmptyOnes() {
        val r = ViewRow("p1", "", listOf("달", "", "2020", "https://x/a.jpg"), typed = emptyMap())
        val board = b.copy(columns = listOf("이름", "저자", "연도", "표지"))
        val caption = galleryCaption(board, r, propLines = 2, ratingProp = "")
        assertEquals("달", caption.title)
        assertEquals(listOf("2020"), caption.lines) // 빈 저자와 파일 열은 뺀다
        assertEquals("달, 2020", caption.description)
        assertEquals(emptyList<String>(), galleryCaption(board, r, 0, "").lines) // 속성 줄 0
    }

    @Test
    fun aRatingPropertyTakesTheFirstLineEvenWhenTheViewHidesItAndIsSpokenAsAScore() {
        val r = ViewRow("p1", "", listOf("달", "저자 가", "2020"), typed = mapOf("r" to JsonPrimitive(4.0)))
        val caption = galleryCaption(b, r, propLines = 2, ratingProp = "r") // 별점 열 '별점'은 보기에 안 보인다
        assertEquals(listOf("★★★★☆", "저자 가"), caption.lines)
        assertEquals("달, 별점 4점 (5점 만점), 저자 가", caption.description)
        assertEquals(listOf("★★★★☆"), galleryCaption(b, r, 1, "r").lines) // 줄이 하나면 별점이 이긴다
    }

    @Test
    fun aSelectRatingShowsItsOptionAndAnEmptyRatingLeavesTheLinesToTheValues() {
        val withSelect = ViewRow("p1", "", listOf("달", "저자 가", "2020"), typed = mapOf("s" to JsonPrimitive("o1")))
        assertEquals(listOf("좋음", "저자 가"), galleryCaption(b, withSelect, 2, "s").lines)
        val empty = ViewRow("p2", "", listOf("달", "저자 가", "2020"), typed = emptyMap())
        assertEquals(listOf("저자 가", "2020"), galleryCaption(b, empty, 2, "r").lines) // 값 없는 별점은 줄이 없다
        assertEquals(listOf("저자 가", "2020"), galleryCaption(b, empty, 2, "gone").lines) // 사라진 속성
    }

    @Test
    fun aVisibleRatingColumnIsNotShownTwice() {
        val board = b.copy(columns = listOf("이름", "별점", "저자"))
        val r = ViewRow("p1", "", listOf("달", "4", "저자 가"), typed = mapOf("r" to JsonPrimitive(4.0)))
        assertEquals(listOf("★★★★☆", "저자 가"), galleryCaption(board, r, 2, "r").lines)
    }
}
