package com.ysdoing.notioncal.core.data

import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import kotlinx.serialization.Serializable

/** 등록 DB 종류(data_sources.kind, PRD §10의 role). 뒤 Phase(LEDGER 등) 값은 아직 모르므로 [of]가 null을 준다. */
enum class SourceKind {
    CALENDAR,

    /** 🌱 습관: 습관 1개 = 1행, 날짜 없음(전체 조회). */
    HABIT,

    /** ✅ 습관 기록: 체크 1번 = 1행(날짜 + 습관 relation). */
    HABIT_LOG,
    ;

    companion object {
        fun of(value: String?): SourceKind? = entries.firstOrNull { it.name == value }
    }
}

val DataSourceEntity.sourceKind: SourceKind? get() = SourceKind.of(kind)

/** HB-01 🌱 습관 DB의 속성 매핑(data_sources.habitPropIds). 모두 속성 ID, 비어 있으면 기본값을 쓴다. */
@Serializable
data class HabitPropIds(
    /** rich_text */
    val emoji: String? = null,
    /** number(1~7) */
    val weeklyTarget: String? = null,
    /** checkbox */
    val active: String? = null,
    /** date */
    val startDate: String? = null,
    /** number */
    val sortOrder: String? = null,
) {
    companion object {
        fun parse(json: String?): HabitPropIds =
            json?.let { runCatching { OutboxJson.decodeFromString(serializer(), it) }.getOrNull() } ?: HabitPropIds()

        fun encode(ids: HabitPropIds): String = OutboxJson.encodeToString(serializer(), ids)
    }
}
