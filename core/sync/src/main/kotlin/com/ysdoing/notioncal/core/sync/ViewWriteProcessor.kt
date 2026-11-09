package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.ConflictEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.SentWriteEntity
import com.ysdoing.notioncal.core.data.db.VIEW_WRITE_CREATE
import com.ysdoing.notioncal.core.data.db.VIEW_WRITE_TRASH
import com.ysdoing.notioncal.core.data.db.ViewWriteEntity
import com.ysdoing.notioncal.core.data.view.FilterMatch
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewFilter
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewWriter
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionReader
import com.ysdoing.notioncal.core.notion.NotionWriter
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.mapping.encodeViewCreate
import com.ysdoing.notioncal.core.notion.mapping.encodeViewValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** 한 항목을 보낸 결과: 보냄 / 노션 값이 이겨 충돌로 남기고 버림 / 보낼 수 없어 '노션 반영 실패'로 버림. */
private enum class SendResult { SENT, SUPERSEDED, REJECTED }

/** 한 번 처리한 결과. [retryError]가 있으면 일시 오류로 멈춘 것이라 나중에 다시 실행한다. */
data class ViewWriteOutcome(val sent: Int, val dropped: Int, val remaining: Int, val retryError: NotionError?)

/**
 * 위젯에서 한 보기 행 바꿈과 새 항목(view_writes)을 노션으로 보낸다. 만든 순서대로 하나씩 보내고, 일시 오류(연결·5xx·속도 제한·인증)면
 * 큐 전체를 멈춰 다음 실행에서 이어 간다. 노션이 거부(400)하거나 페이지·속성이 없으면, 값이 깨졌으면, 바꿈이 너무 여러 번 실패하면
 * 그 항목을 버리고 '노션 반영 실패'로 남긴다(앱 월 화면 머리의 알림). 바꿈은 그 보기를 다시 받아 위젯을 노션 값으로 되돌린다.
 * 새 항목은 일시 오류나 공유 끊김으로는 버리지 않고 보낼 때까지 다시 시도한다(사용자가 입력한 글이라 잃지 않게). 새 항목을 만든 보기도 다시 받아 새 행을 보인다.
 * 일정 아웃박스([OutboxProcessor])와 따로 도는 큐라 서로의 순서·충돌 규칙에 영향이 없다.
 */
