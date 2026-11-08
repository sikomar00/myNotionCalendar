package com.ysdoing.notioncal.core.sync

import android.util.Log
import androidx.room.withTransaction
import com.ysdoing.notioncal.core.data.DateValue
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.ExtraProp
import com.ysdoing.notioncal.core.data.PropValue
import com.ysdoing.notioncal.core.data.parsePropCatalog
import com.ysdoing.notioncal.core.data.EventSnapshot
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.OutboxJson
import com.ysdoing.notioncal.core.data.OutboxOp
import com.ysdoing.notioncal.core.data.SelectValue
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.applying
import com.ysdoing.notioncal.core.data.db.ConflictEntity
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.SYNC_LOG_OUTBOX
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import com.ysdoing.notioncal.core.data.db.OutboxEntity
import com.ysdoing.notioncal.core.data.parseColorOptions
import com.ysdoing.notioncal.core.data.toEntity
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.http.RequestCounter
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionReader
import com.ysdoing.notioncal.core.notion.NotionWriter
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.mapping.NotionPageMapper
import com.ysdoing.notioncal.core.notion.mapping.PageWrite
import com.ysdoing.notioncal.core.notion.mapping.PropertyEncoder
import com.ysdoing.notioncal.core.notion.mapping.QueryFilters
import com.ysdoing.notioncal.core.notion.mapping.RemoteEvent
import com.ysdoing.notioncal.core.notion.mapping.WriteDate
import com.ysdoing.notioncal.core.notion.mapping.LedgerWrite
import com.ysdoing.notioncal.core.data.ledger.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 한 번 처리한 결과. [retryError]가 있으면 일시 오류로 멈춘 것이라 나중에 다시 실행한다. */
data class OutboxOutcome(
    val sent: Int,
    val remaining: Int,
    val retryError: NotionError?,
    /** 이번에 노션에 보낸 페이지(생성은 만들어진 진짜 id). */
    val sentPageIds: Set<String> = emptySet(),
    /** 새 페이지를 만든 소스. 만든 페이지는 어느 보기 행에도 아직 없어 소스의 보기를 다시 받는다(SY-06). */
    val createdInSources: Set<String> = emptySet(),
)

/**
 * 아웃박스 작업을 노션에 보낸다(NS-04, NS-05, PRD §8.5-5·6·8).
 *
 * - 작업은 만든 순서대로 하나씩 보낸다. 일시 오류(연결·5xx·속도 제한·인증)가 나면 큐 전체를 멈추고 다음 실행에서 이어
 *   같은 페이지의 순서가 뒤바뀌지 않게 한다.
 * - UPDATE는 보내기 직전 페이지를 다시 읽어, 노션에서도 바뀐 필드는 노션 값을 남기고 충돌로 기록한다.
 * - 노션이 400으로 거부하면 로컬 변경을 되돌리고 기록한다.
 */
