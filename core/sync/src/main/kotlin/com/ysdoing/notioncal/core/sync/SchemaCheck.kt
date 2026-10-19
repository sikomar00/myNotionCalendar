package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.BudgetPropIds
import com.ysdoing.notioncal.core.data.CategoryPropIds
import com.ysdoing.notioncal.core.data.HabitPropIds
import com.ysdoing.notioncal.core.data.LedgerPropIds
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.sourceKind
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto

/**
 * NS-09 스키마 변경 감지. 매핑은 속성 ID로 하므로 이름이 바뀌어도 동작은 그대로이고 알림만 한다.
 * - 제목·날짜 속성이 없거나 종류가 바뀜 → 그 DB 동기화를 건너뛴다(날짜 필터 조회가 실패하므로).
 * - 완료·색·정렬 속성이 없거나 종류가 바뀜 → 그 매핑만 빼고 동기화한다(완료 아님·DB 색).
 * - ✅ 습관 기록의 '습관'(relation)이 없거나 바뀜 → 연결을 빼고 읽는다(체크는 HabitRepository가 막는다).
 * - 🌱 습관(날짜 없음)은 제목만 필수이고, 이모지·주 목표·진행 중·시작일·순서는 빠지면 기본값을 쓴다.
 * - 💰 거래내역은 금액(number)·타입(select)이, 예산은 기간유형·기간값이 없으면 동기화를 멈춘다(LD-01). 나머지는 빼고 읽는다.
 */
internal object SchemaCheck {
    data class Report(
        /** 이번 스키마에서 찾은 변경. */
        val issues: List<SchemaIssue>,
        /** 역할별 지금 이름(사라진 속성은 이전 이름을 유지해 알림에 쓴다). */
        val names: Map<String, String>,
        /** 빼고 동기화할 선택 역할(완료·색·정렬). */
        val droppedRoles: Set<String>,
    ) {
        val blocksSync: Boolean get() = issues.any { it.blocksSync }
    }

    /** 가계부 종류의 역할별 속성(ID, 허용 유형). */
    private fun roleProps(source: DataSourceEntity): List<Triple<String, String, Set<String>>> = when (source.sourceKind) {
        SourceKind.LEDGER -> LedgerPropIds.parse(source.rolePropIds).let { p ->
            listOfNotNull(
                Triple(SchemaIssue.AMOUNT, p.amount.orEmpty(), setOf("number")),
                Triple(SchemaIssue.FLOW, p.flow.orEmpty(), setOf("select")),
                p.category?.let { Triple(SchemaIssue.CATEGORY, it, setOf("select")) },
                p.time?.let { Triple(SchemaIssue.TIME, it, setOf("rich_text")) },
                p.payment?.let { Triple(SchemaIssue.PAYMENT, it, setOf("select")) },
                p.planned?.let { Triple(SchemaIssue.PLANNED, it, setOf("checkbox")) },
                p.manual?.let { Triple(SchemaIssue.MANUAL, it, setOf("checkbox")) },
            )
        }
        SourceKind.BUDGET -> BudgetPropIds.parse(source.rolePropIds).let { p ->
            listOfNotNull(
                Triple(SchemaIssue.PERIOD_TYPE, p.periodType.orEmpty(), setOf("select")),
                Triple(SchemaIssue.PERIOD_VALUE, p.periodValue.orEmpty(), setOf("rich_text")),
                p.group?.let { Triple(SchemaIssue.GROUP, it, setOf("select")) },
                p.amount?.let { Triple(SchemaIssue.AMOUNT, it, setOf("number")) },
            )
        }
        SourceKind.CATEGORY -> listOfNotNull(CategoryPropIds.parse(source.rolePropIds).group?.let { Triple(SchemaIssue.GROUP, it, setOf("select")) })
        else -> emptyList()
    }

    fun check(source: DataSourceEntity, schema: DataSourceDto, previousNames: Map<String, String>): Report {
        val byId = schema.properties.values.associateBy { it.id }
        val habit = HabitPropIds.parse(source.habitPropIds).takeIf { source.sourceKind == SourceKind.HABIT }
        val roles = listOfNotNull(
            Triple(SchemaIssue.TITLE, source.titlePropId, setOf("title")),
            source.datePropId?.let { Triple(SchemaIssue.DATE, it, setOf("date")) },
            source.donePropId?.let { Triple(SchemaIssue.DONE, it, if (source.doneType == "STATUS") setOf("status") else setOf("checkbox")) },
            source.colorPropId?.let { Triple(SchemaIssue.COLOR, it, setOf("select", "status")) },
            source.sortPropId?.let { Triple(SchemaIssue.SORT, it, setOf("select")) },
            source.linkPropId?.let { Triple(SchemaIssue.LINK, it, setOf("relation")) },
            habit?.emoji?.let { Triple(SchemaIssue.EMOJI, it, setOf("rich_text")) },
            habit?.weeklyTarget?.let { Triple(SchemaIssue.TARGET, it, setOf("number")) },
            habit?.active?.let { Triple(SchemaIssue.ACTIVE, it, setOf("checkbox")) },
            habit?.startDate?.let { Triple(SchemaIssue.START, it, setOf("date")) },
            habit?.sortOrder?.let { Triple(SchemaIssue.ORDER, it, setOf("number")) },
        ) + roleProps(source)
        val issues = mutableListOf<SchemaIssue>()
        val names = mutableMapOf<String, String>()
        val dropped = mutableSetOf<String>()
        for ((role, id, types) in roles) {
            val previous = previousNames[role]
            val prop = byId[id]
            when {
                prop == null -> {
                    issues += SchemaIssue(role, SchemaIssue.MISSING, oldName = previous)
                    previous?.let { names[role] = it }
                    dropped += role
                }
                prop.type !in types -> {
                    issues += SchemaIssue(role, SchemaIssue.TYPE_CHANGED, oldName = prop.name)
                    names[role] = prop.name
                    dropped += role
                }
                else -> {
                    if (previous != null && previous != prop.name) issues += SchemaIssue(role, SchemaIssue.RENAMED, previous, prop.name)
                    names[role] = prop.name
                }
            }
        }
        return Report(issues, names, dropped - SchemaIssue.REQUIRED_ROLES)
    }
}
