package com.ysdoing.notioncal.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * NS-09 매핑한 속성의 스키마 변경 하나. [role]은 매핑 역할(제목·날짜·완료·색·정렬), [kind]는 사라짐·종류 바뀜·이름 바뀜.
 * [acknowledged]는 사용자가 배너에서 확인했는지(확인한 알림은 다시 띄우지 않는다).
 */
@Serializable
data class SchemaIssue(
    val role: String,
    val kind: String,
    val oldName: String? = null,
    val newName: String? = null,
    val acknowledged: Boolean = false,
) {
    /** 제목·날짜 속성이 없거나 종류가 바뀌면 그 DB는 동기화하지 않는다(캐시는 그대로). */
    val blocksSync: Boolean get() = kind != RENAMED && role in REQUIRED_ROLES

    private val key get() = role to kind

    companion object {
        const val TITLE = "TITLE"
        const val DATE = "DATE"
        const val DONE = "DONE"
        const val COLOR = "COLOR"
        const val SORT = "SORT"
        val REQUIRED_ROLES = setOf(TITLE, DATE)

        const val MISSING = "MISSING"
        const val TYPE_CHANGED = "TYPE_CHANGED"
        const val RENAMED = "RENAMED"

        private val listSerializer = ListSerializer(serializer())
        private val namesSerializer = MapSerializer(String.serializer(), String.serializer())

        fun parse(json: String?): List<SchemaIssue> =
            json?.let { runCatching { OutboxJson.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()

        /** 비어 있으면 null(배너 없음). */
        fun encode(issues: List<SchemaIssue>): String? =
            issues.takeIf { it.isNotEmpty() }?.let { OutboxJson.encodeToString(listSerializer, it) }

        fun parseNames(json: String?): Map<String, String> =
            json?.let { runCatching { OutboxJson.decodeFromString(namesSerializer, it) }.getOrNull() }.orEmpty()

        fun encodeNames(names: Map<String, String>): String? =
            names.takeIf { it.isNotEmpty() }?.let { OutboxJson.encodeToString(namesSerializer, it) }

        /**
         * 이번 동기화에서 찾은 [fresh]에 이미 확인한 표시를 옮긴다. 이름 바뀜은 한 번만 잡히므로(바로 새 이름을 기록)
         * 아직 확인하지 않은 이름 바뀜은 다음 동기화에도 남긴다.
         */
        fun merge(stored: List<SchemaIssue>, fresh: List<SchemaIssue>): List<SchemaIssue> {
            val carried = fresh.map { issue -> stored.firstOrNull { it.key == issue.key }?.let { issue.copy(acknowledged = it.acknowledged) } ?: issue }
            val pendingRenames = stored.filter { old -> old.kind == RENAMED && !old.acknowledged && fresh.none { it.key == old.key } }
            return carried + pendingRenames
        }

        /** 배너 '확인': 모두 확인 표시. 이름 바뀜은 더 알릴 것이 없어 지운다. */
        fun acknowledge(stored: List<SchemaIssue>): List<SchemaIssue> =
            stored.filter { it.kind != RENAMED }.map { it.copy(acknowledged = true) }
    }
}
