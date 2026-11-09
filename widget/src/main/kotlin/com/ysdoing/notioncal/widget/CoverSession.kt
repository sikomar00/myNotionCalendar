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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Phase 12d 갤러리 보기의 표지 읽기(보기·노션 위젯 공용). 표지 파일 읽기는 IO에서 하고(StrictMode), 행이 그대로여도 표지만 나중에 받아질 수 있으니
 * 표지 저장소의 번호(메모리 값)가 바뀌면 다시 읽는다. 동기화가 표지를 여러 장 잇달아 저장하면 번호가 연달아 오르니 [RELOAD_DEBOUNCE_MS] 동안 조용해진 뒤
 * 한 번만 읽는다. 처음 한 번은 [load]로 미리 읽은 것을 그대로 쓴다(같은 JPEG를 두 번 디코딩하지 않는다).
 * 갤러리가 아닌 보기는 표지를 읽지 않는다(책 위젯은 따로 읽는다). [boards]는 지금 그려지는 보기들이다(숨은 탭의 표지는 읽지 않는다).
 */
internal object CoverSession {
    private const val RELOAD_DEBOUNCE_MS = 400L

    private fun galleryRows(boards: Collection<ViewBoard?>): List<List<ViewRow>> = boards.filterNotNull().filter { it.type == "gallery" }.map { it.rows }

    suspend fun load(deps: WidgetDependencies, boards: Collection<ViewBoard?>): Covers {
        val rows = galleryRows(boards)
        return if (rows.isEmpty()) Covers.EMPTY else withContext(Dispatchers.IO) { Covers.loadGallery(deps.covers(), rows) }
    }

    /** 읽을 표지를 가르는 열쇠: 읽는 앞쪽 행(보기당 [CoverBudget.galleryCoversPerBoard]개)의 (쪽 번호, 표지 주소)와 표지 저장소 번호. */
    private fun keyOf(boards: Collection<ViewBoard?>, version: Int): Pair<List<Pair<String, String>>, Int> {
        val galleries = galleryRows(boards)
        val perBoard = CoverBudget.galleryCoversPerBoard(galleries.size)
        return galleries.flatMap { rows -> rows.take(perBoard).map { it.pageId to it.coverUrl } } to version
    }

    /** [initial]은 저장소 번호가 [initialVersion]일 때 [initialBoards]로 미리 읽은 표지다(그 뒤에 받아진 표지는 번호가 올라 다시 읽힌다). */
    @Composable
    fun covers(deps: WidgetDependencies, initial: Covers, initialVersion: Int, initialBoards: Collection<ViewBoard?>, boards: Collection<ViewBoard?>): Covers {
        val version by remember { deps.covers().version }.collectAsState()
        val key = keyOf(boards, version)
        var loadedKey by remember { mutableStateOf(keyOf(initialBoards, initialVersion)) }
        var covers by remember { mutableStateOf(initial) }
        LaunchedEffect(key) {
            if (key != loadedKey) {
                delay(RELOAD_DEBOUNCE_MS)
                covers = load(deps, boards)
                loadedKey = key
            }
        }
        return covers
    }
}
