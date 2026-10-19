package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.HabitPropIds
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.RelationSchemaDto

/** HB-01 ✅ 습관 기록의 매핑(속성 ID). */
data class HabitLogChoice(val titlePropId: String, val datePropId: String, val linkPropId: String)

/** HB-01 🌱 습관의 매핑(속성 ID). 제목 말고는 비울 수 있다(기본값). */
data class HabitChoice(
    val titlePropId: String,
    val emoji: String?,
    val weeklyTarget: String?,
    val active: String?,
    val startDate: String?,
    val sortOrder: String?,
)

/** 🌱 습관 등록 화면의 속성 후보(유형별). */
data class HabitCandidates(
    val titles: List<PropertyOption>,
    /** rich_text(이모지) */
    val texts: List<PropertyOption>,
    /** number(주 목표·순서) */
    val numbers: List<PropertyOption>,
    /** checkbox(진행 중) */
    val checkboxes: List<PropertyOption>,
    /** date(시작일) */
    val dates: List<PropertyOption>,
)

/** 등록할 data source 하나. */
data class HabitTarget(val dataSourceId: String, val databaseId: String, val name: String)

/** 기록 DB의 relation이 가리키는 🌱 습관을 조회한 결과. */
data class HabitInspection(
    val target: HabitTarget,
    val candidates: HabitCandidates,
    val suggestion: HabitChoice?,
)

sealed interface HabitInspectResult {
    data class Ok(val inspection: HabitInspection) : HabitInspectResult

    /** 고른 속성이 relation이 아니거나 대상 DB를 알 수 없다. */
    data object NoRelationTarget : HabitInspectResult

    /** 🌱 습관을 읽지 못했다. NotShared면 🌱 습관 페이지에 연결을 추가하라고 안내한다. */
    data class Failed(val error: com.ysdoing.notioncal.core.notion.NotionError) : HabitInspectResult
}

/**
 * HB-01 추천 매핑. 속성 이름이 프리셋(PRD §8.4)과 맞을 때만 고르고, 숫자 속성처럼 헷갈리는 것은 추측하지 않는다.
 * 📅 Calendar에도 relation이 있으므로 '습관 기록' 종류는 사용자가 고른다.
 */
object HabitSuggester {
    private val logDatePreset = listOf("날짜", "Date")
    private val linkPreset = listOf("습관", "Habit")
    private val emojiPreset = listOf("이모지", "Emoji", "아이콘", "Icon")
    private val targetPreset = listOf("주 목표", "주목표", "목표", "Weekly target", "Target")
    private val activePreset = listOf("진행 중", "진행중", "Active", "사용")
    private val startPreset = listOf("시작일", "시작", "Start", "Start date")
    private val orderPreset = listOf("순서", "Order", "정렬")

    private fun DataSourceDto.options(type: String) =
        properties.values.filter { it.type == type }.map { PropertyOption(it.id, it.name, it.type) }.sortedBy { it.name }

    /** 제목·날짜·relation이 모두 있어야 기록 DB로 쓸 수 있다. */
    fun suggestLog(schema: DataSourceDto): HabitLogChoice? {
        val title = schema.options("title").firstOrNull() ?: return null
        val dates = schema.options("date")
        val date = dates.byPreset(logDatePreset) ?: dates.firstOrNull() ?: return null
        val relations = schema.options("relation")
        val link = relations.byPreset(linkPreset) ?: relations.firstOrNull() ?: return null
        return HabitLogChoice(title.id, date.id, link.id)
    }

    /** 날짜가 없고 relation이 있다: 🌱 습관 쪽 링크를 넣은 것으로 보고 ✅ 습관 기록 링크를 넣으라고 안내한다. */
    fun looksLikeHabitDatabase(schema: DataSourceDto): Boolean =
        schema.options("date").isEmpty() && schema.options("relation").isNotEmpty()

    fun relationTarget(schema: DataSourceDto, linkPropId: String): RelationSchemaDto? =
        schema.properties.values.firstOrNull { it.id == linkPropId && it.type == "relation" }?.relation?.takeIf { it.dataSourceId != null }

    fun candidates(schema: DataSourceDto) = HabitCandidates(
        titles = schema.options("title"),
        texts = schema.options("rich_text"),
        numbers = schema.options("number"),
        checkboxes = schema.options("checkbox"),
        dates = schema.options("date"),
    )

