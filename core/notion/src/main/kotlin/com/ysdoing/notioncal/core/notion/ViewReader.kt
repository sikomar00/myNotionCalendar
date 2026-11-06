package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.ViewDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 보기 위젯이 한 보기에서 받는 행 수(VW-07). 더 있어도 이만큼만 받는다. 노션이 한 번에 주는 최대(100)라 요청 수는 그대로다.
 * 넘치면 [ViewRows.hasMore]로 알리고 위젯은 'N+ 더 보기'로 적는다.
 */
const val VIEW_ROW_LIMIT = 100

/** 보기 행 받기 결과. [hasMore]는 상한을 넘는 행이 노션에 더 있다는 뜻이다. */
data class ViewRows(val pages: List<PageDto>, val hasMore: Boolean)

/** VW-01·04 노션 보기 읽기. 테스트에서는 메모리 구현으로 바꾼다. */
interface ViewReader {
    /** DB의 보기 id들. 이름·종류는 [view]로 읽는다. */
    suspend fun viewIds(dataSourceId: String): List<String>

    /** 보기 설정. 조회할 수 없으면(400·404) [NotionException]. */
    suspend fun view(viewId: String): ViewDto

    /**
     * 보기와 같은 행을 값과 함께 받는다. 보기 쿼리(`/views/{id}/queries`)는 값을 주지 않아
     * 보기의 필터·빠른 필터·정렬을 데이터 소스 쿼리에 그대로 넣는다(9-A).
     */
    suspend fun rows(dataSourceId: String, view: ViewDto, limit: Int = VIEW_ROW_LIMIT): List<PageDto>

    /** [rows]에 '더 있음' 표시를 붙인 것. 구현하지 않은 읽기 구현은 더 없다고 본다. */
    suspend fun viewRows(dataSourceId: String, view: ViewDto, limit: Int = VIEW_ROW_LIMIT): ViewRows =
        ViewRows(rows(dataSourceId, view, limit), hasMore = false)

    /**
     * 노션이 이 보기에 넣은 페이지 id를 보기의 순서대로 앞에서 [limit]개까지 받는다(보기 쿼리, 값은 없다). 앱은 값을 데이터 소스 쿼리로 받으므로
     * 이것은 진단 '노션과 비교'가 위젯 행과 맞는지 대조할 때만 쓴다. 구현하지 않은 읽기 전용 구현은 빈 결과를 돌려준다.
     */
    suspend fun viewQueryIds(viewId: String, limit: Int = VIEW_ROW_LIMIT): ViewQueryIds = ViewQueryIds(emptyList(), complete = true)

    /**
     * 위젯에서 값을 되쓸 때 필요한 속성 스키마(종류·선택지). 기본 구현은 빈 스키마라, 이것을 구현하지 않은 읽기 전용 구현은
     * 쓰기 정보 없이 행만 받는다.
     */
    suspend fun schema(dataSourceId: String): DataSourceDto = DataSourceDto(id = dataSourceId)
}

/** 보기 쿼리 결과. [complete]가 false면 노션이 쿼리당 한도(1만 건)에서 잘랐다(`request_status: incomplete`). */
data class ViewQueryIds(val ids: List<String>, val complete: Boolean)

/** 보기 필터와 빠른 필터를 `and`로 묶는다. 둘 다 없으면 null. */
fun viewFilter(view: ViewDto): JsonObject? {
    val quick = view.quickFilters.orEmpty().map { (propertyId, condition) ->
        buildJsonObject {
            put("property", propertyId)
            (condition as? JsonObject)?.forEach { (type, body) -> put(type, body) }
        }
    }
    val all = listOfNotNull(view.filter) + quick
    return when (all.size) {
        0 -> null
        1 -> all.single()
        else -> buildJsonObject { put("and", JsonArray(all)) }
    }
}
