package com.ysdoing.notioncal.core.data.db

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface EventDao {
    /** [from]~[to]와 겹치는 일정(기간 일정 포함). 꺼 둔 DB와 일정 DB가 아닌 것(✅ 습관 기록 등)은 제외한다. */
    @Query(
        """
        SELECT e.*, d.colorHex AS sourceColorHex, d.useOptionColors AS useOptionColors,
          d.emphasisPropId AS emphasisPropId, d.emphasisOptionIds AS emphasisOptionIds FROM events e
        JOIN data_sources d ON e.dataSourceId = d.dataSourceId
        WHERE d.enabled = 1 AND d.kind = 'CALENDAR' AND e.startDate <= :to AND COALESCE(e.endDate, e.startDate) >= :from
        ORDER BY e.startDate, d.position, e.sortKey IS NULL, e.sortKey, e.startTime IS NOT NULL, e.startTime, e.title
        """,
    )
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<EventRow>>

    /**
     * MV-10 제목 검색(켜 둔 DB만). [pattern]은 `%`·`_`·`\`를 이스케이프한 검색어다. 영문은 대소문자를 가리지 않는다(SQLite LIKE).
     * 아직 안 끝난 일정(오늘 이후)을 날짜 오름차순으로 먼저, 지난 일정을 최근 것부터 뒤에 둔다.
     */
    @Query(
        """
        SELECT e.*, d.colorHex AS sourceColorHex, d.useOptionColors AS useOptionColors,
          d.emphasisPropId AS emphasisPropId, d.emphasisOptionIds AS emphasisOptionIds FROM events e
        JOIN data_sources d ON e.dataSourceId = d.dataSourceId
        WHERE d.enabled = 1 AND d.kind = 'CALENDAR' AND e.title LIKE '%' || :pattern || '%' ESCAPE '\'
        ORDER BY COALESCE(e.endDate, e.startDate) < :today,
          CASE WHEN COALESCE(e.endDate, e.startDate) >= :today THEN e.startDate END ASC,
          CASE WHEN COALESCE(e.endDate, e.startDate) < :today THEN e.startDate END DESC,
          e.startTime IS NOT NULL, e.startTime, e.title
        LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, today: LocalDate, limit: Int): List<EventRow>

    @Upsert
    suspend fun upsert(events: List<EventEntity>)

    @Query("DELETE FROM events WHERE pageId IN (:pageIds)")
    suspend fun delete(pageIds: List<String>)

    /** 삭제 감지용: 해당 DB에서 [from]~[to]와 겹치는 로컬 일정 ID. 아직 보내지 않은 쓰기가 있는 일정은 뺀다. */
    @Query(
        """
        SELECT pageId FROM events
        WHERE dataSourceId = :dataSourceId AND startDate <= :to AND COALESCE(endDate, startDate) >= :from
          AND pendingOps = 0
        """,
    )
    suspend fun pageIdsInRange(dataSourceId: String, from: LocalDate, to: LocalDate): List<String>

    /** §12 대조('노션과 비교'): [pageIdsInRange]와 같은 범위의 일정 전체. */
    @Query(
        """
        SELECT * FROM events
        WHERE dataSourceId = :dataSourceId AND startDate <= :to AND COALESCE(endDate, startDate) >= :from
          AND pendingOps = 0
        """,
    )
    suspend fun eventsInRange(dataSourceId: String, from: LocalDate, to: LocalDate): List<EventEntity>

    @Query("SELECT * FROM events WHERE pageId = :pageId")
    suspend fun get(pageId: String): EventEntity?

    @Query("SELECT * FROM events WHERE pageId = :pageId")
    fun observe(pageId: String): Flow<EventEntity?>

    /** 아직 노션에 보내지 않은 쓰기가 있는 일정 ID(읽기 동기화가 건너뛴다). */
    @Query("SELECT pageId FROM events WHERE pendingOps > 0")
    suspend fun pendingPageIds(): List<String>

    @Query("UPDATE events SET pendingOps = :count WHERE pageId = :pageId")
    suspend fun setPending(pageId: String, count: Int)

    /** 생성 응답을 받으면 임시 ID를 실제 page_id로 바꾼다(§8.5-5). */
    @Query("UPDATE events SET pageId = :newId WHERE pageId = :oldId")
    suspend fun renamePage(oldId: String, newId: String)

    @Query("DELETE FROM events WHERE dataSourceId = :dataSourceId")
    suspend fun deleteForSource(dataSourceId: String)

    /** 캐시 초기화: 노션에서 다시 받을 수 있는 일정만 지운다. 아직 보내지 않은 쓰기가 있는 일정은 남긴다. */
    @Query("DELETE FROM events WHERE pendingOps = 0")
    suspend fun deleteSynced()
}

@Dao
interface DataSourceDao {
    @Query("SELECT * FROM data_sources ORDER BY position, name")
    fun observeAll(): Flow<List<DataSourceEntity>>

    /** 종류별 목록(SourceKind 이름). 달력 화면·퀵 추가·위젯 설정은 CALENDAR만 쓴다. */
    @Query("SELECT * FROM data_sources WHERE kind = :kind ORDER BY position, name")
    fun observeByKind(kind: String): Flow<List<DataSourceEntity>>

    @Query("SELECT * FROM data_sources WHERE enabled = 1 ORDER BY position, name")
    suspend fun enabled(): List<DataSourceEntity>

    /** HB-01: [habitSourceId](🌱 습관)를 짝으로 둔 ✅ 습관 기록 DB. */
    @Query("SELECT * FROM data_sources WHERE linkDataSourceId = :habitSourceId")
    suspend fun linkedTo(habitSourceId: String): List<DataSourceEntity>

    @Query("SELECT * FROM data_sources WHERE dataSourceId = :id")
    suspend fun get(id: String): DataSourceEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM data_sources")
    suspend fun nextPosition(): Int

    @Upsert
    suspend fun upsert(source: DataSourceEntity)

    @Query("UPDATE data_sources SET enabled = :enabled WHERE dataSourceId = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE data_sources SET lastSyncAt = :lastSyncAt WHERE dataSourceId = :id")
    suspend fun setLastSyncAt(id: String, lastSyncAt: Long)

    @Query("UPDATE data_sources SET lastFullSyncAt = :at WHERE dataSourceId = :id")
    suspend fun setLastFullSyncAt(id: String, at: Long)

    /** 다음 동기화가 모든 DB의 전체 범위를 다시 받게 한다(앱 업데이트 직후). */
    @Query("UPDATE data_sources SET lastFullSyncAt = NULL")
    suspend fun clearLastFullSync()

    @Query("UPDATE data_sources SET colorOptions = :json WHERE dataSourceId = :id")
    suspend fun setColorOptions(id: String, json: String?)

    @Query("UPDATE data_sources SET propCatalog = :json WHERE dataSourceId = :id")
    suspend fun setPropCatalog(id: String, json: String?)

    @Query("UPDATE data_sources SET extraPropIds = :json WHERE dataSourceId = :id")
    suspend fun setExtraPropIds(id: String, json: String?)

    /** EX-03 DB 색과 색 기준. */
    @Query("UPDATE data_sources SET colorHex = :colorHex, useOptionColors = :useOptionColors WHERE dataSourceId = :id")
    suspend fun setColorStyle(id: String, colorHex: String, useOptionColors: Boolean)

    /** EX-11 강조 기준(속성 ID, 옵션 ID JSON 배열). 둘 다 null이면 강조를 끈다. */
    @Query("UPDATE data_sources SET emphasisPropId = :propId, emphasisOptionIds = :optionIds WHERE dataSourceId = :id")
    suspend fun setEmphasis(id: String, propId: String?, optionIds: String?)

    /** NS-09 매핑 속성 이름과 알림. */
    @Query("UPDATE data_sources SET mappedPropNames = :names, schemaIssues = :issues WHERE dataSourceId = :id")
    suspend fun setSchemaState(id: String, names: String?, issues: String?)

    @Query("UPDATE data_sources SET schemaIssues = :issues WHERE dataSourceId = :id")
    suspend fun setSchemaIssues(id: String, issues: String?)

    @Query("DELETE FROM data_sources WHERE dataSourceId = :id")
    suspend fun deleteRow(id: String)

    /** 캐시 초기화 뒤 다음 동기화가 전체 조회가 되게 한다. */
    @Query("UPDATE data_sources SET lastSyncAt = NULL, lastFullSyncAt = NULL")
    suspend fun resetSyncCursors()
}

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(entry: SyncLogEntity)

    @Query("SELECT * FROM sync_log ORDER BY at DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<SyncLogEntity?>

    @Query("SELECT * FROM sync_log WHERE result != 'ERROR' ORDER BY at DESC, id DESC LIMIT 1")
    fun observeLatestSuccess(): Flow<SyncLogEntity?>

    @Query("SELECT * FROM sync_log WHERE result != 'OK' ORDER BY at DESC, id DESC LIMIT 1")
    fun observeLatestFailure(): Flow<SyncLogEntity?>

    @Query("SELECT * FROM sync_log ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SyncLogEntity>

    @Query("DELETE FROM sync_log")
    suspend fun deleteAll()

    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY at DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 1000)

    @Transaction
    suspend fun record(entry: SyncLogEntity) {
        insert(entry)
        trim()
    }
}

/** EX-02 공휴일. */
@Dao
interface HolidayDao {
    @Query("SELECT * FROM holidays WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<HolidayEntity>>

    @Query("DELETE FROM holidays WHERE date BETWEEN :from AND :to")
    suspend fun deleteRange(from: LocalDate, to: LocalDate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(holidays: List<HolidayEntity>)

    @Transaction
    suspend fun replaceRange(from: LocalDate, to: LocalDate, holidays: List<HolidayEntity>) {
        deleteRange(from, to)
        insert(holidays)
    }

    @Query("SELECT source FROM holidays LIMIT 1")
    suspend fun anySource(): String?

    /** 진단 화면: 저장된 공휴일 출처(없으면 null). */
    @Query("SELECT source FROM holidays LIMIT 1")
    fun observeSource(): Flow<String?>
}

