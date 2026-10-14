package com.ysdoing.notioncal.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 아직 노션에 보내지 않은 쓰기 1건(NS-04, PRD §10). 같은 페이지의 작업은 [createdAt]·[id] 순서로 보낸다.
 * [changes]·[baseValues]는 [com.ysdoing.notioncal.core.data.EventChange] JSON이다.
 */
@Entity(tableName = "outbox", indices = [Index("pageId"), Index("createdAt")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: String,
    val dataSourceId: String,
    /** "CREATE", "UPDATE", "TRASH" */
    val op: String,
    /** 보낼 값(바뀐 필드만). */
    val changes: String,
    /** 바꾸기 전 값(충돌 판정·400 되돌리기용, §8.5-6). TRASH는 지운 일정 전체. */
    val baseValues: String?,
    /** 마지막으로 받은 노션 last_edited_time(epoch ms). CREATE는 null. */
    val baseLastEdited: Long?,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    /** 마지막 실패 종류(NotionError 이름). 토큰·제목은 넣지 않는다(SEC-02). */
    val lastError: String? = null,
)

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(row: OutboxEntity): Long

    @Update
    suspend fun update(row: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt, id")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxEntity?

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM outbox WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: String)

    @Query("UPDATE outbox SET pageId = :newId WHERE pageId = :oldId")
    suspend fun renamePage(oldId: String, newId: String)

    /** 아직 보내지 않은 작업이 있는 페이지(읽기 동기화가 건너뛴다). */
    @Query("SELECT DISTINCT pageId FROM outbox")
    suspend fun pageIds(): List<String>

    @Query("SELECT COUNT(*) FROM outbox WHERE pageId = :pageId")
    suspend fun countForPage(pageId: String): Int

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>
}

/** NS-05 충돌·반영 실패 기록. 화면 배너가 읽고, 사용자가 확인하면 seen = 1. */
@Entity(tableName = "conflicts", indices = [Index("seen")])
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: String,
    val title: String,
    /** "TITLE", "DATE", "DONE", "SELECT", 또는 전체 작업이면 "PAGE". */
    val field: String,
    val localValue: String?,
    val remoteValue: String?,
    /** "CONFLICT"(노션 값 우선), "REMOTE_DELETED"(노션에서 삭제됨), "REJECTED"(노션이 거부해 되돌림) */
    val kind: String,
    val at: Long,
    val seen: Boolean = false,
)

@Dao
interface ConflictDao {
    @Insert
    suspend fun insert(row: ConflictEntity)

    @Query("SELECT * FROM conflicts WHERE seen = 0 ORDER BY at DESC")
    fun observeUnseen(): Flow<List<ConflictEntity>>

    @Query("SELECT * FROM conflicts ORDER BY at DESC")
    suspend fun all(): List<ConflictEntity>

    @Query("UPDATE conflicts SET seen = 1 WHERE seen = 0")
    suspend fun markAllSeen()

    @Query("DELETE FROM conflicts")
    suspend fun deleteAll()
}
