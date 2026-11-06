package com.ysdoing.notioncal.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * M5a 책 갤러리 위젯: 표지(없으면 제목 타일) + 읽은 비율 막대 + 제목, 표지 탭 → 노션 페이지, 머리 탭 → 새로고침.
 * 표지 비트맵은 시험에서 직접 만든 작은 이미지다(실제 책 표지를 저장소에 두지 않는다).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class BookWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val wide = DpSize(358.dp, 380.dp)
    private val small = DpSize(150.dp, 150.dp)

    private fun row(i: Int, progress: Float? = 0.3f, cover: Boolean = true) =
        ViewRow("p$i", "https://notion.so/p$i", listOf("책 $i"), if (cover) "https://img.example/$i.jpg" else "", progress)

    private val readPages = ViewColumn("ing", "ing", "number", role = ViewColumn.ROLE_PAGES_READ)

    private fun board(rows: List<ViewRow>, error: String? = null, syncedAt: Long = now, steppable: Boolean = false) =
        ViewBoard("v1", "읽는 중", "gallery", listOf("제목"), rows, syncedAt, error, if (steppable) listOf(readPages) else emptyList())

    private fun covers(rows: List<ViewRow>) = Covers.of(rows.filter { it.coverUrl.isNotEmpty() }.associate { it.pageId to Bitmap.createBitmap(10, 15, Bitmap.Config.RGB_565) })

    private fun state(rows: List<ViewRow>, covers: Covers = covers(rows), board: ViewBoard? = board(rows), viewId: String? = "v1") =
        BookWidgetState(board, WidgetConfig(1, viewId = viewId), covers, now)

    @Test
    fun drawsOneTilePerBookWithTitleAndProgress() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row(1, 0.35f), row(2, 0.8f))
        provideComposable { BookWidgetContent(state(rows), systemFontScale = 1f) }
        onAllNodes(hasTestTag("bookTile")).assertCountEquals(2)
        onAllNodes(hasTestTag("bookProgress")).assertCountEquals(2)
        onNode(hasContentDescriptionEqualTo("책 1, 35% 읽음")).assertExists()
        onNode(hasTextEqualTo("책 2")).assertExists()
        onAllNodes(hasTestTag("bookNoCover")).assertCountEquals(0)
    }

    @Test
    fun aBookWithoutACoverFallsBackToATitleTile() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row(1), row(2, cover = false))
        provideComposable { BookWidgetContent(state(rows), systemFontScale = 1f) }
        onAllNodes(hasTestTag("bookNoCover")).assertCountEquals(1)
        onAllNodes(hasTestTag("bookTile")).assertCountEquals(2)
    }

    @Test
    fun aBookWithoutKnownProgressDrawsNoBar() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row(1, progress = null))
        provideComposable { BookWidgetContent(state(rows), systemFontScale = 1f) }
        onAllNodes(hasTestTag("bookProgress")).assertCountEquals(0)
        onNode(hasContentDescriptionEqualTo("책 1")).assertExists() // 비율을 모르면 제목만 읽는다
    }

    @Test
    fun tappingACoverOpensTheNotionPageAndTheHeaderRefreshes() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { BookWidgetContent(state(listOf(row(1))), systemFontScale = 1f) }
        val open = Intent(Intent.ACTION_VIEW, "https://notion.so/p1".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onNode(hasTestTag("bookTile")).assert(hasStartActivityClickAction(open))
        onNode(hasTestTag("bookHeader")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
        onNode(hasTestTag("bookRefresh")).assert(hasRunCallbackClickAction<RefreshAction>(actionParametersOf()))
    }

    @Test
    fun emptyAndUnchosenAndMissingStatesAreSpelledOut() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { BookWidgetContent(state(emptyList()), systemFontScale = 1f) }
        onNode(hasTextEqualTo(context.getString(R.string.book_widget_empty))).assertExists()
    }

    @Test
    fun unchosenViewOpensWidgetSettings() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { BookWidgetContent(state(emptyList(), board = null, viewId = null), systemFontScale = 1f) }
        onNode(hasTestTag("bookNote")).assert(hasStartActivityClickAction(viewConfigIntent(context, 1)))
    }

    @Test
    fun failedSyncIsWrittenAndTheLastCoversStayVisible() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row(1))
        provideComposable { BookWidgetContent(state(rows, board = board(rows, error = "Network")), systemFontScale = 1f) }
        onNode(hasTestTag("bookStatus")).assertExists()
        onAllNodes(hasTestTag("bookTile")).assertCountEquals(1) // 오프라인이어도 마지막 표지는 그대로
    }

    @Test
    fun aGoneViewTellsInWordsAndOpensWidgetSettings() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row(1))
        provideComposable { BookWidgetContent(state(rows, board = board(rows, error = VIEW_ERROR_NOT_FOUND)), systemFontScale = 1f) }
        onNode(hasTestTag("bookStatus")).assert(hasStartActivityClickAction(viewConfigIntent(context, 1)))
    }

    @Test
    fun layoutFitsTheNumberOfBooksAndNeverEnlargesTheCoverBeyondItsCap() {
        val one = bookLayout(300.dp, 400.dp, 14.dp, books = 1)
        assertEquals(1, one.columns)
        assertTrue("표지 폭 ${one.coverWidth}", one.coverWidth <= 110.dp)
        assertEquals(one.coverWidth * 1.5f, one.coverHeight)
        val two = bookLayout(340.dp, 400.dp, 14.dp, books = 2)
        assertEquals(2, two.columns)
        assertEquals(1, two.rows)
        val six = bookLayout(340.dp, 600.dp, 14.dp, books = 6)
        assertEquals(3, six.columns)
        assertEquals(2, six.rows)
        assertEquals(6, six.capacity)
    }

    @Test
    fun aTileRowNeverTallerThanTheBodyAndCaptionsDropFirst() {
        // 낮은 몸통에서는 표지를 몸통보다 크게 두지 않고 아래 글자(제목 → 막대)부터 뺀다(잘림 방지).
        val sizes = listOf(120.dp to 60.dp, 130.dp to 90.dp, 150.dp to 120.dp, 200.dp to 160.dp, 340.dp to 300.dp, 340.dp to 600.dp)
        for ((w, h) in sizes) for (books in listOf(1, 2, 6)) {
            val layout = bookLayout(w, h, 14.dp, books)
            val rowHeight = (h - 8.dp * (layout.rows - 1)) / layout.rows
            assertTrue("$w×$h ${books}권: 타일 ${layout.tileHeight(14.dp)} > 줄 $rowHeight", layout.tileHeight(14.dp) <= rowHeight + 0.5.dp)
        }
        assertEquals(BookCaption.NONE, bookLayout(120.dp, 60.dp, 14.dp, 1).caption)
        assertEquals(BookCaption.FULL, bookLayout(340.dp, 600.dp, 14.dp, 2).caption)
    }

    @Test
    fun screenReadersGetTheExactPercentNotOneLess() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        // 0.53f * 100 = 52.999996: 내림하면 52%로 읽힌다.
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { BookWidgetContent(state(listOf(row(1, progress = 0.53f))), systemFontScale = 1f) }
        onNode(hasContentDescriptionEqualTo("책 1, 53% 읽음")).assertExists()
    }

    @Test
    fun onlyAsManyBooksAsFitAreDrawn() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(small)
        val rows = (1..6).map { row(it) }
        provideComposable { BookWidgetContent(state(rows), systemFontScale = 1f) }
        val drawn = bookLayout(150.dp - 20.dp, 150.dp - 8.dp - viewMetrics(1f, 1f).header, 14.dp, 6).capacity
        assertTrue("작은 위젯은 6권을 다 그리지 않는다: $drawn", drawn < 6)
        onAllNodes(hasTestTag("bookTile")).assertCountEquals(minOf(drawn, 6).coerceAtLeast(1))
    }

    // --- M5b −/+ 쪽 버튼

    @Test
    fun eachCoverGetsMinusAndPlusThatRunTheStepAction() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row(1), row(2))
        provideComposable { BookWidgetContent(state(rows, board = board(rows, steppable = true)), systemFontScale = 1f) }
        onAllNodes(hasTestTag("bookPagesLess")).assertCountEquals(2)
        onAllNodes(hasTestTag("bookPagesMore")).assertCountEquals(2)
        onNode(hasContentDescriptionEqualTo("책 1, 10쪽 더 읽기")).assert(
            hasRunCallbackClickAction<BookPagesAction>(actionParametersOf(ViewCellAction.ViewKey to "v1", ViewCellAction.PageKey to "p1", BookPagesAction.DeltaKey to 10)),
        )
        onNode(hasContentDescriptionEqualTo("책 2, 10쪽 줄이기")).assert(
            hasRunCallbackClickAction<BookPagesAction>(actionParametersOf(ViewCellAction.ViewKey to "v1", ViewCellAction.PageKey to "p2", BookPagesAction.DeltaKey to -10)),
        )
        // 표지 탭은 그대로 노션 페이지를 연다.
        val open = Intent(Intent.ACTION_VIEW, "https://notion.so/p1".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        onAllNodes(hasTestTag("bookTile"))[0].assert(hasStartActivityClickAction(open))
    }

    @Test
    fun noButtonsWithoutAReadPagesProperty() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row(1))
        provideComposable { BookWidgetContent(state(rows, board = board(rows)), systemFontScale = 1f) } // 쪽수 속성이 없는 보기
        onAllNodes(hasTestTag("bookPagesMore")).assertCountEquals(0)
        onAllNodes(hasTestTag("bookPagesLess")).assertCountEquals(0)
    }

    @Test
    fun coversNarrowerThanTheButtonsNeedKeepOnlyTheirNotionTap() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(small)
        val rows = listOf(row(1))
        val layout = bookLayout(small.width - 20.dp, small.height - 8.dp - viewMetrics(1f, 1f).header, 14.dp, 1)
        assertTrue("시험 전제: 표지 ${layout.coverWidth}가 ${STEP_MIN_COVER_WIDTH}보다 좁아야 한다", layout.coverWidth < STEP_MIN_COVER_WIDTH)
        provideComposable { BookWidgetContent(state(rows, board = board(rows, steppable = true)), systemFontScale = 1f) }
        onAllNodes(hasTestTag("bookPagesMore")).assertCountEquals(0)
        onAllNodes(hasTestTag("bookTile")).assertCountEquals(1)
    }

    @Test
    fun aBookWithoutACoverKeepsItsTitleAboveTheButtons() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        val rows = listOf(row(1, cover = false))
        provideComposable { BookWidgetContent(state(rows, board = board(rows, steppable = true)), systemFontScale = 1f) }
        onAllNodes(hasTestTag("bookNoCover")).assertCountEquals(1)
        onAllNodes(hasTestTag("bookPagesMore")).assertCountEquals(1) // 표지가 없어도 쪽수는 바꿀 수 있다
    }

    @Test
    fun translatesToRemoteViewsWithTheButtonsInEverySizeAndFont() = runTest(timeout = 3.minutes) {
        // 표지마다 버튼 둘(각 View 서넛)이 더해져도 View 개수·자식 10개 한도 안이다(가장 빽빽한 6권).
        val rows = (1..8).map { row(it, progress = if (it % 3 == 0) null else it / 10f, cover = it % 2 == 1) }
        for (size in listOf(DpSize(110.dp, 110.dp), DpSize(280.dp, 150.dp), DpSize(358.dp, 380.dp), DpSize(411.dp, 800.dp))) for (font in listOf(70, 150)) {
            val s = BookWidgetState(board(rows, steppable = true), WidgetConfig(1, fontScale = font, viewId = "v1"), covers(rows), now)
            render(size) { BookWidgetContent(s, systemFontScale = 1.3f) }
        }
    }

    private suspend fun render(size: DpSize, content: @androidx.compose.runtime.Composable () -> Unit) {
        val remoteViews = GlanceRemoteViews().compose(context, size) { content() }.remoteViews
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val frame = FrameLayout(activity)
        frame.addView(remoteViews.apply(activity, frame))
        frame.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
    }

    @Test
    fun translatesToRemoteViewsWithCoversInEverySizeAndFont() = runTest(timeout = 3.minutes) {
        // Row·Column 자식 10개 제한과 표지 비트맵이 든 RemoteViews 변환(PRD §13). 표지 6장이 든 큰 위젯도 한도 안이다.
        val sizes = listOf(DpSize(110.dp, 110.dp), DpSize(280.dp, 150.dp), DpSize(358.dp, 380.dp), DpSize(411.dp, 800.dp))
        val rows = (1..8).map { row(it, progress = if (it % 3 == 0) null else it / 10f, cover = it % 2 == 1) }
        for (size in sizes) for (font in listOf(70, 150)) for (system in listOf(1f, 1.3f)) {
            val s = BookWidgetState(board(rows), WidgetConfig(1, fontScale = font, viewId = "v1"), covers(rows), now)
            render(size) { BookWidgetContent(s, systemFontScale = system) }
        }
        render(DpSize(358.dp, 380.dp)) { BookWidgetContent(state(emptyList()), systemFontScale = 1f) }
        render(DpSize(358.dp, 380.dp)) { BookWidgetContent(state(rows, board = null, viewId = null), systemFontScale = 1f) }
    }

    @Test
    fun previewStateDrawsWithoutAnyCoverFile() = runGlanceAppWidgetUnitTest(timeout = 60.seconds) {
        setContext(context)
        setAppWidgetSize(wide)
        provideComposable { BookWidgetContent(WidgetPreviews.bookState(now), systemFontScale = 1f) }
        onAllNodes(hasTestTag("bookTile")).assertCountEquals(2)
        onAllNodes(hasTestTag("bookNoCover")).assertCountEquals(2)
    }
}
