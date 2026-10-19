package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.dto.PageDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import java.time.LocalDate

/** HB-01 🌱 습관 DB 매핑. 모두 속성 ID다. 제목 말고는 비워 둘 수 있다(기본값을 쓴다). */
data class HabitMapping(
    val titlePropId: String,
    /** rich_text */
    val emojiPropId: String? = null,
    /** number */
    val weeklyTargetPropId: String? = null,
    /** checkbox */
    val activePropId: String? = null,
    /** date */
    val startDatePropId: String? = null,
    /** number */
    val sortOrderPropId: String? = null,
)

/** 🌱 습관 1행. */
data class RemoteHabit(
    val pageId: String,
    val name: String,
    val emoji: String?,
    /** 1~7(비면 7). */
    val weeklyTarget: Int,
    /** '진행 중'(비면 true). 끈 습관은 앱·위젯에서 숨긴다(HB-04). */
    val active: Boolean,
    val startDate: LocalDate?,
    val sortOrder: Double?,
    val url: String,
)

/**
 * 🌱 습관 행을 읽는다. 날짜 속성이 없어 [NotionPageMapper](일정용)와 따로 둔다. 속성이 없거나 유형이 바뀌어도 기본값을 쓴다.
 * 주 목표는 1~7로 자르고 소수는 반올림한다.
 */
class HabitPageMapper(private val mapping: HabitMapping) {
    /** 휴지통의 습관은 null(없는 것으로 본다). */
    fun map(page: PageDto): RemoteHabit? {
        if (page.inTrash) return null
        val props = page.properties.values.mapNotNull { it as? JsonObject }
        fun prop(id: String?): JsonObject? = id?.let { wanted -> props.firstOrNull { it.str("id") == wanted } }

        val name = (prop(mapping.titlePropId)?.get("title") as? JsonArray).orEmpty()
            .joinToString("") { (it as? JsonObject)?.str("plain_text").orEmpty() }
        val emoji = (prop(mapping.emojiPropId)?.get("rich_text") as? JsonArray).orEmpty()
            .joinToString("") { (it as? JsonObject)?.str("plain_text").orEmpty() }.trim().ifEmpty { null }
        val target = prop(mapping.weeklyTargetPropId)?.get("number")?.primitiveOrNull()?.doubleOrNull
        val active = prop(mapping.activePropId)?.get("checkbox")?.primitiveOrNull()?.booleanOrNull ?: true
        val start = prop(mapping.startDatePropId)?.obj("date")?.let { d -> DateCodec.parse(d.str("start"), null, d.str("time_zone")) }
        val order = prop(mapping.sortOrderPropId)?.get("number")?.primitiveOrNull()?.doubleOrNull
        return RemoteHabit(
            pageId = page.id,
            name = name,
            emoji = emoji,
            weeklyTarget = weeklyTarget(target),
            active = active,
            startDate = start?.let { DateCodec.displayDates(it).first },
            sortOrder = order,
            url = page.url,
        )
    }

    companion object {
        const val DEFAULT_WEEKLY_TARGET = 7

        fun weeklyTarget(value: Double?): Int =
            value?.takeIf { !it.isNaN() }?.roundToInt()?.coerceIn(1, DEFAULT_WEEKLY_TARGET) ?: DEFAULT_WEEKLY_TARGET
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.primitiveOrNull()?.contentOrNull

    private fun JsonElement.primitiveOrNull() = if (this is JsonNull || this is JsonObject || this is JsonArray) null else jsonPrimitive
}
