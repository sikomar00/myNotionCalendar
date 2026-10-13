package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.NotionReader
import com.ysdoing.notioncal.core.notion.NotionWriter
import com.ysdoing.notioncal.core.notion.QueryResult
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 메모리 노션(읽기·쓰기). 📅 Calendar 구조(Name·Date·완료 checkbox·Ctgr select)를 흉내 낸다.
 * 실패 주입: [networkDown], [createTimesOutAfterCreating], [rejectNext].
 */
class FakeNotionStore(private val now: () -> Instant) : NotionReader, NotionWriter {
    data class Page(
        val id: String,
        var title: String,
        var start: String?,
        var end: String? = null,
        var done: Boolean = false,
        var optionId: String? = null,
        var inTrash: Boolean = false,
        val createdTime: Instant,
        var lastEdited: Instant,
    )

    val pages = linkedMapOf<String, Page>()

    /** 노션에 실제로 보낸 쓰기 순서(오프라인 10건 순서 검증용). */
    val writeLog = mutableListOf<String>()
    var networkDown = false
    var createTimesOutAfterCreating = false
    var rejectNext = false

    private val options = mapOf("opt-event" to ("Event" to "yellow"), "opt-learn" to ("Learn" to "blue"), "opt-habit" to ("Habit" to "red"))

