package com.ysdoing.notioncal

import com.ysdoing.notioncal.core.data.db.DataSourceDao
import com.ysdoing.notioncal.core.notion.NotionClient
import com.ysdoing.notioncal.core.notion.NotionException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 값을 그대로 둬도 되는 열거형 키(내용이 아니라 모양을 나타낸다). */
private val KEEP_STRING_KEYS = setOf("object", "type", "direction", "timestamp", "format", "status")

/**
 * VW 9-A 시험: 노션 보기 API 응답의 모양만 남긴다. 글자 값은 "S"로 가리고(제목·이름·ID 보호),
 * 숫자·불리언은 그대로 둔다. 시험 픽스처와 DTO 설계에 쓰고 토큰·내용은 남기지 않는다.
 */
internal fun shapeOf(element: JsonElement, key: String? = null): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (k, v) -> shapeOf(v, k) })
    is JsonArray -> JsonArray(element.map { shapeOf(it, key) })
    is JsonPrimitive ->
        if (element.isString && key !in KEEP_STRING_KEYS) JsonPrimitive("S") else element
    JsonNull -> element
}

/** 등록된 DB 최대 [MAX_SOURCES]개, DB마다 보기 최대 [MAX_VIEWS]개를 받아 모양과 '보기 쿼리 = 데이터 소스 쿼리(보기의 필터·정렬)' 일치 여부를 돌려준다. */
internal suspend fun probeViews(client: NotionClient, sources: DataSourceDao): Pair<JsonObject, String> {
    val started = System.currentTimeMillis()
    val probes = sources.enabled().take(MAX_SOURCES).map { source ->
        val list = client.viewsRaw(source.dataSourceId)
        val ids = list["results"]?.jsonArray.orEmpty().mapNotNull { (it.jsonObject["id"] as? JsonPrimitive)?.contentOrNull }
        buildJsonObject {
            put("sourceKind", source.kind)
            put("views", JsonArray(ids.take(MAX_VIEWS).map { probeView(client, source.dataSourceId, it) }))
        }
    }
    val millis = System.currentTimeMillis() - started
    return buildJsonObject { put("probes", JsonArray(probes)) } to "${probes.size}개 DB, ${millis}ms"
}

private suspend fun probeView(client: NotionClient, dataSourceId: String, viewId: String): JsonObject {
    val view = attempt { client.viewRaw(viewId) }.getOrElse { return buildJsonObject { put("viewError", errorName(it)) } }
    val viewIds = attempt { client.viewQueryRaw(viewId).resultIds() }
    val body = buildJsonObject {
        viewFilter(view)?.let { put("filter", it) }
        view["sorts"]?.takeIf { it !is JsonNull }?.let { put("sorts", it) }
        put("page_size", MAX_ROWS)
    }
    val rows = attempt { client.dataSourceQueryRaw(dataSourceId, body) }
    val a = viewIds.getOrNull().orEmpty()
    val b = rows.getOrNull()?.resultIds().orEmpty()
    return buildJsonObject {
        put("view", shapeOf(view))
        put("viewQueryRows", a.size)
        put("viewQueryError", viewIds.exceptionOrNull()?.let { errorName(it) })
        put("dataSourceRows", b.size)
        put("dataSourceError", rows.exceptionOrNull()?.let { errorName(it) })
        put("sameFirstRows", a.zip(b).takeWhile { (x, y) -> x == y }.size)
        put("sameSet", a.take(b.size).toSet() == b.take(a.size).toSet())
        put("firstRowShape", shapeOf(rows.getOrNull()?.get("results")?.jsonArray?.firstOrNull() ?: JsonNull))
    }
}

/** 보기의 필터에 빠른 필터({속성 id: {타입: 조건}})를 `and`로 묶는다. 보기 쿼리는 둘을 모두 적용한다. */
internal fun viewFilter(view: JsonObject): JsonElement? {
    val base = view["filter"]?.takeIf { it !is JsonNull }
    val quick = (view["quick_filters"] as? JsonObject).orEmpty().map { (id, cond) ->
        buildJsonObject {
            put("property", id)
            (cond as? JsonObject)?.forEach { (type, body) -> put(type, body) }
        }
    }
    val all = listOfNotNull(base) + quick
    return when (all.size) {
        0 -> null
        1 -> all.single()
        else -> buildJsonObject { put("and", JsonArray(all)) }
    }
}

/** 한 보기에서 실패해도 다른 보기 시험은 이어가도록 노션 오류만 결과로 돌려준다. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try { Result.success(block()) } catch (e: NotionException) { Result.failure(e) }

private fun errorName(e: Throwable): String = (e as? NotionException)?.error?.let { it::class.simpleName } ?: e::class.simpleName.orEmpty()

private fun JsonObject.resultIds(): List<String> =
    this["results"]?.jsonArray.orEmpty().mapNotNull { (it.jsonObject["id"] as? JsonPrimitive)?.contentOrNull }

private const val MAX_SOURCES = 3
private const val MAX_VIEWS = 5
private const val MAX_ROWS = 100
