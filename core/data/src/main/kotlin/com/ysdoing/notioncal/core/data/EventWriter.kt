package com.ysdoing.notioncal.core.data

import androidx.room.withTransaction
import com.ysdoing.notioncal.core.data.db.ConflictEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.OutboxEntity
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 아웃박스 작업 종류. */
enum class OutboxOp { CREATE, UPDATE, TRASH }

/**
 * 화면(퀵 시트·날짜 시트)이 쓰는 유일한 쓰기 입구(PRD §7-1, NS-04).
 * Room에 먼저 반영해 화면·위젯이 바로 바뀌고, 노션 전송은 아웃박스 작업으로 뒤따른다.
 */
@Singleton
class EventWriter @Inject constructor(
    private val db: NotionCalDatabase,
    private val clock: Clock,
    private val widgetRefresher: WidgetRefresher,
    private val outboxRequester: OutboxRequester,
) {
    private val events get() = db.eventDao()
    private val outbox get() = db.outboxDao()

    /** WI-02: 새 일정. 노션 응답을 받기 전까지 임시 ID([TEMP_PREFIX])를 쓴다. */
    suspend fun create(dataSourceId: String, title: String, date: DateValue): String {
        val pageId = TEMP_PREFIX + UUID.randomUUID()
        val change = EventChange(title = title.trim(), date = date, done = false)
        db.withTransaction {
            val base = EventEntity(
                pageId = pageId, dataSourceId = dataSourceId, title = "", startDate = LocalDate.parse(date.startDate),
                endDate = null, startTime = null, endTime = null, isAllDay = true, done = false, colorKey = null,
                sortKey = null, url = "", lastEditedTime = clock.millis(), pendingOps = 1,
            )
            events.upsert(listOf(base.applying(change, emptyList())))
            outbox.insert(row(pageId, dataSourceId, OutboxOp.CREATE, change, base = null, baseLastEdited = null))
        }
        afterWrite()
        return pageId
    }

    /** WI-05: 바뀐 필드만 보낸다. 아직 생성이 전송되지 않은 일정이면 생성 작업에 합친다. */
    suspend fun update(pageId: String, change: EventChange) {
        if (change.isEmpty) return
        val updated = db.withTransaction {
            val current = events.get(pageId) ?: return@withTransaction false
            val options = parseColorOptions(db.dataSourceDao().get(current.dataSourceId)?.colorOptions)
            val pendingCreate = outbox.all().firstOrNull { it.pageId == pageId && it.op == OutboxOp.CREATE.name }
            if (pendingCreate != null) {
                val merged = decode(pendingCreate.changes).mergedWith(change)
                outbox.update(pendingCreate.copy(changes = OutboxJson.encodeToString(EventChange.serializer(), merged)))
                events.upsert(listOf(current.applying(change, options)))
            } else {
                events.upsert(listOf(current.applying(change, options).copy(pendingOps = current.pendingOps + 1)))
                outbox.insert(
                    row(pageId, current.dataSourceId, OutboxOp.UPDATE, change, base = current.valuesFor(change), current.lastEditedTime),
                )
            }
            true
        }
        if (updated) afterWrite()
    }

    /** WI-03 */
    suspend fun setDone(pageId: String, done: Boolean) = update(pageId, EventChange(done = done))

    /** NS-04: 노션에서는 휴지통 이동만 한다. 아직 생성되지 않은 일정이면 생성 작업까지 지운다. */
    suspend fun trash(pageId: String) {
        val trashed = db.withTransaction {
            val current = events.get(pageId) ?: return@withTransaction false
            events.delete(listOf(pageId))
            if (pageId.startsWith(TEMP_PREFIX)) {
                outbox.deleteForPage(pageId)
            } else {
                val snapshot = OutboxJson.encodeToString(EventSnapshot.serializer(), current.snapshot())
                outbox.insert(
                    OutboxEntity(
                        pageId = pageId, dataSourceId = current.dataSourceId, op = OutboxOp.TRASH.name, changes = "{}",
                        baseValues = snapshot, baseLastEdited = current.lastEditedTime, createdAt = clock.millis(),
                    ),
                )
            }
            true
        }
        if (trashed) afterWrite()
    }

    private fun row(pageId: String, dataSourceId: String, op: OutboxOp, change: EventChange, base: EventChange?, baseLastEdited: Long?) =
        OutboxEntity(
            pageId = pageId,
            dataSourceId = dataSourceId,
            op = op.name,
            changes = OutboxJson.encodeToString(EventChange.serializer(), change),
            baseValues = base?.let { OutboxJson.encodeToString(EventChange.serializer(), it) },
            baseLastEdited = baseLastEdited,
            createdAt = clock.millis(),
        )

    private fun decode(json: String) = OutboxJson.decodeFromString(EventChange.serializer(), json)

    private fun afterWrite() {
        widgetRefresher.requestUpdate()
        outboxRequester.request()
    }

    companion object {
        const val TEMP_PREFIX = "local-"
    }
}

/** NS-05: 아직 확인하지 않은 충돌·반영 실패(앱 배너). */
@Singleton
class ConflictRepository @Inject constructor(private val db: NotionCalDatabase) {
    val unseen: Flow<List<ConflictEntity>> = db.conflictDao().observeUnseen()

    /** 아직 노션에 보내지 않은 작업 수(전송 대기 표시). */
    val pendingCount: Flow<Int> = db.outboxDao().observeCount()

    suspend fun markAllSeen() = db.conflictDao().markAllSeen()
}
