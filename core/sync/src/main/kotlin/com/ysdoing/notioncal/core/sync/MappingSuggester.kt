package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PropertySchemaDto
import com.ysdoing.notioncal.core.notion.mapping.DoneType
import com.ysdoing.notioncal.core.notion.mapping.NotionPageMapper

/** 등록 화면에서 고를 수 있는 속성 후보. */
data class PropertyOption(val id: String, val name: String, val type: String)

data class MappingCandidates(
    val titles: List<PropertyOption>,
    val dates: List<PropertyOption>,
    /** checkbox·status */
    val done: List<PropertyOption>,
    /** select·status */
    val colors: List<PropertyOption>,
    /** select */
    val sorts: List<PropertyOption>,
    /** relation(HB-01 ✅ 습관 기록의 '습관') */
    val relations: List<PropertyOption> = emptyList(),
)

/** 등록 화면의 선택값. 모두 속성 ID다. */
data class MappingChoice(
    val titlePropId: String,
    val datePropId: String,
    val donePropId: String?,
    val colorPropId: String?,
    val sortPropId: String?,
)

/**
 * NS-02 추천 매핑. PRD §8.4 프리셋은 속성 이름이 맞을 때만 추천 기본값으로 쓰고, ID는 코드에 넣지 않는다.
 */
object MappingSuggester {
    private val donePreset = listOf("완료", "Done", "Completed", "상태", "Status")
    private val colorPreset = listOf("Ctgr", "단계", "유형", "Category", "카테고리")
    private val sortPreset = listOf("Time", "시간대")

    fun candidates(schema: DataSourceDto): MappingCandidates {
        val props = schema.properties.values.map { PropertyOption(it.id, it.name, it.type) }.sortedBy { it.name }
        fun of(vararg types: String) = props.filter { it.type in types }
        return MappingCandidates(
            titles = of("title"),
            dates = of("date"),
            done = of("checkbox", "status"),
            colors = of("select", "status"),
            sorts = of("select"),
            relations = of("relation"),
        )
    }

    /** 제목·날짜 속성이 없으면 null(캘린더로 쓸 수 없는 DB). */
    fun suggest(schema: DataSourceDto): MappingChoice? {
        val c = candidates(schema)
        val title = c.titles.firstOrNull() ?: return null
        val date = c.dates.firstOrNull() ?: return null
        val done = c.done.byPreset(donePreset)
            ?: c.done.firstOrNull { it.type == "checkbox" }
            ?: c.done.firstOrNull()
        val sort = c.sorts.byPreset(sortPreset)
        val color = c.colors.byPreset(colorPreset)
            ?: c.colors.firstOrNull { it.type == "select" && it.id != sort?.id }
        return MappingChoice(title.id, date.id, done?.id, color?.id, sort?.id)
    }

    /** status 완료 쓰기용 옵션 이름: 완료 그룹 첫 옵션, 해제 시 할 일 그룹 첫 옵션(PRD §8.3). */
    fun statusOptions(prop: PropertySchemaDto): Pair<String?, String?> {
        val status = prop.status ?: return null to null
        val names = status.options.associate { it.id to it.name }
        fun firstOf(group: String) = status.groups.firstOrNull { it.name.equals(group, ignoreCase = true) }
            ?.optionIds?.firstOrNull()?.let(names::get)
        return firstOf(NotionPageMapper.COMPLETE_GROUP) to firstOf("To-do")
    }

    fun doneType(prop: PropertySchemaDto?): DoneType? = when (prop?.type) {
        "checkbox" -> DoneType.CHECKBOX
        "status" -> DoneType.STATUS
        else -> null
    }

    private fun List<PropertyOption>.byPreset(names: List<String>): PropertyOption? =
        names.firstNotNullOfOrNull { name -> firstOrNull { it.name.equals(name, ignoreCase = true) } }
}
