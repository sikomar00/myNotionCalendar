package com.ysdoing.notioncal.core.sync

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
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.OutboxEntity
import com.ysdoing.notioncal.core.data.parseColorOptions
import com.ysdoing.notioncal.core.data.toEntity
import com.ysdoing.notioncal.core.notion.NotionError
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
data class OutboxOutcome(val sent: Int, val remaining: Int, val retryError: NotionError?)

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
) {
    private val mutex = Mutex()
    private val outbox get() = db.outboxDao()
    private val events get() = db.eventDao()

    suspend fun process(): OutboxOutcome = withContext(io) {
        mutex.withLock {
            val schemas = HashMap<String, DataSourceDto>()
            var sent = 0
            var retryError: NotionError? = null
            while (true) {
                val row = outbox.all().firstOrNull() ?: break
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
                try {
                    val schema = schemas.getOrPut(source.dataSourceId) { reader.dataSource(source.dataSourceId) }
                    val mapper = NotionPageMapper(mapping, schema)
                    val encoder = PropertyEncoder(mapping, source.doneOption, source.undoneOption, schema)
                    when (OutboxOp.valueOf(row.op)) {
                        OutboxOp.CREATE -> create(row, source, mapper, encoder)
                        OutboxOp.UPDATE -> update(row, source, mapper, encoder)
                        OutboxOp.TRASH -> trash(row)
                    }
                    sent++
                } catch (e: NotionException) {
                    if (e.error is NotionError.Validation) {
                        reject(row, source)
                    } else {
                        outbox.update(row.copy(attempts = row.attempts + 1, lastError = e.error::class.simpleName))
                        retryError = e.error
                        break
                    }
                }
            }
            if (sent > 0) widgetRefresher.requestUpdate()
            OutboxOutcome(sent, outbox.all().size, retryError)
        }
    }

    private suspend fun create(row: OutboxEntity, source: DataSourceEntity, mapper: NotionPageMapper, encoder: PropertyEncoder) {
        val change = decodeChange(row.changes)
        // §8.5-8: 지난번 생성이 시간 초과·서버 오류로 끝났다면 실제로는 만들어졌을 수 있다. 다시 보내기 전에 찾아본다.
        val title = change.title
        val existing = if (row.attempts > 0 && title != null) findCreated(row, source, title) else null
        val page = existing ?: writer.createPage(source.dataSourceId, encoder.encode(change.toPageWrite(parsePropCatalog(source.propCatalog))))
        db.withTransaction {
            // HB-02: 보내는 사이 사용자가 지웠다(EventWriter.trash가 잠금 밖에서 임시 행과 이 작업을 지움). 만들어진 페이지를
            // 반영하면 체크가 되살아나므로 반영하지 않고 휴지통 작업을 대신 쌓는다(같은 실행에서 이어 보낸다).
            if (outbox.get(row.id) == null) {
                outbox.insert(
                    OutboxEntity(
                        pageId = page.id, dataSourceId = source.dataSourceId, op = OutboxOp.TRASH.name, changes = "{}",
                        baseValues = null, baseLastEdited = null, createdAt = clock.millis(),
                    ),
                )
                return@withTransaction
            }
            events.renamePage(row.pageId, page.id)
            outbox.renamePage(row.pageId, page.id)
            outbox.delete(row.id)
            applyServerPage(page.id, mapper.map(page).event, source)
        }
    }

    private suspend fun findCreated(row: OutboxEntity, source: DataSourceEntity, title: String): PageDto? {
        val since = Instant.ofEpochMilli(row.createdAt).minus(CREATE_LOOKBACK)
        return reader.queryAll(source.dataSourceId, QueryFilters.createdWithTitle(source.titlePropId, title.trim(), since))
            .pages.firstOrNull { !it.inTrash }
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
        val remaining = outbox.all().filter { it.pageId == pageId && it.op != OutboxOp.TRASH.name }
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

    companion object {
        const val KIND_CONFLICT = "CONFLICT"
        const val KIND_REMOTE_DELETED = "REMOTE_DELETED"
        const val KIND_REJECTED = "REJECTED"
        private val CREATE_LOOKBACK: Duration = Duration.ofMinutes(1)
    }
}

private fun decodeChange(json: String) = OutboxJson.decodeFromString(EventChange.serializer(), json)

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
        field.valueOf(change) != null && field.valueOf(remote) != field.valueOf(base)
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
    change.props.orEmpty().keys.filter { id -> (remote.props?.get(id) ?: PropValue()) != (base.props?.get(id) ?: PropValue()) }

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