@Singleton
class OutboxProcessor @Inject constructor(
    private val reader: NotionReader,
    private val writer: NotionWriter,
    private val db: NotionCalDatabase,
    private val clock: Clock,
    private val widgetRefresher: WidgetRefresher,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val requests: RequestCounter = RequestCounter(),
) {
    private val mutex = Mutex()
    private val outbox get() = db.outboxDao()
    private val events get() = db.eventDao()

    suspend fun process(): OutboxOutcome = withContext(io) {
        mutex.withLock {
            val schemas = HashMap<String, DataSourceDto>()
            val startedAt = clock.millis()
            val requestsBefore = requests.total
            var sent = 0
            val sentPages = LinkedHashSet<String>()
            val createdSources = LinkedHashSet<String>()
            var retryError: NotionError? = null
            // 공유가 끊긴 DB의 작업은 이번 주기에 건너뛴다(그 DB 안의 순서는 지키고, 다른 DB의 쓰기는 계속 보낸다).
            val skippedSources = HashSet<String>()
            while (true) {
                val row = outbox.all().firstOrNull { it.dataSourceId !in skippedSources } ?: break
                val source = db.dataSourceDao().get(row.dataSourceId)
                if (source == null) {
                    // 등록을 지운 DB의 작업은 보낼 곳이 없다.
                    outbox.delete(row.id)
                    continue
                }
                val mapping = source.toMapping()
                if (mapping == null) {
                    // 날짜 없는 DB(🌱 습관)에는 앱이 쓰지 않는다(HB-09 전).
                    outbox.delete(row.id)
                    continue
                }
                if (!row.isReadable()) {
                    // 작업 종류나 바꿈을 읽지 못하면 몇 번을 보내도 같다. 이 작업 하나가 큐 전체를 막지 않게 정리하고 다음 작업을 보낸다.
                    quarantine(row, source)
                    continue
                }
                try {
                    val schema = schemas.getOrPut(source.dataSourceId) { reader.dataSource(source.dataSourceId) }
                    val mapper = NotionPageMapper(mapping, schema)
                    val encoder = PropertyEncoder(mapping, source.doneOption, source.undoneOption, schema)
                    when (OutboxOp.valueOf(row.op)) {
                        OutboxOp.CREATE -> {
                            sentPages += create(row, source, mapper, encoder)
                            createdSources += source.dataSourceId
                        }
                        OutboxOp.UPDATE -> {
                            update(row, source, mapper, encoder)
                            sentPages += row.pageId
                        }
                        OutboxOp.TRASH -> {
                            trash(row)
                            sentPages += row.pageId
                        }
                    }
                    sent++
                } catch (e: NotionException) {
                    when {
                        e.error is NotionError.Validation -> quarantine(row, source)
                        e.error == NotionError.NotShared -> {
                            // 그 DB만 이번 주기에 건너뛴다. 작업은 남기고(공유를 다시 켜면 보낸다), 배너는 읽기 동기화의 같은 오류가 띄운다.
                            // 결과의 오류로 올리지 않는다: 다른 DB의 보낸 일정이 든 보기를 바로 다시 받는 일(SY-06)이 막히지 않게.
                            outbox.markError(row.id, e.error.storedName())
                            skippedSources += row.dataSourceId
                        }
                        else -> {
                            // 연결·요청 제한·서버 오류는 일시적일 수 있어 순서를 지키려고 큐를 멈추고 다음 주기에 다시 보낸다(고친 내용을 버리지 않는다).
                            // shortcut: 늘 같은 4xx(예: 413)로 실패하는 바꿈은 큐를 계속 막는다. 오류 종류를 일시·영구로 나눌 때 그 바꿈만 정리한다.
                            if (row.op != OutboxOp.CREATE.name) outbox.markAttempt(row.id)
                            outbox.markError(row.id, e.error.storedName())
                            retryError = e.error
                            break
                        }
                    }
                }
            }
            if (sent > 0) widgetRefresher.requestUpdate()
            if (sent > 0 || retryError != null) logCycle(startedAt, requestsBefore, sent, retryError)
            OutboxOutcome(sent, outbox.all().size, retryError, sentPages, createdSources)
        }
    }

    /** PF-11·SY-04: 이번 아웃박스 주기의 보낸 수와 요청 수를 sync_log에 남긴다(헤더의 '마지막 동기화'에는 쓰이지 않는 종류). */
    private suspend fun logCycle(startedAt: Long, requestsBefore: Long, sent: Int, error: NotionError?) {
        db.syncLogDao().record(
            SyncLogEntity(
                at = clock.millis(), trigger = SYNC_LOG_OUTBOX, result = if (error == null) "OK" else "ERROR",
                durationMs = clock.millis() - startedAt, pulled = 0, pushed = sent, error = error?.storedName(),
                requests = (requests.total - requestsBefore).toInt(),
            ),
        )
    }

    /** 노션에 만들어진 진짜 페이지 id를 돌려준다. */
    private suspend fun create(row: OutboxEntity, source: DataSourceEntity, mapper: NotionPageMapper, encoder: PropertyEncoder): String {
        val change = decodeChange(row.changes)
        // 지난 시도에서 실제로 보낸 내용(없으면 지금 내용). 끊긴 생성을 찾을 때와, 그 뒤에 고친 것을 가를 때 쓴다.
        val lastSent = row.baseValues?.let(::decodeChange) ?: change
        // §8.5-8: 지난번 생성이 시간 초과·취소로 끝났다면 실제로는 만들어졌을 수 있다. 다시 보내기 전에 찾아본다.
        val existing = if (row.attempts > 0) findCreated(row, source, mapper, lastSent) else null
        val page = existing ?: run {
            // 응답을 못 받고 끊겨도 노션이 이미 만들었을 수 있다: 시도를 세고 보낸 내용을 남겨 다음에 먼저 찾아본다(SY-08 ②).
            outbox.markSending(row.id, OutboxJson.encodeToString(EventChange.serializer(), change))
            writer.createPage(source.dataSourceId, encoder.encode(change.toPageWrite(parsePropCatalog(source.propCatalog))))
        }
        val sentNow = if (existing != null) lastSent else change
        db.withTransaction { finishCreate(row, page, sentNow, source, mapper) }
        return page.id
    }

    private suspend fun finishCreate(row: OutboxEntity, page: PageDto, sent: EventChange, source: DataSourceEntity, mapper: NotionPageMapper) {
        // HB-02: 보내는 사이 사용자가 지웠다(EventWriter.trash가 잠금 밖에서 임시 행과 이 작업을 지움). 만들어진 페이지를
        // 반영하면 체크가 되살아나므로 반영하지 않고 휴지통 작업을 대신 쌓는다(같은 실행에서 이어 보낸다).
        val current = outbox.get(row.id)
        if (current == null) {
            outbox.insert(
                OutboxEntity(
                    pageId = page.id, dataSourceId = source.dataSourceId, op = OutboxOp.TRASH.name, changes = "{}",
                    baseValues = null, baseLastEdited = null, createdAt = clock.millis(),
                ),
            )
            return
        }
        // 동기화가 이미 이 페이지를 받아 뒀다면(끊긴 생성 뒤 같은 실행의 읽기) 그 행이 진짜이니 임시 일정만 지운다(키 충돌 방지).
        if (page.id != row.pageId && events.get(page.id) != null) events.delete(listOf(row.pageId)) else events.renamePage(row.pageId, page.id)
        outbox.renamePage(row.pageId, page.id)
        outbox.delete(row.id)
        val created = mapper.map(page).event
        // SY-08 ③: 보낸 뒤에 합쳐진 편집은 만들어진 페이지에 UPDATE로 이어 보낸다(지우면 유실된다). 기준값은 보낸 값이라, 그 사이
        // 노션에서 같은 필드를 고쳤다면 충돌로 가려진다(노션 값 우선).
        val edit = decodeChange(current.changes).changedFrom(sent)
        if (!edit.isEmpty) queueEdit(page.id, source, edit, sent.restrictedTo(edit))
        applyServerPage(page.id, created, source)
    }

    private suspend fun queueEdit(pageId: String, source: DataSourceEntity, edit: EventChange, base: EventChange) {
        outbox.insert(
            OutboxEntity(
                pageId = pageId, dataSourceId = source.dataSourceId, op = OutboxOp.UPDATE.name,
                changes = OutboxJson.encodeToString(EventChange.serializer(), edit),
                baseValues = OutboxJson.encodeToString(EventChange.serializer(), base), baseLastEdited = null, createdAt = clock.millis(),
            ),
        )
    }

    /**
     * 끊긴 생성이 이미 노션에 만들어졌는지 찾아본다: 보냈던 제목·날짜와 같은 페이지. 그 사이 로컬에서 제목을 고쳤어도 보낸 제목으로 찾는다.
     * 같은 제목·날짜로 먼저 만든 다른 일정의 페이지를 피하려고 아직 로컬에 없는 페이지를 먼저 쓰고, 동기화가 이미 받아 둔 페이지도 후보다.
     * 이 조회는 미리 해 보는 확인일 뿐이라 노션이 거부(400)해도 못 찾은 것으로 보고 생성을 계속한다(shortcut: 그러면 중복이 생길 수
     * 있다. 사용자의 새 일정을 잃는 것보다 낫다고 보았다).
     */
    private suspend fun findCreated(row: OutboxEntity, source: DataSourceEntity, mapper: NotionPageMapper, sent: EventChange): PageDto? {
        val title = sent.title ?: return null
        val since = Instant.ofEpochMilli(row.createdAt).minus(CREATE_LOOKBACK)
        val found = try {
            reader.queryAll(source.dataSourceId, QueryFilters.createdWithTitle(source.titlePropId, title.trim(), since))
        } catch (e: NotionException) {
            if (e.error is NotionError.Validation) return null else throw e
        }
        val sentDate = sent.date?.startDate
        // shortcut: 후보가 모두 이미 로컬에 있으면(동기화가 받아 둔 페이지) 그중 하나를 이 작업의 것으로 본다. 같은 제목·날짜로 1분 안에
        // 둘을 만들고 둘째가 끊기면 둘째를 만들지 않는다(조용히 합쳐짐). 끊긴 생성이 동기화에 먼저 읽힌 흔한 경우의 중복을 막는 쪽을 택했다.
        val candidates = found.pages.filter { !it.inTrash && (sentDate == null || mapper.map(it).event?.startDate?.toString() == sentDate) }
        return candidates.firstOrNull { events.get(it.id) == null } ?: candidates.firstOrNull()
    }

    private suspend fun update(row: OutboxEntity, source: DataSourceEntity, mapper: NotionPageMapper, encoder: PropertyEncoder) {
        val remote = reader.pageOrNull(row.pageId)
        if (remote == null || remote.inTrash) {
            remoteDeleted(row)
            return
        }
        val change = decodeChange(row.changes)
        val base = row.baseValues?.let(::decodeChange) ?: EventChange()
        val remoteEvent = mapper.map(remote).event
        // last_edited_time은 분 단위로 내림되어 같은 분 안의 노션 수정을 놓친다(실기기 확인). 그래서 시각과 상관없이
        // 방금 다시 읽은 서버 값과 기준값을 필드마다 비교한다(§8.5-6).
        val remoteValues = remoteEvent?.valuesFor(change) ?: EventChange()
        val conflicts = conflictingFields(change, base, remoteValues)
        conflicts.forEach { field ->
            db.conflictDao().insert(
                ConflictEntity(
                    pageId = row.pageId, title = remoteEvent?.title.orEmpty(), field = field.name,
                    localValue = field.describe(change), remoteValue = field.describe(remoteValues),
                    kind = KIND_CONFLICT, at = clock.millis(),
                ),
            )
        }
        // EX-07 추가 속성도 속성 하나씩 같은 규칙(노션 값 우선)으로 판정한다.
        val catalog = parsePropCatalog(source.propCatalog)
        val propConflicts = conflictingProps(change, base, remoteValues)
        propConflicts.forEach { id ->
            db.conflictDao().insert(
                ConflictEntity(
                    pageId = row.pageId, title = remoteEvent?.title.orEmpty(),
                    field = "PROP:" + (catalog.firstOrNull { it.id == id }?.name ?: id),
                    localValue = change.props?.get(id)?.describe(), remoteValue = remoteValues.props?.get(id)?.describe(),
                    kind = KIND_CONFLICT, at = clock.millis(),
                ),
            )
        }
        val toSend = conflicts.fold(change) { acc, field -> field.drop(acc) }
            .let { c -> if (propConflicts.isEmpty()) c else c.copy(props = c.props?.minus(propConflicts.toSet())) }
        val page = if (toSend.isEmpty) remote else writer.updatePage(row.pageId, encoder.encode(toSend.toPageWrite(catalog)))
        db.withTransaction {
            outbox.delete(row.id)
            applyServerPage(row.pageId, mapper.map(page).event, source)
        }
    }

    private suspend fun trash(row: OutboxEntity) {
        writer.trashPage(row.pageId) // 이미 없으면(404) null이고, 그래도 목적은 이룬 것이다.
        outbox.delete(row.id)
    }

    /** §12 충돌 3: 폰에서 고친 항목을 노션에서 지웠다면 로컬에서도 지우고, 남은 작업을 폐기하고 기록한다. */
    private suspend fun remoteDeleted(row: OutboxEntity) = db.withTransaction {
        val title = events.get(row.pageId)?.title.orEmpty()
        events.delete(listOf(row.pageId))
        outbox.deleteForPage(row.pageId)
        db.conflictDao().insert(
            ConflictEntity(
                pageId = row.pageId, title = title, field = "PAGE", localValue = null, remoteValue = null,
                kind = KIND_REMOTE_DELETED, at = clock.millis(),
            ),
        )
    }

    /** 노션이 요청을 거부(400)하면 로컬 변경을 되돌린다(PRD §7-1). */
    private suspend fun reject(row: OutboxEntity, source: DataSourceEntity) = db.withTransaction {
        val current = events.get(row.pageId)
        when (OutboxOp.valueOf(row.op)) {
            OutboxOp.CREATE -> {
                events.delete(listOf(row.pageId))
                outbox.deleteForPage(row.pageId)
            }
            OutboxOp.UPDATE -> {
                val base = row.baseValues?.let(::decodeChange)
                if (current != null && base != null) {
                    events.upsert(listOf(current.applying(base, parseColorOptions(source.colorOptions))))
                }
                outbox.delete(row.id)
                refreshPending(row.pageId)
            }
            OutboxOp.TRASH -> {
                row.baseValues?.let { events.upsert(listOf(OutboxJson.decodeFromString(EventSnapshot.serializer(), it).toEntity())) }
                outbox.delete(row.id)
                refreshPending(row.pageId)
            }
        }
        db.conflictDao().insert(
            ConflictEntity(
                pageId = row.pageId, title = current?.title.orEmpty(), field = "PAGE", localValue = row.op, remoteValue = null,
                kind = KIND_REJECTED, at = clock.millis(),
            ),
        )
        widgetRefresher.requestUpdate()
    }

    /** 노션 응답으로 로컬 일정을 맞춘다. 같은 페이지에 남은 작업이 있으면 그 변경을 다시 얹는다. */
    private suspend fun applyServerPage(pageId: String, remote: RemoteEvent?, source: DataSourceEntity) {
        // SY-08 ①: 같은 페이지의 삭제가 줄 서 있으면 앞선 편집의 응답이 지운 일정을 로컬에 되살리지 않는다.
        val queued = outbox.all().filter { it.pageId == pageId }
        if (queued.any { it.op == OutboxOp.TRASH.name }) return
        val remaining = queued
        if (remote == null) {
            if (remaining.isEmpty()) events.delete(listOf(pageId))
            return
        }
        val options = parseColorOptions(source.colorOptions)
        // 습관 연결을 매핑에서 뺀 동기화(NS-09)였다면 로컬에 있던 연결을 지우지 않는다.
        val link = remote.linkPageId ?: events.get(pageId)?.linkPageId
        var entity = remote.toEntity(source.dataSourceId).copy(pageId = pageId, linkPageId = link)
        remaining.forEach { entity = entity.applying(decodeChange(it.changes), options) }
        events.upsert(listOf(entity.copy(pendingOps = remaining.size)))
    }

    private suspend fun refreshPending(pageId: String) = events.setPending(pageId, outbox.countForPage(pageId))

    /**
     * 보낼 수 없는 작업 하나를 정리한다(노션이 거부함, 또는 작업을 읽지 못함). 거부와 같게 로컬을 되돌리고 '노션 반영 실패'에 남긴다.
     * 되돌리는 데 쓸 저장값까지 망가져 그것도 못 하면 작업만 지우고 같은 기록을 남긴다. 이 작업 하나 때문에 큐 전체가 멈추지 않게 하는 것이 먼저다.
     */
    private suspend fun quarantine(row: OutboxEntity, source: DataSourceEntity) {
        try {
            reject(row, source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "되돌리지 못한 작업 정리(${row.op}): ${e.javaClass.simpleName}")
            outbox.delete(row.id)
            refreshPending(row.pageId)
            db.conflictDao().insert(
                ConflictEntity(
                    pageId = row.pageId, title = events.get(row.pageId)?.title.orEmpty(), field = "PAGE", localValue = row.op, remoteValue = null,
                    kind = KIND_REJECTED, at = clock.millis(),
                ),
            )
        }
    }

    companion object {
        const val KIND_CONFLICT = "CONFLICT"
        const val KIND_REMOTE_DELETED = "REMOTE_DELETED"
        const val KIND_REJECTED = "REJECTED"
        private const val TAG = "OutboxProcessor"
        private val CREATE_LOOKBACK: Duration = Duration.ofMinutes(1)
    }
}

