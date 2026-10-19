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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 메모리 노션(읽기·쓰기). data source마다 따로 둔다.
 * - [CALENDAR]: 📅 Calendar 구조(Name·Date·완료 checkbox·Ctgr select + EX-07 추가 속성)
 * - [HABIT_LOG]: ✅ 습관 기록(이름·날짜·습관 relation), [HABITS]: 🌱 습관(이름·이모지·주 목표·진행 중·시작일·순서)
 * 실패 주입: [networkDown], [createTimesOutAfterCreating], [rejectNext], [afterCreate].
 */
class FakeNotionStore(private val now: () -> Instant) : NotionReader, NotionWriter {
    data class Page(
        val id: String,
        var title: String,
        var start: String?,
        var end: String? = null,
        var done: Boolean = false,
        var optionId: String? = null,
        /** EX-07 추가 속성: Pri.(select) 옵션 ID, Tags(multi_select) 옵션 ID들, Cost(number), URL(url). */
        var pri: String? = null,
        var tags: List<String> = emptyList(),
        var cost: Double? = null,
        var link: String? = null,
        var inTrash: Boolean = false,
        val createdTime: Instant,
        var lastEdited: Instant,
        val dataSourceId: String = CALENDAR,
        /** ✅ 습관 기록의 '습관' relation(🌱 습관 page ID). */
        var habit: String? = null,
    )

    /** 🌱 습관 1행. 빈 값은 null. */
    data class Habit(
        val id: String,
        var name: String,
        var emoji: String? = null,
        var target: Double? = null,
        var active: Boolean? = true,
        var start: String? = null,
        var order: Double? = null,
        var inTrash: Boolean = false,
    )

    val pages = linkedMapOf<String, Page>()
    val habits = linkedMapOf<String, Habit>()

    /** 노션에 실제로 보낸 쓰기 순서(오프라인 10건 순서 검증용). */
    val writeLog = mutableListOf<String>()
    var networkDown = false
    var createTimesOutAfterCreating = false
    var rejectNext = false

    /** 생성 요청이 노션에 반영된 뒤, 응답이 돌아가기 전에 실행한다(전송 중 해제 경쟁 재현). 한 번 쓰면 지운다. */
    var afterCreate: (suspend (PageDto) -> Unit)? = null

    private val options = mapOf("opt-event" to ("Event" to "yellow"), "opt-learn" to ("Learn" to "blue"), "opt-habit" to ("Habit" to "red"))

