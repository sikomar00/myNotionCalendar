package com.ysdoing.notioncal.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** 열 이름·셀 값을 한 글자열로 이을 때 쓰는 구분자(사용자 글에 나오지 않는 제어 문자). */
const val VIEW_CELL_SEPARATOR = "\u001F"

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

    /** 설정과 행을 한꺼번에 바꾼다. 중간에 실패하면 이전 스냅샷이 그대로 남는다. */
    @Transaction
    open suspend fun replace(view: ViewSnapshotEntity, rows: List<ViewRowEntity>) {
        upsert(view)
        deleteRows(view.viewId)
        insertRows(rows)
    }
}
