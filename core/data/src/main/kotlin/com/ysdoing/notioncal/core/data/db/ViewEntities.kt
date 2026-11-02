package com.ysdoing.notioncal.core.data.db

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
)

/** 보기의 한 행(VW-02로 글자가 된 값). [cells]는 [VIEW_CELL_SEPARATOR]로 이은 열 순서의 값. */
@Entity(tableName = "view_rows", primaryKeys = ["viewId", "position"])
data class ViewRowEntity(
    val viewId: String,
    val position: Int,
    val pageId: String,
    val url: String,
    val cells: String,
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

    @Query("DELETE FROM view_snapshots WHERE viewId = :viewId")
    protected abstract suspend fun deleteView(viewId: String)

    /** 보기 등록을 지운다(행 스냅샷 포함). 노션에는 아무 영향이 없다. */
    @Transaction
    open suspend fun remove(viewId: String) {
        deleteRows(viewId)
        deleteView(viewId)
    }

    @Query("SELECT COUNT(*) FROM view_snapshots WHERE viewId = :viewId")
    protected abstract suspend fun registeredCount(viewId: String): Int

    /**
     * 설정과 행을 한꺼번에 바꾼다. 중간에 실패하면 이전 스냅샷이 그대로 남는다.
     * 받는 사이에 사용자가 등록을 지웠다면(등록 화면의 삭제) 되살리지 않는다. 먼저 [upsert]로 등록해 둔 보기만 바꾼다.
     */
    @Transaction
    open suspend fun replace(view: ViewSnapshotEntity, rows: List<ViewRowEntity>) {
        if (registeredCount(view.viewId) == 0) return
        upsert(view)
        deleteRows(view.viewId)
        insertRows(rows)
    }
}