    val schema: DataSourceDto = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"$CALENDAR","properties":{
            "Name":{"id":"title","name":"Name","type":"title"},
            "Date":{"id":"dAtE","name":"Date","type":"date"},
            "완료":{"id":"dOnE","name":"완료","type":"checkbox"},
            "Ctgr":{"id":"cTgR","name":"Ctgr","type":"select","select":{"options":[
              {"id":"opt-event","name":"Event","color":"yellow"},
              {"id":"opt-learn","name":"Learn","color":"blue"},
              {"id":"opt-habit","name":"Habit","color":"red"}]}},
            "Pri.":{"id":"pRi","name":"Pri.","type":"select","select":{"options":[{"id":"pri-1","name":"1st","color":"red"}]}},
            "Tags":{"id":"tAg","name":"Tags","type":"multi_select","multi_select":{"options":[
              {"id":"t-home","name":"집","color":"blue"},{"id":"t-work","name":"회사","color":"green"}]}},
            "Cost":{"id":"cOs","name":"Cost","type":"number"},
            "URL":{"id":"uRl","name":"URL","type":"url"}}}""",
    )

    var logSchema: DataSourceDto = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"$HABIT_LOG","properties":{
            "이름":{"id":"title","name":"이름","type":"title"},
            "날짜":{"id":"nAlJ","name":"날짜","type":"date"},
            "습관":{"id":"rEl","name":"습관","type":"relation","relation":{"data_source_id":"$HABITS","database_id":"db-habits"}}}}""",
    )

    var habitSchema: DataSourceDto = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"$HABITS","properties":{
            "이름":{"id":"title","name":"이름","type":"title"},
            "이모지":{"id":"eMoJ","name":"이모지","type":"rich_text"},
            "주 목표":{"id":"gOaL","name":"주 목표","type":"number"},
            "진행 중":{"id":"aCtV","name":"진행 중","type":"checkbox"},
            "시작일":{"id":"sTrT","name":"시작일","type":"date"},
            "순서":{"id":"oRdR","name":"순서","type":"number"},
            "기록":{"id":"gIrO","name":"기록","type":"relation","relation":{"data_source_id":"$HABIT_LOG","database_id":"db-log"}}}}""",
    )

    fun seed(
        id: String,
        title: String,
        start: String,
        optionId: String? = null,
        editedAt: Instant = Instant.parse("2026-10-01T00:00:00Z"),
        dataSourceId: String = CALENDAR,
        habit: String? = null,
    ) {
        pages[id] = Page(id, title, start, optionId = optionId, createdTime = editedAt, lastEdited = editedAt, dataSourceId = dataSourceId, habit = habit)
    }

    fun seedHabit(habit: Habit) {
        habits[habit.id] = habit
    }

    /** ✅ 습관 기록에 남은(휴지통 아닌) 기록: (습관 ID, 날짜). */
    fun liveLogs(): List<Pair<String?, String?>> =
        pages.values.filter { it.dataSourceId == HABIT_LOG && !it.inTrash }.map { it.habit to it.start }

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
        return when (dataSourceId) {
            HABIT_LOG -> logSchema
            HABITS -> habitSchema
            else -> schema
        }
    }

    override suspend fun pageOrNull(pageId: String): PageDto? {
        if (networkDown) throw NotionException(NotionError.Network)
        return pages[pageId]?.toDto() ?: habits[pageId]?.toDto()
    }

    /** 마지막 queryAll 필터(날짜 범위 확인용). */
    val queries = mutableListOf<Pair<String, JsonObject?>>()

    override suspend fun queryAll(dataSourceId: String, filter: JsonObject?): QueryResult {
        if (networkDown) throw NotionException(NotionError.Network)
        queries += dataSourceId to filter
        if (dataSourceId == HABITS) {
            require(filter == null) { "🌱 습관은 필터 없이 전체 조회한다" }
            return QueryResult(habits.values.filter { !it.inTrash }.map { it.toDto() }, complete = true)
        }
        val live = pages.values.filter { !it.inTrash && it.dataSourceId == dataSourceId }
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
        val page = Page(id, "", null, createdTime = now(), lastEdited = now(), dataSourceId = dataSourceId)
        page.applyProperties(properties)
        pages[id] = page
        writeLog += "CREATE ${page.title}"
        if (createTimesOutAfterCreating) {
            createTimesOutAfterCreating = false
            throw NotionException(NotionError.Network) // 서버에는 만들어졌지만 응답을 못 받음
        }
        val dto = page.toDto()
        afterCreate?.let { hook ->
            afterCreate = null
            hook(dto)
        }
        return dto
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
        if (dataSourceId == HABIT_LOG) {
            props["이름"]?.jsonObject?.get("title")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonObject?.get("content")
                ?.jsonPrimitive?.content?.let { title = it }
            props["날짜"]?.jsonObject?.get("date")?.jsonObject?.let { d -> start = d["start"]?.jsonPrimitive?.contentOrNull }
            props["습관"]?.jsonObject?.get("relation")?.jsonArray?.let { arr -> habit = arr.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content }
            return
        }
        props["Name"]?.jsonObject?.get("title")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonObject?.get("content")
            ?.jsonPrimitive?.content?.let { title = it }
        props["Date"]?.jsonObject?.get("date")?.jsonObject?.let { d ->
            start = d["start"]?.jsonPrimitive?.contentOrNull
            end = (d["end"] as? JsonPrimitive)?.contentOrNull
        }
        props["완료"]?.jsonObject?.get("checkbox")?.jsonPrimitive?.booleanOrNull?.let { done = it }
        props["Ctgr"]?.jsonObject?.let { c ->
            val select = c["select"]
            optionId = if (select == null || select is JsonNull) null else select.jsonObject["id"]?.jsonPrimitive?.content
        }
        props["Pri."]?.jsonObject?.let { c ->
            val select = c["select"]
            pri = if (select == null || select is JsonNull) null else select.jsonObject["id"]?.jsonPrimitive?.content
        }
        props["Tags"]?.jsonObject?.get("multi_select")?.jsonArray?.let { arr -> tags = arr.map { it.jsonObject["id"]!!.jsonPrimitive.content } }
        props["Cost"]?.jsonObject?.let { c -> cost = (c["number"] as? JsonPrimitive)?.contentOrNull?.toDouble() }
        props["URL"]?.jsonObject?.let { c -> link = (c["url"] as? JsonPrimitive)?.contentOrNull }
    }

    private fun str(value: String) = NotionJson.encodeToString(kotlinx.serialization.serializer<String>(), value)

    private fun Page.toDto(): PageDto {
        val date = start?.let { """{"start":"$it","end":${end?.let { e -> "\"$e\"" } ?: "null"}}""" } ?: "null"
        val head = """"id":"$id","url":"https://www.notion.so/$id","created_time":"$createdTime","last_edited_time":"$lastEdited","in_trash":$inTrash"""
        if (dataSourceId == HABIT_LOG) {
            return NotionJson.decodeFromString(
                PageDto.serializer(),
                """{$head,"properties":{
                    "이름":{"id":"title","type":"title","title":[{"plain_text":${str(title)}}]},
                    "날짜":{"id":"nAlJ","type":"date","date":$date},
                    "습관":{"id":"rEl","type":"relation","relation":[${habit?.let { """{"id":"$it"}""" } ?: ""}]}}}""",
            )
        }
        val select = optionId?.let { id -> options[id]?.let { (name, color) -> """{"id":"$id","name":"$name","color":"$color"}""" } } ?: "null"
        return NotionJson.decodeFromString(
            PageDto.serializer(),
            """{$head,"properties":{
                "Name":{"id":"title","type":"title","title":[{"plain_text":${str(title)}}]},
                "Date":{"id":"dAtE","type":"date","date":$date},
                "완료":{"id":"dOnE","type":"checkbox","checkbox":$done},
                "Ctgr":{"id":"cTgR","type":"select","select":$select},
                "Pri.":{"id":"pRi","type":"select","select":${pri?.let { """{"id":"$it","name":"1st","color":"red"}""" } ?: "null"}},
                "Tags":{"id":"tAg","type":"multi_select","multi_select":[${tags.joinToString(",") { """{"id":"$it","name":"$it"}""" }}]},
                "Cost":{"id":"cOs","type":"number","number":${cost ?: "null"}},
                "URL":{"id":"uRl","type":"url","url":${link?.let { "\"$it\"" } ?: "null"}}}}""",
        )
    }

    private fun Habit.toDto(): PageDto = NotionJson.decodeFromString(
        PageDto.serializer(),
        """{"id":"$id","url":"https://www.notion.so/$id","last_edited_time":"2026-10-19T00:00:00Z","in_trash":$inTrash,"properties":{
            "이름":{"id":"title","type":"title","title":[{"plain_text":${str(name)}}]},
            "이모지":{"id":"eMoJ","type":"rich_text","rich_text":[${emoji?.let { """{"plain_text":${str(it)}}""" } ?: ""}]},
            "주 목표":{"id":"gOaL","type":"number","number":${target ?: "null"}},
            "진행 중":{"id":"aCtV","type":"checkbox","checkbox":${active ?: false}},
            "시작일":{"id":"sTrT","type":"date","date":${start?.let { """{"start":"$it"}""" } ?: "null"}},
            "순서":{"id":"oRdR","type":"number","number":${order ?: "null"}}}}""",
    )

    companion object {
        const val CALENDAR = "ds"
        const val HABIT_LOG = "log"
        const val HABITS = "habits"
    }
}
