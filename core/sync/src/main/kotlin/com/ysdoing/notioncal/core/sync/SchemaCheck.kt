package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto

/**
 * NS-09 스키마 변경 감지. 매핑은 속성 ID로 하므로 이름이 바뀌어도 동작은 그대로이고 알림만 한다.
 * - 제목·날짜 속성이 없거나 종류가 바뀜 → 그 DB 동기화를 건너뛴다(날짜 필터 조회가 실패하므로).
 * - 완료·색·정렬 속성이 없거나 종류가 바뀜 → 그 매핑만 빼고 동기화한다(완료 아님·DB 색).
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

    fun check(source: DataSourceEntity, schema: DataSourceDto, previousNames: Map<String, String>): Report {
        val byId = schema.properties.values.associateBy { it.id }
        val roles = listOfNotNull(
            Triple(SchemaIssue.TITLE, source.titlePropId, setOf("title")),
            source.datePropId?.let { Triple(SchemaIssue.DATE, it, setOf("date")) },
            source.donePropId?.let { Triple(SchemaIssue.DONE, it, if (source.doneType == "STATUS") setOf("status") else setOf("checkbox")) },
            source.colorPropId?.let { Triple(SchemaIssue.COLOR, it, setOf("select", "status")) },
            source.sortPropId?.let { Triple(SchemaIssue.SORT, it, setOf("select")) },
            source.linkPropId?.let { Triple(SchemaIssue.LINK, it, setOf("relation")) },
        )
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
