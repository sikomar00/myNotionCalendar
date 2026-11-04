package com.ysdoing.notioncal.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
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
 * [ViewSnapshotEntity.lastError]가 이 값이면 노션이 이 보기의 설정·행 요청을 400(Validation)으로 거부한다: 일시 오류가 아니라 앱이 읽을 수 없는
 * 보기(SY-07)다. `ViewSync`가 `NotionError.Validation`을 이 값으로 적는다.
 */
const val VIEW_ERROR_UNSUPPORTED = "Validation"

/** [ViewWriteEntity.lastError]가 이 값이면 처리기가 이 쓰기를 보내는 중이다(노션 값을 읽고 쓰는 동안). 이때 같은 속성을 또 바꾸면 기준값을 이어받지 않는다. */
const val VIEW_WRITE_SENDING = "Sending"

/** [ViewWriteEntity.propType]이 이 값이면 속성 바꿈이 아니라 새 항목 만들기다([ViewWriteEntity.value]는 ViewCreate JSON, pageId는 임시 값). */
const val VIEW_WRITE_CREATE = "create"

/**
 * [ViewWriteEntity.propType]이 이 값이면 행(노션 페이지)을 휴지통으로 보내기(`in_trash`, 영구 삭제 아님)다. [ViewWriteEntity.value]는 지운 행의 제목
 * (로컬 행이 없어져도 실패 기록에 제목을 남기려고), propId는 [VIEW_TRASH_PROP]이라 같은 페이지의 속성 바꿈을 덮어쓰지 않는다.
 */
const val VIEW_WRITE_TRASH = "trash"

/** 휴지통 쓰기의 propId. 노션 속성 ID는 URL 인코딩된 짧은 글자라 '!'로 시작하는 값은 없다. */
const val VIEW_TRASH_PROP = "!trash"

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
    /** v14: 보이는 속성의 쓰기 정보(속성 ID·종류·선택지)를 JSON으로. M5b부터 `role`이 달린 숨긴 읽은 쪽·전체 쪽도 들어 있다. 비어 있으면 아직 못 받았거나 읽기만 한다(ViewColumn). */
    @ColumnInfo(defaultValue = "") val columnInfo: String = "",
    /** v14: 위젯에서 마지막으로 바꾼 시각(epoch ms). 이보다 일찍 시작한 읽기는 결과를 덮어쓰지 않는다(보내는 도중에 받은 이전 값 방지). */
    @ColumnInfo(defaultValue = "0") val lastWriteAt: Long = 0,
    /** v17(Phase 11a): 노션 보기 `configuration` 원본 JSON(보드의 `group_by` 등). 없으면 빈 글자. */
    @ColumnInfo(defaultValue = "") val viewConfig: String = "",
    /** v17: 보기의 행을 상한(100) 안에서 다 받았는가. false면 노션에 행이 더 있다('N+ 더 보기'). */
    @ColumnInfo(defaultValue = "1") val rowsComplete: Boolean = true,
    /** v18(Phase 11c): 노션 보기 필터(보기 필터와 빠른 필터를 합친 JSON). 폰에서 항목이 필터에서 빠지는지 판정하고(IX-06) 새 항목의 값을 채우는(AD-01) 데 쓴다. 없으면 빈 글자. */
    @ColumnInfo(defaultValue = "") val filterJson: String = "",
)