private fun decodeChange(json: String) = OutboxJson.decodeFromString(EventChange.serializer(), json)

/**
 * 보내기 전에 작업 종류·바꿈·되돌릴 원래 값을 읽을 수 있는가. 못 읽는 작업은 다시 보내도 같아 정리한다
 * (보낸 뒤에 난 오류로 이미 노션에 반영된 작업을 지우지 않으려고 미리 본다).
 */
private fun OutboxEntity.isReadable(): Boolean = runCatching {
    val kind = OutboxOp.valueOf(op)
    decodeChange(changes)
    baseValues?.let { if (kind == OutboxOp.TRASH) OutboxJson.decodeFromString(EventSnapshot.serializer(), it) else decodeChange(it) }
}.isSuccess

/** 충돌을 판정하는 필드 단위(PRD §8.5-6: 속성 단위). */
internal enum class ChangeField {
    TITLE, DATE, DONE, SELECT;

    fun valueOf(change: EventChange): Any? = when (this) {
        TITLE -> change.title
        DATE -> change.date
        DONE -> change.done
        SELECT -> change.select
    }

    fun drop(change: EventChange): EventChange = when (this) {
        TITLE -> change.copy(title = null)
        DATE -> change.copy(date = null)
        DONE -> change.copy(done = null)
        SELECT -> change.copy(select = null)
    }

