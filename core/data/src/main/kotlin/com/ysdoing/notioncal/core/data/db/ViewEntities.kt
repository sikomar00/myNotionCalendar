package com.ysdoing.notioncal.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 열 이름·셀 값을 한 글자열로 이을 때 쓰는 구분자(사용자 글에 나오지 않는 제어 문자). */
const val VIEW_CELL_SEPARATOR = "\u001F"

/**
 * [ViewSnapshotEntity.lastError]에 남는 '노션이 404로 답함'(보기가 지워졌거나 연결에 공유되지 않음, VW-06).
 * `ViewSync`가 `NotionError.NotShared`를 이 값으로 적는다. 위젯은 `:core:notion`을 모르므로 값을 여기서 맞춘다.
 * 릴리스(R8)는 클래스 이름을 바꾸므로 `::class.simpleName`이 아니라 정해 둔 문자열로 남긴다.
 */
const val VIEW_ERROR_NOT_FOUND = "NotShared"

/** [ViewWriteEntity.propType]이 이 값이면 속성 바꿈이 아니라 새 항목 만들기다([ViewWriteEntity.value]는 ViewCreate JSON, pageId는 임시 값). */
const val VIEW_WRITE_CREATE = "create"

/**
 * VW-01 등록한 노션 보기와 마지막으로 받은 설정(v12). 행이 있으면 등록된 보기다.
 * [columns]는 보이는 속성 이름을 [VIEW_CELL_SEPARATOR]로 이은 것. [syncedAt]이 0이면 아직 못 받았다.
 * [lastError]는 마지막 동기화 실패(없으면 null). 실패해도 이전 행은 지우지 않는다.
 */
@Entity(tableName = "view_snapshots")
data class ViewSnapshotEntity(
    @PrimaryKey val viewId: String,
    val dataSourceId: String,
    val name: String,
    val viewType: String,
    val columns: String = "",
    val syncedAt: Long = 0,
    val lastError: String? = null,
    /** v14: 보이는 속성의 쓰기 정보(속성 ID·종류·선택지)를 JSON으로. 비어 있으면 아직 못 받았거나 읽기만 한다(ViewColumn). */
    @ColumnInfo(defaultValue = "") val columnInfo: String = "",
    /** v14: 위젯에서 마지막으로 바꾼 시각(epoch ms). 이보다 일찍 시작한 읽기는 결과를 덮어쓰지 않는다(보내는 도중에 받은 이전 값 방지). */
    @ColumnInfo(defaultValue = "0") val lastWriteAt: Long = 0,
)

/** 보기의 한 행(VW-02로 글자가 된 값). [cells]는 [VIEW_CELL_SEPARATOR]로 이은 열 순서의 값. */
@Entity(tableName = "view_rows", primaryKeys = ["viewId", "position"])
data class ViewRowEntity(
    val viewId: String,
    val position: Int,
    val pageId: String,
    val url: String,
    val cells: String,
    /** v14: 쓸 수 있는 속성의 원래 값(속성 ID → 체크·선택지 ID·숫자)을 JSON으로. 표시용 [cells]와 따로 둔다. */
    @ColumnInfo(defaultValue = "") val typed: String = "",
)

/**
 * v14 위젯에서 바꾼 보기 행 속성 중 아직 노션에 보내지 않은 것(아웃박스와 같은 역할의 보기 전용 큐).
 * 같은 페이지·속성은 마지막 값 하나만 남긴다. [value]는 JSON 원시값(체크 true/false, 선택지 ID, 숫자), null이면 비운다.
 * 캘린더형 일정의 아웃박스와 섞지 않는다: 그쪽은 날짜 속성이 있는 DB만 처리하고 일정 행을 갱신한다.
 */
@Entity(tableName = "view_writes")
data class ViewWriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val viewId: String,
    val pageId: String,
    val dataSourceId: String,
    val propId: String,
    val propType: String,
    val value: String?,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Dao
abstract class ViewDao {
    @Query("SELECT * FROM view_snapshots")
    abstract suspend fun all(): List<ViewSnapshotEntity>

    @Query("SELECT * FROM view_snapshots ORDER BY name")
    abstract fun observeAll(): Flow<List<ViewSnapshotEntity>>

    @Query("SELECT * FROM view_snapshots WHERE viewId = :viewId")
    abstract fun observe(viewId: String): Flow<ViewSnapshotEntity?>

    @Query("SELECT * FROM view_rows WHERE viewId = :viewId ORDER BY position")
    abstract fun observeRows(viewId: String): Flow<List<ViewRowEntity>>

    @Query("SELECT * FROM view_rows WHERE viewId = :viewId ORDER BY position")
    abstract suspend fun rows(viewId: String): List<ViewRowEntity>

    @Upsert
    abstract suspend fun upsert(view: ViewSnapshotEntity)

    @Query("UPDATE view_snapshots SET lastError = :error WHERE viewId = :viewId")
    abstract suspend fun markError(viewId: String, error: String)

    @Insert
    protected abstract suspend fun insertRows(rows: List<ViewRowEntity>)

    @Query("DELETE FROM view_rows WHERE viewId = :viewId")
    protected abstract suspend fun deleteRows(viewId: String)

    @Query("SELECT * FROM view_snapshots WHERE viewId = :viewId")
    abstract suspend fun snapshot(viewId: String): ViewSnapshotEntity?

