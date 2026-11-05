package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/** select·status의 선택지 하나. [group]은 status의 그룹 이름(할 일·진행 중·완료)이다. */
@Serializable
data class ViewChoice(
    val id: String,
    val name: String,
    val group: String? = null,
    /** 노션 옵션 색 이름(`blue`·`default` 등). 칩 색에 쓴다. 모르면 null. */
    val color: String? = null,
)

/** 보기에 보이는 속성 하나의 쓰기 정보. 위젯은 이것으로 어떤 탭을 어떤 값으로 바꿀 수 있는지 안다. */
@Serializable
data class ViewColumn(
    val id: String,
    val name: String,
    val type: String,
    val options: List<ViewChoice> = emptyList(),
    /** M5b 책 위젯용 표시([ROLE_PAGES_READ]·[ROLE_PAGES_TOTAL]). 보기에서 숨긴 속성도 이 표시로 쓰기 정보에 들어온다. 보통은 빈 글자. */
    val role: String = "",
) {
    /**
     * 위젯에서 눌렀을 때 순환하지 않고 작은 선택 시트를 여는 열(IX-02): 선택지가 [CHOOSER_MIN_OPTIONS]개 이상인 select.
     * 상태는 옵션이 많아도 그룹 순서로 순환한다(할 일 → 진행 중 → 완료가 주된 쓰임이다).
     */
    val opensChooser: Boolean get() = type == "select" && options.size >= CHOOSER_MIN_OPTIONS

    /** 앱에서 바꿀 수 있는 종류([WRITABLE_TYPES]). 나머지는 읽기만 한다. */
    val writable: Boolean get() = type in WRITABLE_TYPES

    /**
     * 위젯 칸을 한 번 눌러 바로 바꿀 수 있는가. 체크박스, 그리고 선택지가 둘 이상인 상태·선택이다(하나뿐이면 눌러도 같은 값이다).
     * 숫자는 눌러서 바꾸는 방식이 달라 따로 다룬다.
     */
    val tappable: Boolean get() = type == "checkbox" || (type in OPTION_TYPES && options.size >= 2)

    /**
     * 표시용 글자. [value]는 [ViewJson.typed]의 값이다: 체크 bool, 선택지 ID, 숫자, 글자(제목·텍스트·URL·이메일·전화),
     * 선택지 ID 목록(다중 선택, 새 옵션은 `{"name":…}`), 날짜 `{"start":…,"end":…}`. 노션 읽기 표기(displayValue)와 같다.
     */
    fun display(value: JsonElement?): String = when (type) {
        "checkbox" -> if ((value as? JsonPrimitive)?.booleanOrNull == true) "✓" else ""
        "select", "status" -> options.firstOrNull { it.id == (value as? JsonPrimitive)?.contentOrNull }?.name.orEmpty()
        "number" -> (value as? JsonPrimitive)?.doubleOrNull?.let { n -> if (n % 1.0 == 0.0 && abs(n) < MAX_PLAIN) n.toLong().toString() else n.toString() }.orEmpty()
        "title", "rich_text", "url", "email", "phone_number" -> (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        "multi_select" -> (value as? JsonArray).orEmpty().joinToString(", ") { optionName(it) }
        "date" -> (value as? JsonObject)?.let { dateText(it) }.orEmpty()
        else -> ""
    }

    private fun optionName(item: JsonElement): String =
        options.firstOrNull { it.id == (item as? JsonPrimitive)?.contentOrNull }?.name
            ?: ((item as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun dateText(date: JsonObject): String {
        val start = (date["start"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val end = (date["end"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return if (end.isEmpty()) start else "$start~$end"
    }

    /** 옵션 목록을 아는 열이면 그 안의 ID만 쓴다(없는 ID는 노션이 거부한다). 목록을 모르면(비어 있으면) 막지 않는다. */
    private fun knownOption(id: String) = options.isEmpty() || options.any { it.id == id }

    /** [value]가 이 열에 쓸 수 있는 모양인가(잘못된 모양을 큐에 넣지 않는다). 비우기는 JsonNull(제목·상태는 비울 수 없다). */
    fun accepts(value: JsonElement): Boolean {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }
        return when (type) {
            "checkbox" -> (value as? JsonPrimitive)?.booleanOrNull != null
            "select" -> value is JsonNull || (text != null && knownOption(text.content))
            "status" -> text != null && knownOption(text.content)
            "url", "email", "phone_number" -> value is JsonNull || text != null
            "title", "rich_text" -> text != null
            "number" -> value is JsonNull || (value as? JsonPrimitive)?.doubleOrNull != null
            "multi_select" -> value is JsonArray && value.all { item -> item is JsonObject || ((item as? JsonPrimitive)?.takeIf { it.isString }?.let { knownOption(it.content) } == true) }
            "date" -> value is JsonNull || ((value as? JsonObject)?.get("start") as? JsonPrimitive)?.isString == true
            else -> false
        }
    }

    /** [currentId] 다음 선택지(끝이면 처음). 눌러서 상태를 순환시킬 때 쓴다. 선택지가 없으면 null. */
    fun nextOption(currentId: String?): ViewChoice? {
        if (options.isEmpty()) return null
        val index = options.indexOfFirst { it.id == currentId }
        return options[(index + 1) % options.size]
    }

    companion object {
        /** select가 이 개수 이상이면 위젯 탭이 선택 시트를 연다([opensChooser]). */
        const val CHOOSER_MIN_OPTIONS = 4

        const val ROLE_PAGES_READ = "pagesRead"
        const val ROLE_PAGES_TOTAL = "pagesTotal"

        /** Phase 11c 보기 필터가 가리키는 속성. 보기에 보이지 않아도 행의 값을 남겨 폰에서 필터를 판정한다(IX-06). */
        const val ROLE_FILTER = "filter"

        /** Phase 11a 보드의 `group_by` 속성. 카드에 보이지 않아도 열로 나누려고 쓰기 정보에 담는다. */
        const val ROLE_GROUP = "group"

        /** Phase 12a 캘린더 보기의 `date_property_id` 속성. 보기에서 숨겨도 월 격자가 날짜를 읽도록, 그리고 새 항목이 같은 열에 날짜를 쓰도록 쓰기 정보에 담는다. */
        const val ROLE_DATE = "date"

        /** Phase 12b 차트 보기의 축·값 속성(`x_axis`·`y_axis`·`value`). 보기에서 숨겨도 폰이 묶고 집계하도록 쓰기 정보에 담는다. 새 항목 양식에는 나오지 않는다. */
        const val ROLE_CHART = "chart"

        /**
         * 새 항목의 날짜가 들어가고 월 격자·하루 한 줄 판정이 읽는 날짜 열: 캘린더의 [ROLE_DATE] 열이 먼저, 없으면 첫 날짜 열.
         * (옛 스냅샷은 역할이 없어 첫 날짜 열로 폴백하고, 다음 동기화 뒤 역할이 채워진다.)
         */
        fun dateColumnOf(columns: List<ViewColumn>): ViewColumn? =
            columns.firstOrNull { it.type == "date" && it.role == ROLE_DATE } ?: columns.firstOrNull { it.type == "date" }

        /** 앱에서 쓸 수 있는 속성 유형(PRD §5.8 MVP 편집: 제목·텍스트·숫자·선택·다중 선택·상태·날짜·체크박스·URL·이메일·전화). */
        val WRITABLE_TYPES = setOf("title", "rich_text", "number", "select", "multi_select", "status", "date", "checkbox", "url", "email", "phone_number")
        private val OPTION_TYPES = setOf("select", "status")

        /** columnInfo에 담는 종류. 제목·날짜는 새 항목을 만들 때도 쓴다. */
        val INFO_TYPES = WRITABLE_TYPES

        /** 읽기 전용이라도 보이는 열이면 쓰기 정보에 담아 편집 시트가 잠금 줄로 보여 준다(ED-02). 이 종류는 [writable]이 false다. */
        val DISPLAY_ONLY_TYPES = setOf("formula", "rollup", "created_time", "created_by", "last_edited_time", "last_edited_by", "unique_id", "relation", "people", "files")

        /** 이보다 큰 정수는 Long으로 바꾸면 값이 틀어지므로 그대로 글자로 쓴다. */
        private const val MAX_PLAIN = 1e15
    }
}

/**
 * 새 항목 만들기 값(제목, 선택 날짜 yyyy-MM-dd와 그 날짜를 넣을 속성 ID, Phase 11c의 값 묶음 [props]: 속성 ID → [ViewColumn.display]가 적은 모양의 값).
 * view_writes.value에 JSON으로 담는다. [props]는 기본이 빈 맵이라 이전 버전이 쌓은 쓰기도 읽힌다.
 */
@Serializable
data class ViewCreate(val title: String, val date: String? = null, val dateProp: String? = null, val props: Map<String, JsonElement> = emptyMap())

/** view_snapshots.columnInfo·view_rows.typed의 JSON 읽기·쓰기. 깨진 글자는 빈 값으로 읽는다(캐시라 다시 받으면 된다). */
object ViewJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val columnList = ListSerializer(ViewColumn.serializer())
    private val typedMap = MapSerializer(String.serializer(), JsonElement.serializer())

    fun columns(text: String): List<ViewColumn> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(columnList, text) }.getOrDefault(emptyList())

    fun encodeColumns(columns: List<ViewColumn>): String = if (columns.isEmpty()) "" else json.encodeToString(columnList, columns)

    fun typed(text: String): Map<String, JsonElement> =
        if (text.isBlank()) emptyMap() else runCatching { json.decodeFromString(typedMap, text) }.getOrDefault(emptyMap())

    fun encodeTyped(values: Map<String, JsonElement>): String = if (values.isEmpty()) "" else json.encodeToString(typedMap, values)

    /** JSON 원시값 한 개의 글자(쓰기 큐 value 열). 비어 있으면 null. */
    fun valueText(value: JsonElement?): String? = value?.takeIf { it !is JsonNull }?.toString()

    fun encodeCreate(create: ViewCreate): String = json.encodeToString(ViewCreate.serializer(), create)

    /** 깨졌으면 null(보내지 않고 버린다). */
    fun parseCreate(text: String?): ViewCreate? =
        text?.let { runCatching { json.decodeFromString(ViewCreate.serializer(), it) }.getOrNull() }

    /** 쓰기 큐 value 열을 값으로 읽는다. 비어 있으면(null) 비우는 값 JsonNull, 깨진 글자면 null(노션에 지우는 쓰기를 보내지 않게 구분한다). */
    fun parseValue(text: String?): JsonElement? =
        if (text == null) JsonNull else runCatching { json.parseToJsonElement(text) }.getOrNull()
}