    fun describe(change: EventChange): String? = when (this) {
        TITLE -> change.title
        DATE -> change.date?.let { d -> listOfNotNull(d.startDate, d.endDate).joinToString("~") }
        DONE -> change.done?.toString()
        SELECT -> change.select?.optionId
    }
}

/** 내가 바꾼 필드 중 노션에서도 기준값과 달라진 필드. */
internal fun conflictingFields(change: EventChange, base: EventChange, remote: EventChange): List<ChangeField> =
    ChangeField.entries.filter { field ->
        // 노션 값이 이미 내 값과 같으면(끊기기 전에 노션이 받은 편집을 재시도) 충돌이 아니다.
        field.valueOf(change) != null && field.valueOf(remote) != field.valueOf(base) && field.valueOf(remote) != field.valueOf(change)
    }

/** 서버 일정에서 [change]와 같은 필드만 같은 모양으로 꺼낸다. */
internal fun RemoteEvent.valuesFor(change: EventChange) = EventChange(
    title = title.takeIf { change.title != null },
    date = DateValue(
        startDate.toString(), endDate?.toString(), startTime?.toEpochMilli(), endTime?.toEpochMilli(),
    ).takeIf { change.date != null },
    done = done.takeIf { change.done != null },
    select = SelectValue(colorOptionId).takeIf { change.select != null },
    props = change.props?.let { wanted -> wanted.keys.associateWith { extraValues[it]?.toPropValue() ?: PropValue() } },
)

