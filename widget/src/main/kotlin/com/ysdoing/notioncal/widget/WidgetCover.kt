package com.ysdoing.notioncal.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.scale
import androidx.glance.layout.ContentScale
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.size
import com.ysdoing.notioncal.core.data.view.CoverStore
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.widget.CoverBudget.MAX_BITMAP_BYTES
import java.io.File

// M5 책 표지. 위젯의 비트맵 금지(PF-07)에 **이 파일 하나만** 예외를 둔다(사용자 승인 2026-11-03, docs/plans/2026-11-03-m5-reading-widget.md).
// WidgetNoBitmapTest가 다른 모든 위젯 소스에서 비트맵 API를 계속 막고, 아래 예산을 WidgetCoverTest가 지킨다.

/**
 * Android 17은 위젯 비트맵이 '화면 폭×높이×4바이트×1.5'를 넘으면 앱을 죽인다(PF-07). 표지는 그 한도의 1/10도 안 되는 크기로만 쓴다:
 * 받아 둔 JPEG는 240×360px 이내이고(`CoverScaler`), 디코딩은 픽셀당 2바이트(RGB_565)라 한 장 172,800바이트다.
 */
internal object CoverBudget {
    /** 비트맵 한 장 상한(바이트). */
    const val MAX_BITMAP_BYTES = 200_000

    /** 책 위젯 하나가 한꺼번에 그리는 표지 수 상한. 동기화가 한 보기에서 받는 수와 같은 상수다. */
    const val MAX_COVERS = CoverStore.MAX_COVERS_PER_VIEW

    /**
     * 갤러리(Phase 12d)는 표지가 많아(최대 [GALLERY_MAX_COVERS]장) 위젯 하나의 표지 비트맵 합계를 [GALLERY_TOTAL_BYTES]로 묶는다
     * (PF-07 25% 한도와 위젯 Binder 여유 안): 한 장 상한은 합계를 장수로 나눈 값이라 카드 크기의 표지를 약 160×240px로 줄여 읽는다.
     */
    const val GALLERY_TOTAL_BYTES = 700_000
    const val GALLERY_MAX_COVERS = CoverStore.MAX_GALLERY_COVERS
    const val GALLERY_BITMAP_BYTES = GALLERY_TOTAL_BYTES / GALLERY_MAX_COVERS

    private const val MIN_GALLERY_PER_BOARD = 3

    /** 갤러리 보기 [boards]개가 그려지면 보기마다 읽을 표지 수: 합계 장수를 나누되 보기당 최소 3장(그려지는 보기만 센다). */
    fun galleryCoversPerBoard(boards: Int): Int = (GALLERY_MAX_COVERS / boards.coerceAtLeast(1)).coerceAtLeast(MIN_GALLERY_PER_BOARD)
}

/** 위젯이 그릴 표지 묶음(페이지 ID → 비트맵). 비트맵을 다루는 코드는 이 클래스 안에만 있다. */
internal class Covers private constructor(private val byPage: Map<String, Bitmap>) {
    fun has(pageId: String): Boolean = pageId in byPage

    val size: Int get() = byPage.size

    /**
     * [pageId]의 표지를 [width]×[height]에 맞춰 그린다(가운데를 잘라 채움, [fit]이면 전체가 보이게 맞춤). 없으면 아무것도 그리지 않는다.
     * 낭독 설명은 이 그림을 감싼 눌림 상자가 맡아(제목 + 읽은 비율) 그림에는 두지 않는다: 같은 말을 두 번 읽지 않게.
     */
    @Composable
    fun Draw(pageId: String, width: Dp, height: Dp, corner: Dp, fit: Boolean = false) {
        val bitmap = byPage[pageId] ?: return
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier.size(width, height).cornerRadius(corner),
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
        )
    }

    companion object {
        val EMPTY = Covers(emptyMap())

        /** 시험에서 비트맵을 직접 넣는다. */
        internal fun of(byPage: Map<String, Bitmap>) = Covers(byPage)

        /**
         * 받아 둔 표지 파일을 읽는다(파일 입출력이라 메인 스레드에서 부르지 않는다). 앞쪽 [CoverBudget.MAX_COVERS]권만,
         * 파일이 없거나 읽을 수 없는 책은 건너뛴다(그 책은 제목 타일로 그려진다).
         */
        fun load(store: CoverStore, rows: List<ViewRow>): Covers {
            val map = LinkedHashMap<String, Bitmap>()
            for (row in rows.take(CoverBudget.MAX_COVERS)) {
                if (row.coverUrl.isEmpty()) continue
                decode(store.fileFor(row.coverUrl))?.let { map[row.pageId] = it }
            }
            return Covers(map)
        }

        /**
         * 지금 그려지는 갤러리 보기들의 표지를 읽는다([boards]는 보기마다 행 목록, IO 스레드에서). 합계 장수를 보기 수로 나눠([CoverBudget.galleryCoversPerBoard])
         * 보기마다 앞쪽 행만, 한 장씩 [CoverBudget.GALLERY_BITMAP_BYTES] 안으로 줄여 읽어 위젯 하나의 표지 합계를 묶는다. 표지가 없는 행은 건너뛴다(빈 타일로 그린다).
         */
        fun loadGallery(store: CoverStore, boards: List<List<ViewRow>>): Covers {
            if (boards.isEmpty()) return EMPTY
            val perBoard = CoverBudget.galleryCoversPerBoard(boards.size)
            val map = LinkedHashMap<String, Bitmap>()
            for (rows in boards) {
                for (row in rows.take(perBoard)) {
                    if (row.coverUrl.isEmpty() || row.pageId in map) continue
                    decode(store.fileFor(row.coverUrl), CoverBudget.GALLERY_BITMAP_BYTES)?.let { map[row.pageId] = it }
                }
            }
            return Covers(map)
        }

        /** JPEG를 RGB_565로 읽고, 예산([maxBytes], 기본 [MAX_BITMAP_BYTES])을 넘으면 줄인다. 깨졌거나 없으면 null(위젯이 죽지 않는다). */
        internal fun decode(file: File, maxBytes: Int = MAX_BITMAP_BYTES): Bitmap? {
            if (!file.isFile) return null
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = try {
                BitmapFactory.decodeFile(file.path, options)
            } catch (e: OutOfMemoryError) {
                null
            } ?: return null
            if (bitmap.byteCount <= maxBytes) return bitmap
            val ratio = Math.sqrt(maxBytes.toDouble() / bitmap.byteCount)
            val scaled = bitmap.scale(maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()))
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }
    }
}