    val schema: DataSourceDto = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{
            "Name":{"id":"title","name":"Name","type":"title"},
            "Date":{"id":"dAtE","name":"Date","type":"date"},
            "완료":{"id":"dOnE","name":"완료","type":"checkbox"},
            "Ctgr":{"id":"cTgR","name":"Ctgr","type":"select","select":{"options":[
              {"id":"opt-event","name":"Event","color":"yellow"},
              {"id":"opt-learn","name":"Learn","color":"blue"},
              {"id":"opt-habit","name":"Habit","color":"red"}]}}}}""",
    )

    fun seed(id: String, title: String, start: String, optionId: String? = null, editedAt: Instant = Instant.parse("2026-10-01T00:00:00Z")) {
        pages[id] = Page(id, title, start, optionId = optionId, createdTime = editedAt, lastEdited = editedAt)
    }

    /**
     * 노션 앱(또는 자동화)에서 고친 것처럼 바꾼다. [sameMinute]이면 last_edited_time을 그대로 둔다
     * (노션은 분 단위로 내림하므로 같은 분 안의 수정은 시각이 같아 보인다).
     */
    fun editRemotely(id: String, sameMinute: Boolean = false, block: Page.() -> Unit) {
        val page = pages.getValue(id).apply(block)
        if (!sameMinute) page.lastEdited = now()
    }

    private fun guard() {
        if (networkDown) throw NotionException(NotionError.Network)
        if (rejectNext) {
            rejectNext = false
            throw NotionException(NotionError.Validation("validation_error"))
        }
    }

    override suspend fun dataSource(dataSourceId: String): DataSourceDto {
        if (networkDown) throw NotionException(NotionError.Network)
        return schema
    }

    override suspend fun pageOrNull(pageId: String): PageDto? {
        if (networkDown) throw NotionException(NotionError.Network)
        return pages[pageId]?.toDto()
    }

    override suspend fun queryAll(dataSourceId: String, filter: JsonObject?): QueryResult {
        if (networkDown) throw NotionException(NotionError.Network)
        val live = pages.values.filter { !it.inTrash }
        val result = when {
            filter?.containsKey("timestamp") == true -> {
                val since = OffsetDateTime.parse(filter["last_edited_time"]!!.jsonObject["on_or_after"]!!.jsonPrimitive.content).toInstant()
                live.filter { !it.lastEdited.isBefore(since) }
            }
            filter?.containsKey("and") == true -> {
                val conds = filter["and"]!!.jsonArray.map { it.jsonObject }
                live.filter { page -> conds.all { matches(page, it) } }
            }
            else -> live
        }
        return QueryResult(result.map { it.toDto() }, complete = true)
    }

    private fun matches(page: Page, cond: JsonObject): Boolean {
        cond["date"]?.jsonObject?.let { d ->
            val start = page.start?.take(10) ?: return false
            d["on_or_after"]?.jsonPrimitive?.content?.let { if (start < it.take(10)) return false }
            d["on_or_before"]?.jsonPrimitive?.content?.let { if (start > it.take(10)) return false }
        }
        cond["title"]?.jsonObject?.get("equals")?.jsonPrimitive?.content?.let { if (page.title != it) return false }
        cond["created_time"]?.jsonObject?.get("on_or_after")?.jsonPrimitive?.content?.let {
            if (page.createdTime.isBefore(OffsetDateTime.parse(it).toInstant())) return false
        }
        return true
    }

    override suspend fun createPage(dataSourceId: String, properties: JsonObject): PageDto {
        guard()
        val id = "page-" + UUID.randomUUID().toString().take(8)
        val page = Page(id, "", null, createdTime = now(), lastEdited = now())
        page.applyProperties(properties)
        pages[id] = page
        writeLog += "CREATE ${page.title}"
        if (createTimesOutAfterCreating) {
            createTimesOutAfterCreating = false
            throw NotionException(NotionError.Network) // 서버에는 만들어졌지만 응답을 못 받음
        }
        return page.toDto()
    }

    override suspend fun updatePage(pageId: String, properties: JsonObject): PageDto {
        guard()
        val page = pages[pageId] ?: throw NotionException(NotionError.NotShared)
        page.applyProperties(properties)
        page.lastEdited = now()
        writeLog += "UPDATE ${page.title} ${properties.keys.sorted()}"
        return page.toDto()
    }

    override suspend fun trashPage(pageId: String): PageDto? {
        guard()
        val page = pages[pageId] ?: return null
        page.inTrash = true
        page.lastEdited = now()
        writeLog += "TRASH ${page.title}"
        return page.toDto()
    }

    /** 실제 노션처럼 속성 이름 키를 받는다(인코딩된 ID 키는 무시된다). */
    private fun Page.applyProperties(props: JsonObject) {
        props["Name"]?.jsonObject?.get("title")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonObject?.get("content")
            ?.jsonPrimitive?.content?.let { title = it }
        props["Date"]?.jsonObject?.get("date")?.jsonObject?.let { d ->
            start = d["start"]?.jsonPrimitive?.contentOrNull
            end = (d["end"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        }
        props["완료"]?.jsonObject?.get("checkbox")?.jsonPrimitive?.booleanOrNull?.let { done = it }
        props["Ctgr"]?.jsonObject?.let { c ->
            val select = c["select"]
            optionId = if (select == null || select is JsonNull) null else select.jsonObject["id"]?.jsonPrimitive?.content
        }
    }

    private fun Page.toDto(): PageDto {
        val date = start?.let { """{"start":"$it","end":${end?.let { e -> "\"$e\"" } ?: "null"}}""" } ?: "null"
        val select = optionId?.let { id -> options[id]?.let { (name, color) -> """{"id":"$id","name":"$name","color":"$color"}""" } } ?: "null"
        return NotionJson.decodeFromString(
            PageDto.serializer(),
            """{"id":"$id","url":"https://www.notion.so/$id","created_time":"$createdTime","last_edited_time":"$lastEdited",
              "in_trash":$inTrash,"properties":{
                "Name":{"id":"title","type":"title","title":[{"plain_text":${NotionJson.encodeToString(kotlinx.serialization.serializer<String>(), title)}}]},
                "Date":{"id":"dAtE","type":"date","date":$date},
                "완료":{"id":"dOnE","type":"checkbox","checkbox":$done},
                "Ctgr":{"id":"cTgR","type":"select","select":$select}}}""",
        )
    }
}
