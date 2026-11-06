package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 12d 갤러리 보기의 표지 읽기(보기·노션 위젯 공용). 표지 파일 읽기는 IO에서 하고(StrictMode), 행이 그대로여도 표지만 나중에 받아질 수 있으니
 * 표지 저장소의 번호(메모리 값)가 바뀌면 다시 읽는다. 처음 한 번은 [load]로 미리 읽은 것을 그대로 쓴다(같은 JPEG를 두 번 디코딩하지 않는다).
 * 갤러리가 아닌 보기는 표지를 읽지 않는다(책 위젯은 따로 읽는다).
 */
internal object CoverSession {
    private fun galleryRows(boards: Collection<ViewBoard?>): List<List<ViewRow>> = boards.filterNotNull().filter { it.type == "gallery" }.map { it.rows }

    suspend fun load(deps: WidgetDependencies, boards: Collection<ViewBoard?>): Covers {
        val rows = galleryRows(boards)
        return if (rows.isEmpty()) Covers.EMPTY else withContext(Dispatchers.IO) { Covers.loadGallery(deps.covers(), rows) }
    }

    /** 읽을 표지를 가르는 열쇠: 카드가 그릴 앞쪽 행의 (쪽 번호, 표지 주소)와 표지 저장소 번호. */
    private fun keyOf(boards: Collection<ViewBoard?>, version: Int): Pair<List<Pair<String, String>>, Int> =
        galleryRows(boards).flatMap { rows -> rows.take(GALLERY_COVER_CAP).map { it.pageId to it.coverUrl } } to version

    @Composable
    fun covers(deps: WidgetDependencies, initial: Covers, initialBoards: Collection<ViewBoard?>, boards: Collection<ViewBoard?>): Covers {
        val version by remember { deps.covers().version }.collectAsState()
        val key = keyOf(boards, version)
        var loadedKey by remember { mutableStateOf(keyOf(initialBoards, deps.covers().version.value)) }
        var covers by remember { mutableStateOf(initial) }
        LaunchedEffect(key) {
            if (key != loadedKey) {
                covers = load(deps, boards)
                loadedKey = key
            }
        }
        return covers
    }
}
