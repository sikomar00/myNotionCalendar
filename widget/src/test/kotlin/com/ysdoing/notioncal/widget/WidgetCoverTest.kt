package com.ysdoing.notioncal.widget

import android.graphics.Bitmap
import com.ysdoing.notioncal.core.data.view.CoverStore
import com.ysdoing.notioncal.core.data.view.ViewRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * 책 표지 비트맵 예산(PF-07 좁은 예외, 사용자 승인 2026-11-03).
 * Android 17은 위젯 비트맵이 '화면 폭×높이×4바이트×1.5'를 넘으면 앱을 죽인다. 표지는 그 한도의 1/10도 안 되게 쓴다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h900dp-xxhdpi")
class WidgetCoverTest {
    private val dir: File = Files.createTempDirectory("covers").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun jpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    @Test
    fun aStoredCoverAtTheMaximumSizeFitsTheByteBudgetAsRgb565() {
        // 동기화가 만드는 가장 큰 표지(240×360)를 픽셀당 2바이트로 읽으면 예산 안이다.
        assertTrue(240 * 360 * 2 <= CoverBudget.MAX_BITMAP_BYTES)
        val file = File(dir, "c.jpg").also { jpeg(it, 240, 360) }
        val bitmap = Covers.decode(file)
        assertNotNull(bitmap)
        assertEquals(Bitmap.Config.RGB_565, bitmap!!.config)
        assertTrue("${bitmap.byteCount}바이트", bitmap.byteCount <= CoverBudget.MAX_BITMAP_BYTES)
    }

    @Test
    fun anOversizedImageIsShrunkUnderTheBudgetKeepingItsShape() {
        val file = File(dir, "big.jpg").also { jpeg(it, 600, 900) } // 비정상: 동기화는 이 크기를 만들지 않는다
        val bitmap = Covers.decode(file)!!
        assertTrue("${bitmap.byteCount}바이트", bitmap.byteCount <= CoverBudget.MAX_BITMAP_BYTES)
        assertEquals(600f / 900f, bitmap.width.toFloat() / bitmap.height, 0.02f)
    }

    @Test
    fun missingOrBrokenFilesGiveNoBitmapInsteadOfCrashing() {
        assertNull(Covers.decode(File(dir, "none.jpg")))
        val broken = File(dir, "broken.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertNull(Covers.decode(broken))
    }

    @Test
    fun loadReadsOnlyTheFirstSixCoversThatExist() {
        val store = CoverStore(dir)
        val rows = (1..9).map { ViewRow("p$it", "", listOf("책 $it"), "https://img.example/$it.jpg", 0.5f) }
        // 1·2·4·8번만 파일이 있다. 7번 이후는 상한(6권) 밖이라 파일이 있어도 읽지 않는다.
        listOf(1, 2, 4, 8).forEach { jpeg(store.fileFor("https://img.example/$it.jpg"), 40, 60) }
        val covers = Covers.load(store, rows)
        assertEquals(setOf("p1", "p2", "p4"), (1..9).map { "p$it" }.filter { covers.has(it) }.toSet())
        assertEquals(3, covers.size)
        assertTrue(CoverBudget.MAX_COVERS * CoverBudget.MAX_BITMAP_BYTES <= 1_200_000) // 위젯 하나가 쥐는 표지 비트맵 합계 상한
    }

    // --- Phase 12d 갤러리: 위젯 하나의 표지 비트맵 합계를 묶는다(PF-07).

    @Test
    fun theGalleryBudgetKeepsTheWholeWidgetUnderItsCoverTotal() {
        assertEquals(9, CoverBudget.GALLERY_MAX_COVERS)
        assertTrue(CoverBudget.GALLERY_MAX_COVERS * CoverBudget.GALLERY_BITMAP_BYTES <= CoverBudget.GALLERY_TOTAL_BYTES)
        // 합계는 화면 기준 위젯 비트맵 한도(PF-07의 25%)와 책 위젯 합계(6×200KB)보다 크지 않다.
        assertTrue(CoverBudget.GALLERY_TOTAL_BYTES <= ChartBudget.widgetBytes(720, 1600))
        assertTrue(CoverBudget.GALLERY_TOTAL_BYTES <= CoverBudget.MAX_COVERS * CoverBudget.MAX_BITMAP_BYTES)
    }

    @Test
    fun theCoverCountPerGalleryDividesTheNineAndNeverDropsBelowThree() {
        assertEquals(9, CoverBudget.galleryCoversPerBoard(1))
        assertEquals(4, CoverBudget.galleryCoversPerBoard(2))
        assertEquals(3, CoverBudget.galleryCoversPerBoard(3))
        assertEquals(3, CoverBudget.galleryCoversPerBoard(5)) // 보기당 최소 3장
        assertEquals(9, CoverBudget.galleryCoversPerBoard(0))
    }

    @Test
    fun loadGalleryShrinksEachCoverIntoTheGalleryBudgetAndReadsAtMostNine() {
        val store = CoverStore(dir)
        val rows = (1..12).map { ViewRow("p$it", "", listOf("카드 $it"), "https://img.example/$it.jpg") }
        rows.forEach { jpeg(store.fileFor(it.coverUrl), 240, 360) } // 동기화가 만드는 최대 크기
        val covers = Covers.loadGallery(store, listOf(rows))
        assertEquals(9, covers.size)
        assertTrue(rows.take(9).all { covers.has(it.pageId) } && rows.drop(9).none { covers.has(it.pageId) })
        val one = Covers.decode(store.fileFor(rows[0].coverUrl), CoverBudget.GALLERY_BITMAP_BYTES)!!
        assertTrue("${one.byteCount}바이트", one.byteCount <= CoverBudget.GALLERY_BITMAP_BYTES)
        assertEquals(240f / 360f, one.width.toFloat() / one.height, 0.02f) // 모양은 지킨다
    }

    @Test
    fun severalGalleriesShareTheNineCoversWithAtLeastThreeEach() {
        val store = CoverStore(dir)
        fun rows(prefix: String) = (1..9).map { ViewRow("$prefix$it", "", listOf("카드"), "https://img.example/$prefix$it.jpg") }
        val boards = listOf("a", "b", "c").map(::rows)
        boards.flatten().forEach { jpeg(store.fileFor(it.coverUrl), 40, 60) }
        val covers = Covers.loadGallery(store, boards)
        assertEquals(9, covers.size) // 보기 셋: 한 보기에 세 장씩
        assertTrue(covers.has("a3") && !covers.has("a4") && covers.has("c1"))
        assertEquals(0, Covers.loadGallery(store, emptyList()).size)
    }

    @Test
    fun rowsWithoutACoverUrlAreSkipped() {
        val covers = Covers.load(CoverStore(dir), listOf(ViewRow("p1", "", listOf("책"), "", null)))
        assertEquals(0, covers.size)
    }
}