/** 보기의 한 행(VW-02로 글자가 된 값). [cells]는 [VIEW_CELL_SEPARATOR]로 이은 열 순서의 값. */
@Entity(tableName = "view_rows", primaryKeys = ["viewId", "position"], indices = [Index("pageId")])
data class ViewRowEntity(
    val viewId: String,
    val position: Int,
    val pageId: String,
    val url: String,
    val cells: String,
    /** v14: 쓸 수 있는 속성의 원래 값(속성 ID → 체크·선택지 ID·숫자)을 JSON으로. 표시용 [cells]와 따로 둔다. */
    @ColumnInfo(defaultValue = "") val typed: String = "",
    /** v15(M5): 책 위젯이 그릴 표지 이미지 주소(https). 보기에 보이는 열과 무관하게 파일 속성에서 읽는다. 없으면 빈 글자. */
    @ColumnInfo(defaultValue = "") val coverUrl: String = "",
    /** v15(M5): 읽은 비율 0~1. 계산할 속성이 없으면 null. */
    val progress: Float? = null,
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
    /** v16(SY-05): 바꾸기 전 노션 값(JSON 원시값, 모르면 null). 보내기 직전에 노션 값과 비교해 충돌을 가린다. */
    val baseValue: String? = null,
    /**
     * v18(Phase 11c): 이 시각(epoch ms) 전에는 보내지 않는다. 위젯의 즉시 동작은 약 4초 뒤로 두어 연타를 한 번의 전송으로 합치고(IX-03)
     * 그 사이 되돌릴 수 있게 한다(IX-05). 0이면 바로 보낼 수 있다.
     */
    @ColumnInfo(defaultValue = "0") val sendAfter: Long = 0,
)

/**
 * v16(CN-02): 소스(데이터 소스)의 스키마 전체(속성 ID·이름·유형·옵션·상태 그룹·수식 결과 유형 등, 노션 응답 JSON)를 보기 동기화가 저장한다.
 * 같은 소스의 보기가 여럿이어도 한 줄이다. [fetchedAt]은 마지막으로 **바뀐** 스키마를 저장한 시각이다(같으면 다시 쓰지 않는다). 위젯·편집 시트는 이것으로 속성을 알고 노션을 부르지 않는다(캐시).
 */
