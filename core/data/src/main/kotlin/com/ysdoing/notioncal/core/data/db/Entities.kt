package com.ysdoing.notioncal.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** 등록한 노션 data source와 속성 매핑(NS-02). 속성은 이름이 아니라 ID로 저장한다(PRD §8.3). */
@Entity(tableName = "data_sources")
data class DataSourceEntity(
    @PrimaryKey val dataSourceId: String,
    val databaseId: String,
    val name: String,
    /** "#RRGGBB". 일정에 색 기준 값이 없을 때 쓰는 DB 색. */
    val colorHex: String,
    val titlePropId: String,
    /** 날짜 속성 ID. 날짜 없는 DB(🌱 습관, HB-01)는 null이다(v9). */
    val datePropId: String?,
    val donePropId: String?,
    /** "CHECKBOX" 또는 "STATUS". 완료 속성을 안 쓰면 null. */
    val doneType: String?,
    /** status 완료 처리 시 쓸 옵션 이름(쓰기는 Phase 3). */
    val doneOption: String?,
    val undoneOption: String?,
    val colorPropId: String?,
    val sortPropId: String?,
    val enabled: Boolean = true,
    /** 증분 조회 기준 시각(epoch ms). null이면 아직 범위 조회 전. */
    val lastSyncAt: Long? = null,
    /** 전체 범위 대조(삭제 감지) 시각(epoch ms). */
    val lastFullSyncAt: Long? = null,
    val position: Int = 0,
    /** 색 기준 select 옵션 목록(JSON: [{id,name,color}]). 동기화 때 스키마로 갱신한다(편집 시트용, v3). */
    val colorOptions: String? = null,
    /** EX-07 보기·편집할 수 있는 속성 목록(JSON: [ExtraProp]). 동기화 때 스키마로 갱신한다(v4). null이면 아직 안 받음. */
    val propCatalog: String? = null,
    /** EX-07 편집 화면에 보여 줄 속성 ID 목록(JSON 배열, v4). */
    val extraPropIds: String? = null,
    /** EX-03 일정 색 기준: true면 색 속성(select) 값의 노션 색(값이 없으면 DB 색), false면 모두 DB 색(v5). */
    @ColumnInfo(defaultValue = "1") val useOptionColors: Boolean = true,
    /** NS-09 매핑한 속성의 마지막으로 본 이름(JSON {역할: 이름}, v5). 이름 변경 감지용. */
    val mappedPropNames: String? = null,
    /** NS-09 아직 확인하지 않은 스키마 변경 알림(JSON 배열, v5). null이면 없음. */
    val schemaIssues: String? = null,
    /** EX-11 강조 기준 속성(select·multi_select·status) ID(v8). null이면 강조 안 함. */
    val emphasisPropId: String? = null,
    /** EX-11 강조할 옵션 ID 목록(JSON 배열, v8). 예: 우선순위 '1st'. */
    val emphasisOptionIds: String? = null,
    /**
     * DB 종류(v9, PRD §10의 role). [com.ysdoing.notioncal.core.data.SourceKind] 이름: CALENDAR(일정), HABIT(🌱 습관),
     * HABIT_LOG(✅ 습관 기록). 달력·검색·일정 위젯은 CALENDAR만 읽는다. 모르는 값은 건너뛴다.
     */
    @ColumnInfo(defaultValue = "CALENDAR") val kind: String = "CALENDAR",
    /** HABIT_LOG: 습관을 가리키는 relation 속성 ID(v9). */
    val linkPropId: String? = null,
    /** HABIT_LOG: 짝인 🌱 습관 data source ID(v9). 켜기·끄기·삭제를 함께 한다. */
    val linkDataSourceId: String? = null,
    /** HABIT: 이모지·주 목표·진행 중·시작일·순서 속성 ID(JSON [com.ysdoing.notioncal.core.data.HabitPropIds], v9). */
    val habitPropIds: String? = null,
)

