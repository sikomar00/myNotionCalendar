package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.VIEW_ROW_LIMIT
import com.ysdoing.notioncal.core.notion.ViewReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 보기 하나의 대조 결과: 노션의 보기 쿼리(보기에 들어가는 페이지 id와 순서)와 위젯이 가진 행(`view_rows`)의 차이.
 * 제목·값은 담지 않는다(SEC-02). [error]가 있으면 노션 오류로 비교하지 못했다.
 */
data class ViewCompare(
    val viewId: String,
    val name: String,
    val type: String,
    val queryCount: Int,
    val localCount: Int,
    /** 노션 보기에는 있는데 위젯에 없는 행 수. */
    val missingLocally: Int,
    /** 위젯에는 있는데 노션 보기에 없는 행 수. */
    val extraLocally: Int,
    val sameOrder: Boolean,
    val queryComplete: Boolean = true,
    val error: String? = null,
) {
    val matches: Boolean get() = error == null && missingLocally == 0 && extraLocally == 0 && sameOrder

    /** 9-A 실측의 알려진 불일치(board 보기 + relation 빠른 필터 1건, 원인 미확인). 빠른 필터를 저장하지 않아 board면 후보로만 표시한다. */
    val knownExceptionCandidate: Boolean get() = !matches && type == "board"

    fun summary(): String = when {
        error != null -> "$name($type): 비교하지 못함 $error"
        matches -> "$name($type): 일치 ${localCount}행"
        else -> "$name($type): 불일치 노션에만 $missingLocally 앱에만 $extraLocally${if (sameOrder) "" else " 순서 다름"}" +
            (if (knownExceptionCandidate) " (알려진 예외 후보)" else "")
    }
}

/**
 * Phase 10-7 진단 '노션과 비교'의 보기 대조(디버그 메뉴). 등록한 보기마다 노션 보기 쿼리의 페이지 id를 위젯이 받아 둔 행과 비교한다.
 * 위젯은 앞 [VIEW_ROW_LIMIT]행만 받으므로 그만큼만 본다. 읽기만 한다(노션에 쓰지 않고 로컬도 고치지 않는다).
 */
class ViewComparer @Inject constructor(
    private val reader: ViewReader,
    private val db: NotionCalDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun compare(): List<ViewCompare> = withContext(io) {
        db.viewDao().all().map { view ->
            val local = db.viewDao().rows(view.viewId).map { it.pageId }.take(VIEW_ROW_LIMIT)
            try {
                val query = reader.viewQueryIds(view.viewId, VIEW_ROW_LIMIT)
                compareIds(view.viewId, view.name, view.viewType, query.ids.take(VIEW_ROW_LIMIT), local, query.complete)
            } catch (e: NotionException) {
                ViewCompare(view.viewId, view.name, view.viewType, 0, local.size, 0, 0, true, error = e.error::class.simpleName)
            }
        }
    }

    private fun compareIds(viewId: String, name: String, type: String, query: List<String>, local: List<String>, complete: Boolean): ViewCompare {
        val queryIds = query.toSet()
        val localIds = local.toSet()
        val common = queryIds intersect localIds
        return ViewCompare(
            viewId, name, type, query.size, local.size,
            missingLocally = (queryIds - localIds).size,
            extraLocally = (localIds - queryIds).size,
            sameOrder = query.filter { it in common } == local.filter { it in common },
            queryComplete = complete,
        )
    }
}