    @Query("SELECT * FROM view_rows WHERE viewId = :viewId AND pageId = :pageId")
    abstract suspend fun row(viewId: String, pageId: String): ViewRowEntity?

    @Query("UPDATE view_rows SET cells = :cells, typed = :typed WHERE viewId = :viewId AND pageId = :pageId")
    protected abstract suspend fun updateRow(viewId: String, pageId: String, cells: String, typed: String)

    @Insert
    protected abstract suspend fun insertWrite(write: ViewWriteEntity)

    @Query("DELETE FROM view_writes WHERE viewId = :viewId AND pageId = :pageId AND propId = :propId")
    protected abstract suspend fun deleteWrite(viewId: String, pageId: String, propId: String)

    @Query("DELETE FROM view_writes WHERE viewId = :viewId")
    protected abstract suspend fun deleteWrites(viewId: String)

    @Query("SELECT * FROM view_writes ORDER BY id LIMIT 1")
    abstract suspend fun firstWrite(): ViewWriteEntity?

    @Query("DELETE FROM view_writes WHERE id = :id")
    abstract suspend fun deleteWriteById(id: Long)

    @Query("UPDATE view_writes SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    abstract suspend fun markWriteFailed(id: Long, error: String)

    @Query("SELECT COUNT(*) FROM view_writes WHERE viewId = :viewId")
    abstract suspend fun pendingWrites(viewId: String): Int

    @Query("SELECT COUNT(*) FROM view_writes")
    abstract suspend fun pendingWriteCount(): Int

    @Query("UPDATE view_snapshots SET lastWriteAt = :at WHERE viewId = :viewId")
    protected abstract suspend fun markWritten(viewId: String, at: Long)

    /**
     * 새 항목 만들기를 큐에 쌓는다(고칠 로컬 행이 없다). 보기는 노션이 만든 뒤 처리기가 다시 받아 채운다.
     * 그 사이 등록이 지워졌으면(등록을 지우면 그 보기의 바꿈은 노션에 가지 않는다) 쌓지 않고 false.
     */
    @Transaction
    open suspend fun enqueueCreate(write: ViewWriteEntity): Boolean {
        if (snapshot(write.viewId) == null) return false
        insertWrite(write)
        markWritten(write.viewId, write.createdAt)
        return true
    }

    /**
     * 로컬 행을 먼저 고치고(낙관적) 같은 속성의 이전 대기분을 지운 뒤 쓰기 큐에 쌓는다. 한 트랜잭션이다.
     */
    @Transaction
    open suspend fun enqueue(row: ViewRowEntity, write: ViewWriteEntity) {
        updateRow(row.viewId, row.pageId, row.cells, row.typed)
        deleteWrite(write.viewId, write.pageId, write.propId)
        insertWrite(write)
        markWritten(write.viewId, write.createdAt)
    }

    /**
     * 현재 행을 읽어 [block]으로 새 행과 큐 항목을 만들고 저장하기까지를 한 트랜잭션으로 한다. 바로 이어 누른 탭이나 보기 읽기와
     * 겹쳐도 읽은 값이 낡지 않는다(체크 두 번이 한 번으로 세어지거나 다른 열이 이전 값으로 되돌아가는 일을 막는다).
     * 바꿨으면 true.
     */
    @Transaction
    open suspend fun mutate(
        viewId: String,
        pageId: String,
        block: (ViewSnapshotEntity, ViewRowEntity) -> Pair<ViewRowEntity, ViewWriteEntity>?,
    ): Boolean {
        val snapshot = snapshot(viewId) ?: return false
        val row = row(viewId, pageId) ?: return false
        val (changed, write) = block(snapshot, row) ?: return false
        enqueue(changed, write)
        return true
    }

    @Query("DELETE FROM view_snapshots WHERE viewId = :viewId")
    protected abstract suspend fun deleteView(viewId: String)

    /** 보기 등록을 지운다(행 스냅샷과 아직 보내지 않은 바꿈 포함: 등록을 지우면 그 보기에서 한 바꿈은 노션에 가지 않는다). */
    @Transaction
    open suspend fun remove(viewId: String) {
        deleteRows(viewId)
        deleteWrites(viewId)
        deleteView(viewId)
    }

    /**
     * 설정과 행을 한꺼번에 바꾼다. 중간에 실패하면 이전 스냅샷이 그대로 남는다.
     * 받는 사이에 사용자가 등록을 지웠다면(등록 화면의 삭제) 되살리지 않는다. 먼저 [upsert]로 등록해 둔 보기만 바꾼다.
     * 아직 보내지 않은 바꿈(view_writes)이 있거나, 이 읽기([fetchedAt]에 시작)보다 늦게 위젯에서 바꾼 보기는 노션 값으로 덮지 않는다
     * (방금 한 체크가 읽는 도중 이전 값으로 되돌아가 보이지 않게). 다음 읽기가 맞춘다.
     */
    @Transaction
    open suspend fun replace(view: ViewSnapshotEntity, rows: List<ViewRowEntity>, fetchedAt: Long = Long.MAX_VALUE) {
        val current = snapshot(view.viewId) ?: return
        if (pendingWrites(view.viewId) > 0 || current.lastWriteAt >= fetchedAt) return
        upsert(view.copy(lastWriteAt = current.lastWriteAt))
        deleteRows(view.viewId)
        insertRows(rows)
    }
}
