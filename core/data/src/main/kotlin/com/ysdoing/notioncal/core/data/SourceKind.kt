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

    /** 💰 거래내역: 거래 1건 = 1행(날짜·금액·타입·대분류, LD-01). */
    LEDGER,

    /** 💰 예산: 날짜 없음(전체 조회, LD-05). */
    BUDGET,

    /** 💰 카테고리 마스터: 대분류 → 그룹, 날짜 없음(전체 조회). */
    CATEGORY,
    ;

    companion object {
        fun of(value: String?): SourceKind? = entries.firstOrNull { it.name == value }
    }
}

val DataSourceEntity.sourceKind: SourceKind? get() = SourceKind.of(kind)

/** LD-01 거래내역 속성 매핑(data_sources.rolePropIds). 금액·타입은 필수, 나머지는 비울 수 있다. */
@Serializable
data class LedgerPropIds(
    /** number */
    val amount: String? = null,
    /** select(지출·수입·이체) */
    val flow: String? = null,
    /** select(대분류) */
    val category: String? = null,
    /** rich_text(HH:MM) */
    val time: String? = null,
    /** select(결제수단) */
    val payment: String? = null,
    /** checkbox(예정) */
    val planned: String? = null,
    /** checkbox(앱입력) */
    val manual: String? = null,
) {
    companion object {
        fun parse(json: String?): LedgerPropIds =
            json?.let { runCatching { OutboxJson.decodeFromString(serializer(), it) }.getOrNull() } ?: LedgerPropIds()

        fun encode(ids: LedgerPropIds): String = OutboxJson.encodeToString(serializer(), ids)
    }
}

/** LD-05 예산 DB 속성 매핑(data_sources.rolePropIds). */
@Serializable
data class BudgetPropIds(
    /** select(연·분기·월·주) */
    val periodType: String? = null,
    /** rich_text(2026-10, 2026-W43) */
    val periodValue: String? = null,
    /** select(전체·생활비 …) */
    val group: String? = null,
    /** number */
    val amount: String? = null,
) {
    companion object {
        fun parse(json: String?): BudgetPropIds =
            json?.let { runCatching { OutboxJson.decodeFromString(serializer(), it) }.getOrNull() } ?: BudgetPropIds()

        fun encode(ids: BudgetPropIds): String = OutboxJson.encodeToString(serializer(), ids)
    }
}

/** 카테고리 마스터 속성 매핑(data_sources.rolePropIds). 제목이 대분류다. */
@Serializable
data class CategoryPropIds(
    /** select(그룹) */
    val group: String? = null,
) {
    companion object {
        fun parse(json: String?): CategoryPropIds =
            json?.let { runCatching { OutboxJson.decodeFromString(serializer(), it) }.getOrNull() } ?: CategoryPropIds()

        fun encode(ids: CategoryPropIds): String = OutboxJson.encodeToString(serializer(), ids)
    }
}

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
