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
import kotlin.math.roundToLong

/** LD-01 거래내역 속성(모두 속성 ID). 금액·타입 말고는 비울 수 있다. */
data class LedgerMapping(
    /** number(원) */
    val amountPropId: String?,
    /** select(지출·수입·이체) */
    val flowPropId: String?,
    /** select(대분류) */
    val categoryPropId: String? = null,
    /** rich_text(HH:MM) */
    val timePropId: String? = null,
    /** select(결제수단) */
    val paymentPropId: String? = null,
    /** checkbox(예정) */
    val plannedPropId: String? = null,
    /** checkbox(앱입력) */
    val manualPropId: String? = null,
)

/** 거래 한 건의 가계부 값. 금액은 원 단위로 반올림한 Long(부호는 노션 그대로), select는 옵션 이름. */
data class RemoteLedger(
    val amount: Long?,
    val flow: String?,
    val category: String?,
    val time: String?,
    val payment: String?,
    val planned: Boolean,
    val manual: Boolean,
)

/** 가계부 쓰기(LD-07·LD-09). null인 필드는 보내지 않는다. [category]가 빈 문자열이면 select를 비운다. */
data class LedgerWrite(
    val amount: Long? = null,
    val flow: String? = null,
    val category: String? = null,
    val time: String? = null,
    val planned: Boolean? = null,
    val manual: Boolean? = null,
)

/** LD-05 예산 DB 속성. */
data class BudgetMapping(
    val titlePropId: String,
    /** select(연·분기·월·주) */
    val periodTypePropId: String?,
    /** rich_text(2026-10, 2026-W43) */
    val periodValuePropId: String?,
    /** select */
    val groupPropId: String?,
    /** number */
    val amountPropId: String?,
)

data class RemoteBudget(val pageId: String, val periodType: String, val periodKey: String, val group: String?, val amount: Long?)

/** 카테고리 마스터 속성. 제목이 대분류다. */
data class CategoryMapping(val titlePropId: String, val groupPropId: String?)

data class RemoteCategory(val pageId: String, val category: String, val group: String?)

/** 페이지 속성 읽기 도우미(가계부·예산·카테고리 공용). */
internal class PageProps(page: PageDto) {
    private val props = page.properties.values.mapNotNull { it as? JsonObject }

    fun prop(id: String?): JsonObject? = id?.let { wanted -> props.firstOrNull { it.text("id") == wanted } }

    fun plain(id: String?, key: String): String? = (prop(id)?.get(key) as? JsonArray)
        ?.joinToString("") { (it as? JsonObject)?.text("plain_text").orEmpty() }

    fun selectName(id: String?): String? = (prop(id)?.get("select") as? JsonObject)?.text("name")?.trim()?.ifEmpty { null }

    fun number(id: String?): Double? = prop(id)?.get("number")?.primitive()?.doubleOrNull

    fun checkbox(id: String?): Boolean? = prop(id)?.get("checkbox")?.primitive()?.booleanOrNull

    /** number를 원 단위로 반올림. */
    fun won(id: String?): Long? = number(id)?.takeIf { !it.isNaN() && !it.isInfinite() }?.roundToLong()

    private fun JsonObject.text(key: String): String? = get(key)?.primitive()?.contentOrNull

    private fun JsonElement.primitive() = if (this is JsonNull || this is JsonObject || this is JsonArray) null else jsonPrimitive
}

/** 거래내역 페이지의 가계부 값([NotionPageMapper]가 부른다). */
internal fun PageProps.ledger(mapping: LedgerMapping) = RemoteLedger(
    amount = won(mapping.amountPropId),
    flow = selectName(mapping.flowPropId),
    category = selectName(mapping.categoryPropId),
    time = plain(mapping.timePropId, "rich_text")?.trim()?.ifEmpty { null },
    payment = selectName(mapping.paymentPropId),
    planned = checkbox(mapping.plannedPropId) ?: false,
    manual = checkbox(mapping.manualPropId) ?: false,
)

/** LD-05 예산 행 읽기. 기간유형·기간값이 없으면 쓸 수 없어 null, 휴지통 행도 null. */
class BudgetPageMapper(private val mapping: BudgetMapping) {
    fun map(page: PageDto): RemoteBudget? {
        if (page.inTrash) return null
        val p = PageProps(page)
        val type = p.selectName(mapping.periodTypePropId) ?: return null
        val key = p.plain(mapping.periodValuePropId, "rich_text")?.trim()?.ifEmpty { null } ?: return null
        return RemoteBudget(page.id, type, key, p.selectName(mapping.groupPropId), p.won(mapping.amountPropId))
    }
}

/** 카테고리 마스터 행 읽기. 대분류(제목)가 비면 null. */
class CategoryPageMapper(private val mapping: CategoryMapping) {
    fun map(page: PageDto): RemoteCategory? {
        if (page.inTrash) return null
        val p = PageProps(page)
        val name = p.plain(mapping.titlePropId, "title")?.trim()?.ifEmpty { null } ?: return null
        return RemoteCategory(page.id, name, p.selectName(mapping.groupPropId))
    }
}