    fun suggestHabit(schema: DataSourceDto): HabitChoice? {
        val c = candidates(schema)
        val title = c.titles.firstOrNull() ?: return null
        return HabitChoice(
            titlePropId = title.id,
            emoji = c.texts.byPreset(emojiPreset)?.id,
            weeklyTarget = c.numbers.byPreset(targetPreset)?.id,
            active = c.checkboxes.byPreset(activePreset)?.id,
            startDate = c.dates.byPreset(startPreset)?.id,
            sortOrder = c.numbers.byPreset(orderPreset)?.id,
        )
    }

    private fun List<PropertyOption>.byPreset(names: List<String>): PropertyOption? =
        names.firstNotNullOfOrNull { name -> firstOrNull { it.name.equals(name, ignoreCase = true) } }
}

/**
 * HB-01 등록 행 두 개(✅ HABIT_LOG, 🌱 HABIT). 다시 등록하면 켜기 상태·위치·DB 색 기준 등 앱 상태는 기존 행에서 옮기고
 * (검증 메모 2), 매핑이 바뀐 쪽만 동기화 기준을 지워 처음부터 다시 받는다. 기록 DB는 완료·색·정렬을 쓰지 않는다.
 */
internal fun habitRows(
    log: HabitTarget,
    logChoice: HabitLogChoice,
    habit: HabitTarget,
    habitChoice: HabitChoice,
    colorHex: String,
    existingLog: DataSourceEntity?,
    existingHabit: DataSourceEntity?,
    nextPosition: Int,
): Pair<DataSourceEntity, DataSourceEntity> {
    val habitIds = HabitPropIds.encode(
        HabitPropIds(habitChoice.emoji, habitChoice.weeklyTarget, habitChoice.active, habitChoice.startDate, habitChoice.sortOrder),
    )
    val logChanged = existingLog == null || existingLog.titlePropId != logChoice.titlePropId ||
        existingLog.datePropId != logChoice.datePropId || existingLog.linkPropId != logChoice.linkPropId ||
        existingLog.linkDataSourceId != habit.dataSourceId
    val habitChanged = existingHabit == null || existingHabit.titlePropId != habitChoice.titlePropId || existingHabit.habitPropIds != habitIds
    val logRow = DataSourceEntity(
        dataSourceId = log.dataSourceId,
        databaseId = log.databaseId,
        name = log.name,
        colorHex = colorHex,
        titlePropId = logChoice.titlePropId,
        datePropId = logChoice.datePropId,
        donePropId = null,
        doneType = null,
        doneOption = null,
        undoneOption = null,
        colorPropId = null,
        sortPropId = null,
        enabled = existingLog?.enabled ?: true,
        lastSyncAt = existingLog?.lastSyncAt?.takeUnless { logChanged },
        lastFullSyncAt = existingLog?.lastFullSyncAt?.takeUnless { logChanged },
        position = existingLog?.position ?: nextPosition,
        propCatalog = existingLog?.propCatalog,
        mappedPropNames = existingLog?.mappedPropNames?.takeUnless { logChanged },
        schemaIssues = null,
        kind = SourceKind.HABIT_LOG.name,
        linkPropId = logChoice.linkPropId,
        linkDataSourceId = habit.dataSourceId,
    )
    val habitRow = DataSourceEntity(
        dataSourceId = habit.dataSourceId,
        databaseId = habit.databaseId,
        name = habit.name,
        colorHex = colorHex,
        titlePropId = habitChoice.titlePropId,
        datePropId = null,
        donePropId = null,
        doneType = null,
        doneOption = null,
        undoneOption = null,
        colorPropId = null,
        sortPropId = null,
        enabled = existingHabit?.enabled ?: true,
        lastSyncAt = existingHabit?.lastSyncAt?.takeUnless { habitChanged },
        lastFullSyncAt = existingHabit?.lastFullSyncAt?.takeUnless { habitChanged },
        position = existingHabit?.position ?: (nextPosition + 1),
        mappedPropNames = existingHabit?.mappedPropNames?.takeUnless { habitChanged },
        schemaIssues = null,
        kind = SourceKind.HABIT.name,
        habitPropIds = habitIds,
    )
    return logRow to habitRow
}