/** 노션 페이지 1개 = 1행(PRD §10). 날짜만 있는 일정은 시간 컬럼이 null이다(NS-07). */
@Entity(
    tableName = "events",
    indices = [Index("startDate"), Index("dataSourceId", "startDate"), Index("linkPageId", "startDate")],
)
data class EventEntity(
    @PrimaryKey val pageId: String,
    val dataSourceId: String,
    val title: String,
    /** 표시 날짜(Asia/Seoul). 시간 일정도 이 날짜에 놓는다. */
    val startDate: LocalDate,
    /** 여러 날 일정의 마지막 날(포함). 하루 일정이면 null. */
    val endDate: LocalDate?,
    /** 시간 일정의 시작·종료 Instant(epoch ms). 종일 일정이면 null. */
    val startTime: Long?,
    val endTime: Long?,
    val isAllDay: Boolean,
    val done: Boolean,
    /** 노션 색 이름(yellow ...). null이면 DB 색. */
    val colorKey: String?,
    val sortKey: Int?,
    val url: String,
    val lastEditedTime: Long,
    /** 색 기준 select에서 고른 옵션 ID(편집 시트용, v3). */
    val colorOptionId: String? = null,
    /** 아직 노션에 보내지 않은 쓰기 작업 수(v3). 0보다 크면 읽기 동기화가 덮어쓰거나 지우지 않는다. */
    @ColumnInfo(defaultValue = "0") val pendingOps: Int = 0,
    /** EX-07 추가 속성 값(JSON: {속성 ID: PropValue}, v4). */
    val extraValues: String? = null,
    /** 제목에 노션 언급(@)이 있다(v7). 앱에서 제목을 바꾸면 언급이 글자로 바뀌므로 편집 화면이 경고한다. */
    @ColumnInfo(defaultValue = "0") val titleHasMention: Boolean = false,
    /** ✅ 습관 기록 행이 가리키는 🌱 습관 page ID(relation, v9). 일정 행은 null. */
    val linkPageId: String? = null,
)

/** 화면용 조회 결과: 일정 + 소속 DB 색. */
data class EventRow(
    @Embedded val event: EventEntity,
    val sourceColorHex: String,
    /** EX-03 소속 DB의 색 기준(꺼져 있으면 DB 색만 쓴다). */
    val useOptionColors: Boolean,
    /** EX-11 소속 DB의 강조 기준(속성 ID·옵션 ID JSON). */
    val emphasisPropId: String? = null,
    val emphasisOptionIds: String? = null,
)

/** 동기화 기록(진단 화면·§3 지표). 오류 문구에 토큰·일정 제목을 넣지 않는다(SEC-02). */
@Entity(tableName = "sync_log", indices = [Index("at")])
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val trigger: String,
    /** "OK", "PARTIAL", "ERROR" */
    val result: String,
    val durationMs: Long,
    val pulled: Int,
    val pushed: Int,
    /** NotionError 종류 이름(예: "Unauthorized"). */
    val error: String?,
)

/** EX-02 공휴일(PRD §10 holidays). 폰의 공휴일 캘린더에서 복사한다. 날짜 하나에 이름 하나. */
@Entity(tableName = "holidays")
data class HolidayEntity(
    @PrimaryKey val date: LocalDate,
    val name: String,
    /** "GOOGLE"(대한민국의 휴일) 또는 "SAMSUNG"(삼성 공휴일). */
    val source: String,
)


/**
 * 🌱 습관 1개 = 1행(HB-01, v9). 노션에서 날짜 없이 전체 조회해 DB별로 통째로 바꾸는 캐시다(DB 복구 때 남기지 않는다).
 * 값이 비면 기본값: 주 목표 7, 진행 중 true, 시작일 없음, 순서 없음(이름순).
 */
@Entity(tableName = "habits", indices = [Index("dataSourceId")])
data class HabitEntity(
    @PrimaryKey val pageId: String,
    val dataSourceId: String,
    val name: String,
    val emoji: String?,
    /** 1~7. */
    val weeklyTarget: Int,
    val active: Boolean,
    val startDate: LocalDate?,
    val sortOrder: Double?,
    val url: String = "",
)