@Entity(tableName = "source_schemas")
data class SourceSchemaEntity(
    @PrimaryKey val dataSourceId: String,
    val schemaJson: String,
    val fetchedAt: Long,
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

    /** 한 페이지의 행을 모든 보기에서 지운다(같은 페이지가 여러 보기에 있을 수 있다). */
    @Query("DELETE FROM view_rows WHERE pageId = :pageId")
    protected abstract suspend fun deleteRowsOfPage(pageId: String)

    /** 한 페이지의 보내지 않은 쓰기를 모두 지운다(휴지통으로 가는 페이지에 속성은 보낼 이유가 없다). */
    @Query("DELETE FROM view_writes WHERE pageId = :pageId")
    protected abstract suspend fun deleteWritesOfPage(pageId: String)

    /** 휴지통 전송을 기다리는 페이지. 보기를 새로 받아도 이 페이지의 행은 다시 넣지 않는다(노션에는 아직 있다). */
    @Query("SELECT pageId FROM view_writes WHERE propType = '$VIEW_WRITE_TRASH'")
    protected abstract suspend fun trashingPages(): List<String>

    @Query("UPDATE view_rows SET cells = :cells, typed = :typed, progress = :progress WHERE viewId = :viewId AND pageId = :pageId")
    protected abstract suspend fun updateRow(viewId: String, pageId: String, cells: String, typed: String, progress: Float?)

    @Upsert
    abstract suspend fun upsertSchema(schema: SourceSchemaEntity)

    @Query("SELECT * FROM source_schemas WHERE dataSourceId = :dataSourceId")
    abstract suspend fun schema(dataSourceId: String): SourceSchemaEntity?

    /** 이 페이지가 든 보기(전송이 끝난 뒤 그 보기를 다시 받는다, SY-06). */
    @Query("SELECT DISTINCT viewId FROM view_rows WHERE pageId = :pageId")
    abstract suspend fun viewsContaining(pageId: String): List<String>

    /** 처리기가 이 쓰기를 보내기 시작했다(노션 값을 읽기 전). [VIEW_WRITE_SENDING] 참고. 실패하면 [markWriteFailed]가 오류로 덮는다. */
    @Query("UPDATE view_writes SET lastError = 'Sending' WHERE id = :id")
    abstract suspend fun markWriteSending(id: Long)

    /** 이 소스를 쓰는 보기들(방금 만든 페이지는 어느 보기 행에도 아직 없어 소스로 찾는다, SY-06). */
    @Query("SELECT viewId FROM view_snapshots WHERE dataSourceId = :dataSourceId")
    abstract suspend fun viewIdsOfSource(dataSourceId: String): List<String>

    @Query("SELECT * FROM view_writes WHERE viewId = :viewId AND pageId = :pageId AND propId = :propId")
    protected abstract suspend fun writeFor(viewId: String, pageId: String, propId: String): ViewWriteEntity?

    @Insert
    protected abstract suspend fun insertWrite(write: ViewWriteEntity)

    @Query("DELETE FROM view_writes WHERE viewId = :viewId AND pageId = :pageId AND propId = :propId")
    protected abstract suspend fun deleteWrite(viewId: String, pageId: String, propId: String)

    @Query("DELETE FROM view_writes WHERE viewId = :viewId")
    protected abstract suspend fun deleteWrites(viewId: String)

    @Query("SELECT * FROM view_writes ORDER BY id LIMIT 1")
    abstract suspend fun firstWrite(): ViewWriteEntity?

    /** 보낼 때가 된([ViewWriteEntity.sendAfter] <= [now]) 쓰기 중 가장 먼저 쌓은 것. 아직 지연 중인 앞선 쓰기가 뒤의 쓰기를 막지 않는다. */
    @Query("SELECT * FROM view_writes WHERE sendAfter <= :now ORDER BY id LIMIT 1")
    abstract suspend fun firstDueWrite(now: Long): ViewWriteEntity?

    /** 한 보기에서 아직 지연 중인([ViewWriteEntity.sendAfter] > [now]) 쓰기, 쌓은 순서대로. */
    @Query("SELECT * FROM view_writes WHERE viewId = :viewId AND sendAfter > :now ORDER BY id")
    protected abstract suspend fun delayedWrites(viewId: String, now: Long): List<ViewWriteEntity>

    /** 한 보기에서 바꾸고 아직 노션에 보내지 않은 행(새 항목·휴지통 제외). 필터에서 빠지는 중인 행을 가리는 데 쓴다(IX-06). */
    @Query("SELECT DISTINCT pageId FROM view_writes WHERE viewId = :viewId AND propType NOT IN ('$VIEW_WRITE_CREATE', '$VIEW_WRITE_TRASH')")
    abstract fun observePendingPageIds(viewId: String): Flow<List<String>>

    /** 한 보기의 지연이 걸린 쓰기들의 전송 시각(위젯이 '되돌리기' 줄을 그릴지 정한다). 지난 시각도 담으니 세는 쪽이 지금과 견준다. */
    @Query("SELECT sendAfter FROM view_writes WHERE viewId = :viewId AND sendAfter > 0")
    abstract fun observePendingSendAfter(viewId: String): Flow<List<Long>>

    /** 아직 지연 중인 쓰기 중 가장 이른 전송 시각. 없으면 null(처리기를 다시 깨울 필요가 없다). */
    @Query("SELECT MIN(sendAfter) FROM view_writes WHERE sendAfter > :now")
    abstract suspend fun earliestSendAfter(now: Long): Long?

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
     * 행을 휴지통으로 보내는 쓰기를 쌓고 그 페이지의 로컬 행을 모든 보기에서 지운다(한 트랜잭션). 보기나 행이 없으면 쌓지 않고 false다.
     * 그 페이지의 앞선 속성 바꿈은 지운다(휴지통으로 갈 페이지에 보낼 이유가 없다). 같은 소스의 모든 보기에 쓴 시각을 남겨, 휴지통 전송 전에
     * 시작된 새로 받기가 지운 행을 되살리지 못하게 한다(그 뒤 새로 받는 것은 [replace]가 이 페이지를 빼고 넣는다). 노션 값을 읽지 않으니 기준값은 없다.
     * [title]은 지운 행의 제목이다(로컬 행이 없어진 뒤 실패 기록에 제목을 남기려고 value에 둔다).
     */
    @Transaction
    open suspend fun enqueueTrash(viewId: String, pageId: String, title: String, createdAt: Long): Boolean {
        val snapshot = snapshot(viewId) ?: return false
        row(viewId, pageId) ?: return false
        deleteRowsOfPage(pageId)
        deleteWritesOfPage(pageId)
        insertWrite(
            ViewWriteEntity(
                viewId = viewId, pageId = pageId, dataSourceId = snapshot.dataSourceId, propId = VIEW_TRASH_PROP, propType = VIEW_WRITE_TRASH,
                value = title, createdAt = createdAt,
            ),
        )
        viewIdsOfSource(snapshot.dataSourceId).forEach { markWritten(it, createdAt) }
        return true
    }

    /**
     * 되돌리기(IX-05): 한 보기에서 지연 중인 쓰기를 모두 지운다. 속성 바꿈이면 그 행을 [restore]가 돌려주는 값으로 되돌린다(null이면 행은 그대로).
     * 새 항목·휴지통 쓰기는 행이 없어 쓰기만 지운다. 한 트랜잭션이고, 지운 쓰기 수를 돌려준다.
     */
    @Transaction
    open suspend fun undoDelayed(
        viewId: String,
        now: Long,
        restore: (ViewSnapshotEntity, ViewRowEntity, ViewWriteEntity) -> ViewRowEntity?,
    ): Int {
        val snapshot = snapshot(viewId) ?: return 0
        val writes = delayedWrites(viewId, now)
        writes.forEach { write ->
            if (write.propType != VIEW_WRITE_CREATE && write.propType != VIEW_WRITE_TRASH) {
                row(viewId, write.pageId)?.let { current ->
                    restore(snapshot, current, write)?.let { updateRow(it.viewId, it.pageId, it.cells, it.typed, it.progress) }
                }
            }
            deleteWriteById(write.id)
        }
        return writes.size
    }

    /**
     * 로컬 행을 먼저 고치고(낙관적) 같은 속성의 이전 대기분을 지운 뒤 쓰기 큐에 쌓는다. 한 트랜잭션이다.
     */
    @Transaction
    open suspend fun enqueue(row: ViewRowEntity, write: ViewWriteEntity) {
        updateRow(row.viewId, row.pageId, row.cells, row.typed, row.progress)
        // 같은 속성의 대기분이 있으면 그 기준값(노션에 있던 값)을 이어받는다. 새 쓰기의 기준을 로컬에서 바꾼 값으로 잡으면 충돌 확인이 어긋난다.
        // 단 이미 보내는 중이면 그 값은 노션에 반영되는 중이라 이어받지 않는다(보낸 값을 노션의 다른 편집으로 보게 된다).
        val prior = writeFor(write.viewId, write.pageId, write.propId)
        deleteWrite(write.viewId, write.pageId, write.propId)
        insertWrite(if (prior != null && prior.lastError != VIEW_WRITE_SENDING) write.copy(baseValue = prior.baseValue) else write)
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

    /** 한 보기의 앞쪽 [limit]권 표지 주소(저장된 행 기준, 위치 순). */
    @Query("SELECT coverUrl FROM view_rows WHERE viewId = :viewId AND coverUrl != '' ORDER BY position LIMIT :limit")
    abstract suspend fun coverUrls(viewId: String, limit: Int): List<String>

    /** 등록한 모든 보기의 표지 주소(표지 파일 정리에서 남길 것을 정한다). */
    @Query("SELECT DISTINCT coverUrl FROM view_rows WHERE coverUrl != ''")
    abstract suspend fun coverUrls(): List<String>

    @Query("DELETE FROM view_snapshots WHERE viewId = :viewId")
    protected abstract suspend fun deleteView(viewId: String)

    /** 어떤 보기도 쓰지 않는 소스의 스키마 캐시를 지운다. */
    @Query("DELETE FROM source_schemas WHERE dataSourceId NOT IN (SELECT dataSourceId FROM view_snapshots)")
    protected abstract suspend fun deleteUnusedSchemas()

    /** 보기 등록을 지운다(행 스냅샷과 아직 보내지 않은 바꿈 포함: 등록을 지우면 그 보기에서 한 바꿈은 노션에 가지 않는다). */
    @Transaction
    open suspend fun remove(viewId: String) {
        deleteRows(viewId)
        deleteWrites(viewId)
        deleteView(viewId)
        deleteUnusedSchemas()
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
        val trashing = trashingPages().toSet()
        insertRows(if (trashing.isEmpty()) rows else rows.filter { it.pageId !in trashing })
    }
}