/** EX-07: 내가 바꾼 추가 속성 중 노션에서도 기준값과 달라진 것(속성 ID). */
internal fun conflictingProps(change: EventChange, base: EventChange, remote: EventChange): List<String> =
    change.props.orEmpty().keys.filter { id ->
        val now = remote.props?.get(id) ?: PropValue()
        now != (base.props?.get(id) ?: PropValue()) && now != change.props?.get(id)
    }

private fun PropValue.describe(): String =
    optionIds?.joinToString(",") ?: number?.toString() ?: text.orEmpty()

internal fun EventChange.toPageWrite(catalog: List<ExtraProp> = emptyList()) = PageWrite(
    title = title,
    date = date?.let { d ->
        WriteDate(
            startDate = LocalDate.parse(d.startDate),
            endDate = d.endDate?.let(LocalDate::parse),
            start = d.startTime?.let(Instant::ofEpochMilli),
            end = d.endTime?.let(Instant::ofEpochMilli),
        )
    },
    done = done,
    setSelect = select != null,
    selectOptionId = select?.optionId,
    props = props.orEmpty().toPropertyWrites(catalog),
    link = link,
    ledger = ledger?.let { tx ->
        LedgerWrite(
            amount = tx.amount,
            flow = tx.flow?.let { name -> Flow.entries.firstOrNull { it.name == name }?.notionName },
            category = tx.category,
            time = tx.time,
            planned = tx.planned,
            manual = tx.manual,
        )
    },
)

/** [edit]에 든 필드만 남긴다(그 필드의 기준값으로 쓰려고). */
private fun EventChange.restrictedTo(edit: EventChange) = EventChange(
    title = title.takeIf { edit.title != null },
    date = date.takeIf { edit.date != null },
    done = done.takeIf { edit.done != null },
    select = select.takeIf { edit.select != null },
    props = props?.filterKeys { edit.props?.containsKey(it) == true },
    link = link.takeIf { edit.link != null },
    ledger = ledger.takeIf { edit.ledger != null },
)

/** [old]와 다른 필드만 남긴다(생성을 보낸 뒤 고친 것만 이어 보내려고). */
private fun EventChange.changedFrom(old: EventChange) = EventChange(
    title = title.takeIf { it != old.title },
    date = date.takeIf { it != old.date },
    done = done.takeIf { it != old.done },
    select = select.takeIf { it != old.select },
    props = props?.filter { (id, value) -> old.props?.get(id) != value }?.takeIf { it.isNotEmpty() },
    link = link.takeIf { it != old.link },
    ledger = ledger.takeIf { it != old.ledger },
)