@Singleton
class ViewWriteProcessor @Inject constructor(
    private val reader: NotionReader,
    private val writer: NotionWriter,
    private val db: NotionCalDatabase,
    private val views: ViewSync,
    private val widgets: WidgetRefresher,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val dao get() = db.viewDao()

    private class Drain(val outcome: ViewWriteOutcome, val refreshViews: Set<String>)

    suspend fun process(): ViewWriteOutcome = withContext(io) {
        // 보내는 도중에 작업이 취소돼도(지연 요청이 새로 걸려 실행 중인 작업을 갈아 끼운다) 그 쓰기는 끝까지 보낸다: 보내는 중 상태로 남거나 중복으로 보내지 않게.
        val drain = withContext(NonCancellable) { mutex.withLock { drain() } }
        // 버린 바꿈의 보기(노션 값으로 되돌림)와 새 항목을 만든 보기(새 행을 보임)는 큐를 다 비운 뒤, 잠금 밖에서 다시 받는다.
        // 연결 문제로 멈췄다면 지금 받아도 실패하니 다음 동기화가 한다. 쓰기 때문에 건너뛴 보기도 이제 받는다(그 사이 새로고침이 사라지지 않게).
        if (drain.outcome.retryError == null) {
            drain.refreshViews.forEach { views.sync(it) }
            views.syncSkipped()
        }
        // 다시 받을 보기가 있으면 위젯은 그 뒤에 한 번만 그린다(보낸 직후 행이 정상 모양으로 깜빡였다 사라지지 않게).
        if (drain.refreshViews.isNotEmpty()) widgets.requestUpdate()
        drain.outcome
    }

    private suspend fun drain(): Drain {
        val schemas = HashMap<String, DataSourceDto>()
        val refresh = LinkedHashSet<String>()
        var sent = 0
        var dropped = 0
        var retryError: NotionError? = null
        // 되돌릴 수 있는 시간이 지난 보낸 기록은 지우고, 위젯의 '보낸 변경 되돌리기' 줄도 이때 사라지게 다시 그린다.
        val expired = dao.deleteSentBefore(clock.millis() - ViewWriter.SENT_UNDO_WINDOW_MS)
        // 공유가 끊긴 DB의 새 항목은 이번 실행에 건너뛴다(사용자가 입력한 글이라 버리지 않고, 공유를 다시 켜면 보낸다).
        // 그 항목만 건너뛴다: 같은 DB의 속성 바꿈·휴지통은 예전처럼 '노션 반영 실패'로 정리돼 큐에 끝없이 남지 않는다.
        val skipped = HashSet<Long>()
        while (true) {
            val write = dao.firstDueWrite(clock.millis(), skipped) ?: break // 지연 중인 쓰기(sendAfter가 아직 안 됨)는 남겨 둔다
            val result = try {
                trySend(write, schemas)
            } catch (e: NotionException) {
                val error = e.error
                when {
                    error == NotionError.NotShared && write.propType == VIEW_WRITE_CREATE -> {
                        dao.markWriteError(write.id, error.storedName())
                        skipped += write.id
                        continue
                    }
                    error is NotionError.Validation || error == NotionError.NotShared -> SendResult.REJECTED
                    else -> {
                        // 서버 오류만 이 쓰기의 시도로 센다. 연결·속도 제한·인증은 이 쓰기 탓이 아니라, 세면 오래 꺼져 있던 폰에서 바꿈을 버린다.
                        if (error is NotionError.Server) dao.markWriteFailed(write.id, error.storedName()) else dao.markWriteError(write.id, error.storedName())
                        retryError = error
                        break
                    }
                }
            }
            when (result) {
                SendResult.SENT -> {
                    dao.deleteWriteById(write.id)
                    logSent(write)
                    sent++
                    if (write.propType == VIEW_WRITE_CREATE) refresh += write.viewId // 새 행이 위젯에 나타나게
                    else if (write.propType != VIEW_WRITE_TRASH && leavesFilter(write)) refresh += write.viewId // 바뀐 행이 필터에서 빠져 위젯에서 사라지게(IX-06)
                }
                SendResult.SUPERSEDED -> {
                    dao.deleteWriteById(write.id) // 노션 값이 이겼고 충돌로 남겼다
                    refresh += write.viewId
                    dropped++
                }
                SendResult.REJECTED -> {
                    reject(write)
                    // 거부된 휴지통은 행이 돌아와야 하고, 같은 소스의 다른 보기에서도 이미 지웠으므로 모두 다시 받는다.
                    if (write.propType == VIEW_WRITE_TRASH) refresh += dao.viewIdsOfSource(write.dataSourceId) else refresh += write.viewId
                    dropped++
                }
            }
        }
        if ((sent + dropped > 0 || expired > 0) && refresh.isEmpty()) widgets.requestUpdate() // 다시 받을 보기가 있으면 process()가 받은 뒤 그린다
        return Drain(ViewWriteOutcome(sent, dropped, dao.pendingWriteCount(), retryError), refresh)
    }

    /**
     * 속성 바꿈을 보냈으면 이전 값(기준값, 비어 있었으면 null)과 보낸 값을 기록한다. 새 항목·휴지통과 되돌리는 쓰기([ViewWriter.UNDO_BATCH_ID])는 기록하지 않는다.
     * 기록은 다음 처리 때 지우고 위젯을 다시 그린다(2분 뒤 줄을 거둔다). 그 시각에 맞춘 깨우기는 두지 않는다: 지연 전송 깨우기와 같은 작업 이름을 갈아 끼워
     * 4초 지연 쓰기의 전송을 늦출 수 있어서다. 그래서 줄은 만료 뒤 다음 위젯 갱신까지 남을 수 있고, 눌러도 아무 일 없이 사라진다.
     */
    private suspend fun logSent(write: ViewWriteEntity) {
        if (write.propType == VIEW_WRITE_CREATE || write.propType == VIEW_WRITE_TRASH || write.batchId == ViewWriter.UNDO_BATCH_ID) return
        dao.insertSent(
            SentWriteEntity(
                viewId = write.viewId, pageId = write.pageId, propId = write.propId, propType = write.propType,
                priorValue = write.baseValue, sentValue = write.value, sentAt = clock.millis(), batchId = write.batchId,
            ),
        )
    }

    /**
     * 한 항목을 보낸다. 끝없이 실패하는 바꿈 하나가 그 보기의 새로 받기를 영원히 막지 않게 [MAX_ATTEMPTS]번 실패하면 버린다.
     * 새 항목은 잃지 않으려고 횟수로 버리지 않는다. 일시 오류는 던져 큐 전체를 멈춘다.
     */
    private suspend fun trySend(write: ViewWriteEntity, schemas: MutableMap<String, DataSourceDto>): SendResult {
        val creating = write.propType == VIEW_WRITE_CREATE
        if (!creating && write.attempts >= MAX_ATTEMPTS) return SendResult.REJECTED
        if (write.propType == VIEW_WRITE_TRASH) return trash(write)
        val schema = schemas.getOrPut(write.dataSourceId) { reader.dataSource(write.dataSourceId) }
        return if (creating) create(write, schema) else update(write, schema)
    }

    /** 페이지를 휴지통으로 보낸다. 스키마도 노션 값도 읽지 않는다(마지막 쓰기가 이긴다). 이미 없으면(null) 목적은 이룬 것이라 보냄이다. */
    private suspend fun trash(write: ViewWriteEntity): SendResult {
        writer.trashPage(write.pageId)
        return SendResult.SENT
    }

    /**
     * 속성 하나를 바꾼다(SY-05). 기준값이 있으면 보내기 직전에 노션 값을 다시 읽어, 그 사이 노션에서 바뀌었다면 보내지 않고 노션 값을 남긴다
     * (충돌로 기록, [SendResult.SUPERSEDED]). 기준값이 없는 쓰기(이전 버전에서 쌓인 것)는 확인 없이 보낸다.
     */
    private suspend fun update(write: ViewWriteEntity, schema: DataSourceDto): SendResult {
        // 키는 보내는 순간의 속성 이름이다. 속성이 지워졌다면 ID를 키로 보내도 노션이 오류 없이 무시하므로 보내지 않고 되돌린다.
        val property = schema.properties.values.firstOrNull { it.id == write.propId } ?: return SendResult.REJECTED
        val value = ViewJson.parseValue(write.value) ?: return SendResult.REJECTED
        val properties = encodeViewValue(property.name, write.propType, value) ?: return SendResult.REJECTED
        if (write.baseValue != null) {
            dao.markWriteSending(write.id) // 읽고 쓰는 동안 같은 속성을 또 바꾸면 기준값을 이어받지 않게 표시한다
            val page = reader.pageOrNull(write.pageId)?.takeIf { !it.inTrash } ?: return SendResult.REJECTED
            val column = viewColumn(property) ?: ViewColumn(property.id, property.name, property.type)
            val remote = typedValue(column, page.properties[property.name] as? JsonObject)
            // 노션 값이 이미 내 값이면(보낸 요청이 닿았는데 응답이 끊겼다) 충돌이 아니고 다시 쓰지도 않는다.
            if (ViewJson.valueText(remote) == write.value) return SendResult.SENT
            if (remote.toString() != write.baseValue) {
                recordConflict(write, column, value, remote)
                return SendResult.SUPERSEDED
            }
        }
        writer.updatePage(write.pageId, properties)
        return SendResult.SENT
    }

    /**
     * 보낸 바꿈 때문에 행이 보기 필터를 벗어났는지(또는 폰에서 판정할 수 없는지). 그러면 그 보기를 다시 받아 노션이 가리는 대로 맞춘다(IX-06 '반영 대기').
     * 필터가 없거나 행이 필터에 맞으면 다시 받지 않는다(요청을 늘리지 않으려고). 폰에서 판정할 수 없는 필터(상대 날짜·수식 등)는 PRD IX-06대로
     * 전송 뒤마다 한 번 다시 받는다(지연 4초 안의 바꿈은 한 번의 전송으로 합쳐져 탭마다가 아니다).
     */
    private suspend fun leavesFilter(write: ViewWriteEntity): Boolean {
        val snapshot = dao.snapshot(write.viewId)?.takeIf { it.filterJson.isNotBlank() } ?: return false
        val row = dao.row(write.viewId, write.pageId) ?: return false
        return ViewFilter.parse(snapshot.filterJson).matches(ViewJson.columns(snapshot.columnInfo), ViewJson.typed(row.typed)) != FilterMatch.YES
    }

    /** 충돌 기록: 배너에 옵션 ID·JSON이 아니라 사람이 읽는 글자(옵션 이름·날짜·숫자)로 남긴다. */
    private suspend fun recordConflict(write: ViewWriteEntity, column: ViewColumn, mine: JsonElement, remote: JsonElement) {
        db.conflictDao().insert(
            ConflictEntity(
                pageId = write.pageId, title = rowTitle(write), field = "PROP:${column.name}",
                localValue = column.display(mine), remoteValue = column.display(remote),
                kind = OutboxProcessor.KIND_CONFLICT, at = clock.millis(),
            ),
        )
    }

    private suspend fun rowTitle(write: ViewWriteEntity): String =
        dao.row(write.viewId, write.pageId)?.cells?.substringBefore(VIEW_CELL_SEPARATOR).orEmpty()

    /** 새 항목을 만든다. 값이 깨졌거나 제목 속성이 지워졌으면 보내지 않고 [SendResult.REJECTED]. */
    private suspend fun create(write: ViewWriteEntity, schema: DataSourceDto): SendResult {
        val spec = ViewJson.parseCreate(write.value) ?: return SendResult.REJECTED
        val titleKey = schema.properties.values.firstOrNull { it.id == write.propId }?.name ?: return SendResult.REJECTED
        // 날짜 속성이 지워졌다면 날짜만 빼고 항목은 만든다(제목이 더 중요하다).
        val dateKey = spec.dateProp?.let { id -> schema.properties.values.firstOrNull { it.id == id }?.name }
        // 값 묶음은 보내는 순간의 속성 이름·유형으로 인코딩한다. 그 사이 지워진 속성은 건너뛴다(항목은 만든다).
        val extra = spec.props.mapNotNull { (id, value) ->
            schema.properties.values.firstOrNull { it.id == id }?.let { property -> encodeViewValue(property.name, property.type, value) }
        }.fold(emptyMap<String, JsonElement>()) { all, one -> all + one }
        dao.markWriteSending(write.id) // 만드는 동안 해빗 체크가 이 새 항목의 체크를 고치려 해도 못 고친다(고쳐도 이미 보낸 요청에 안 실린다). 실패하면 오류 이름이 덮는다
        writer.createPage(write.dataSourceId, encodeViewCreate(titleKey, spec.title, dateKey, spec.date, JsonObject(extra)))
        return SendResult.SENT
    }

    /** 버린 항목을 앱 월 화면의 '노션 반영 실패'에 남긴다(조용히 사라지지 않게). */
    private suspend fun reject(write: ViewWriteEntity) {
        dao.deleteWriteById(write.id)
        val (title, kind) = when (write.propType) {
            VIEW_WRITE_CREATE -> ViewJson.parseCreate(write.value)?.title.orEmpty() to "CREATE"
            VIEW_WRITE_TRASH -> write.value.orEmpty() to "TRASH" // 로컬 행은 이미 지웠으니 큐에 남긴 제목을 쓴다
            else -> rowTitle(write) to "UPDATE"
        }
        db.conflictDao().insert(
            ConflictEntity(
                pageId = write.pageId, title = title, field = "PAGE", localValue = kind, remoteValue = null,
                kind = OutboxProcessor.KIND_REJECTED, at = clock.millis(),
            ),
        )
    }

    private companion object {
        /** 이만큼 실패하면 버린다(15분 주기로 약 2시간 반). */
        const val MAX_ATTEMPTS = 10
    }
}
