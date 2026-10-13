package com.ysdoing.notioncal.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface EventDao {
    /** [from]~[to]와 겹치는 일정(기간 일정 포함). 꺼 둔 DB는 제외한다. */
    @Query(
        """
        SELECT e.*, d.colorHex AS sourceColorHex FROM events e
        JOIN data_sources d ON e.dataSourceId = d.dataSourceId
        WHERE d.enabled = 1 AND e.startDate <= :to AND COALESCE(e.endDate, e.startDate) >= :from
        ORDER BY e.startDate, d.position, e.sortKey IS NULL, e.sortKey, e.startTime IS NOT NULL, e.startTime, e.title
        """,
    )
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<EventRow>>

    @Upsert
    suspend fun upsert(events: List<EventEntity>)

    @Query("DELETE FROM events WHERE pageId IN (:pageIds)")
    suspend fun delete(pageIds: List<String>)

    /** 삭제 감지용: 해당 DB에서 [from]~[to]와 겹치는 로컬 일정 ID. */
    @Query(
        """
        SELECT pageId FROM events
        WHERE dataSourceId = :dataSourceId AND startDate <= :to AND COALESCE(endDate, startDate) >= :from
        """,
    )
    suspend fun pageIdsInRange(dataSourceId: String, from: LocalDate, to: LocalDate): List<String>

    @Query("DELETE FROM events WHERE dataSourceId = :dataSourceId")
    suspend fun deleteForSource(dataSourceId: String)
}

@Dao
interface DataSourceDao {
    @Query("SELECT * FROM data_sources ORDER BY position, name")
    fun observeAll(): Flow<List<DataSourceEntity>>

    @Query("SELECT * FROM data_sources WHERE enabled = 1 ORDER BY position, name")
    suspend fun enabled(): List<DataSourceEntity>

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

    @Query("DELETE FROM data_sources WHERE dataSourceId = :id")
    suspend fun deleteRow(id: String)
}

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(entry: SyncLogEntity)

    @Query("SELECT * FROM sync_log ORDER BY at DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<SyncLogEntity?>

    @Query("SELECT * FROM sync_log WHERE result != 'ERROR' ORDER BY at DESC, id DESC LIMIT 1")
    fun observeLatestSuccess(): Flow<SyncLogEntity?>

    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY at DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 1000)

    @Transaction
    suspend fun record(entry: SyncLogEntity) {
        insert(entry)
        trim()
    }
}
