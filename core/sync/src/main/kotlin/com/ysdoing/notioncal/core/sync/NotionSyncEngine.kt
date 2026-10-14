package com.ysdoing.notioncal.core.sync

import androidx.room.withTransaction
import com.ysdoing.notioncal.core.data.ColorOption
import com.ysdoing.notioncal.core.data.encodeExtraValues
import com.ysdoing.notioncal.core.data.encodePropCatalog
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.OutboxJson
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionReader
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.mapping.DateCodec
import com.ysdoing.notioncal.core.notion.mapping.DoneType
import com.ysdoing.notioncal.core.notion.mapping.MappedPage
import com.ysdoing.notioncal.core.notion.mapping.NotionPageMapper
import com.ysdoing.notioncal.core.notion.mapping.PropertyMapping
import com.ysdoing.notioncal.core.notion.mapping.QueryFilters
import com.ysdoing.notioncal.core.notion.mapping.RemoteEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** 한 번의 동기화 결과. 화면 배너(PF-05)와 Worker 재시도 판단에 쓴다. */
data class SyncOutcome(val pulled: Int, val error: NotionError?)

/**
 * 노션 → Room 읽기 동기화(NS-03, PRD §8.5). 네트워크 호출은 이 클래스와 [ConnectionService]에서만 한다.
 *
 * DB마다
 * 1. 처음이면 범위 조회(이번 달 ±[RANGE_MONTHS]개월, 시작은 [LOOKBACK_DAYS]일 앞당김).
 * 2. 이후에는 last_edited_time 증분 조회(2분 겹침).
 * 3. 삭제 감지: 하루 1회 전체 범위, 앱 열기·새로고침 때는 현재 달을 조회해 로컬 ID와 대조한다.
 *    대조에서 빠진 항목은 바로 지우지 않고 단건 재조회로 확인한다(날짜 필터의 기간 일정 처리가 문서에 없음).
 */
@Singleton
class NotionSyncEngine @Inject constructor(
    private val reader: NotionReader,
    private val db: NotionCalDatabase,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val widgetRefresher: WidgetRefresher,
) {
    private val mutex = Mutex()
    private val sources get() = db.dataSourceDao()
    private val events get() = db.eventDao()

    suspend fun sync(trigger: SyncTrigger, onlyDataSourceId: String? = null): SyncOutcome = withContext(io) {
        mutex.withLock {
            val startedAt = clock.millis()
            var pulled = 0
            var failures = 0
            var lastError: NotionError? = null
            val targets = sources.enabled().filter { onlyDataSourceId == null || it.dataSourceId == onlyDataSourceId }
            // 등록한 DB가 없으면 기록하지 않는다("마지막 동기화" 시각이 거짓으로 뜨지 않게).
            if (targets.isEmpty()) return@withContext SyncOutcome(0, null)
            for (source in targets) {
                try {
                    pulled += syncSource(source, trigger)
                } catch (e: NotionException) {
                    failures++
                    lastError = e.error
                    // 토큰 문제는 모든 DB에 같으므로 나머지를 부르지 않는다.
                    if (e.error == NotionError.Unauthorized) break
                }
            }
            val result = when {
                lastError == null -> "OK"
                failures < targets.size -> "PARTIAL"
                else -> "ERROR"
            }
            db.syncLogDao().record(
                SyncLogEntity(
                    at = clock.millis(),
                    trigger = trigger.name,
                    result = result,
                    durationMs = clock.millis() - startedAt,
                    pulled = pulled,
                    pushed = 0,
                    error = lastError?.let { it::class.simpleName },
                ),
            )
            // WG-07: 위젯 머리줄의 동기화 시각·오류 점을 맞춘다(일정이 그대로여도).
            widgetRefresher.requestUpdate()
            SyncOutcome(pulled, lastError)
        }
    }

    private suspend fun syncSource(source: DataSourceEntity, trigger: SyncTrigger): Int {
        val now = clock.instant()
        val today = now.atZone(DateCodec.SEOUL).toLocalDate()
        val schema = reader.dataSource(source.dataSourceId)
        val mapper = NotionPageMapper(source.toMapping(), schema)
        // 편집 시트의 색 기준 선택지(WI-05). 노션에서 옵션을 바꿔도 다음 동기화에 반영된다.
        sources.setColorOptions(source.dataSourceId, colorOptionsJson(schema, source.colorPropId))
        // EX-07: 보기·편집할 수 있는 속성 목록. 처음 받는 DB(앱 업데이트 직후 포함)는 기존 일정에도 값을 채우려고 전체 조회한다.
        val catalogMissing = source.propCatalog == null
        sources.setPropCatalog(source.dataSourceId, encodePropCatalog(schema.propCatalog()))
        var pulled = 0

        val lastFull = source.lastFullSyncAt
        val fullDue = lastFull == null || catalogMissing || Duration.between(Instant.ofEpochMilli(lastFull), now) >= FULL_RECONCILE_INTERVAL
        val lastSync = source.lastSyncAt
        if (lastSync != null) {
            val since = Instant.ofEpochMilli(lastSync).minus(INCREMENTAL_OVERLAP)
            val changed = reader.queryAll(source.dataSourceId, QueryFilters.editedSince(since)).pages.map(mapper::map)
            apply(source, changed)
            pulled += changed.size
        }
        if (fullDue) {
            val (from, to) = fullRange(today)
            pulled += reconcile(source, mapper, from, to)
            sources.setLastFullSyncAt(source.dataSourceId, now.toEpochMilli())
        } else if (trigger == SyncTrigger.APP_OPEN || trigger == SyncTrigger.PULL_TO_REFRESH) {
            val month = YearMonth.from(today)
            pulled += reconcile(source, mapper, month.atDay(1), month.atEndOfMonth())
        }
        // 조회 시작 전 시각을 기준으로 남겨, 동기화 도중 바뀐 페이지는 다음 증분에서 받는다.
        sources.setLastSyncAt(source.dataSourceId, now.toEpochMilli())
        return pulled
    }

    /** [from]~[to] 범위를 조회해 upsert하고, 로컬에만 남은 항목은 단건 재조회로 확인한 뒤 정리한다. */
    private suspend fun reconcile(source: DataSourceEntity, mapper: NotionPageMapper, from: LocalDate, to: LocalDate): Int {
        val queryFrom = from.minusDays(LOOKBACK_DAYS)
        val result = reader.queryAll(source.dataSourceId, QueryFilters.dateRange(source.datePropId, queryFrom, to))
        val mapped = result.pages.map(mapper::map)
        apply(source, mapped)
        if (!result.complete) return mapped.size // 1만 건 제한에 걸리면 삭제 판단을 하지 않는다.

        val remoteIds = mapped.filter { it.event != null }.mapTo(HashSet()) { it.pageId }
        val missing = events.pageIdsInRange(source.dataSourceId, from, to).filterNot { it in remoteIds }
        val rechecked = missing.map { id -> reader.pageOrNull(id)?.let(mapper::map) ?: MappedPage(id, null) }
        apply(source, rechecked)
        return mapped.size + rechecked.size
    }

    private suspend fun apply(source: DataSourceEntity, incoming: List<MappedPage>) {
        if (incoming.isEmpty()) return
        // 아직 노션에 보내지 않은 쓰기가 있는 페이지는 건드리지 않는다. 내 변경이 서버 값으로 되돌아가거나
        // 휴지통 대기 중인 일정이 다시 나타나는 것을 막는다(전송이 끝나면 응답으로 맞춰진다).
        val pending = db.outboxDao().pageIds().toHashSet()
        val pages = incoming.filterNot { it.pageId in pending }
        if (pages.isEmpty()) return
        val (present, gone) = pages.partition { it.event != null }
        db.withTransaction {
            if (present.isNotEmpty()) events.upsert(present.map { it.event!!.toEntity(source.dataSourceId) })
            if (gone.isNotEmpty()) events.delete(gone.map { it.pageId })
        }
        // WG-05: 바뀐 내용이 있으면 위젯을 갱신한다(구현이 짧은 시간의 요청을 묶는다).
        widgetRefresher.requestUpdate()
    }

    companion object {
        const val RANGE_MONTHS = 2L
        const val LOOKBACK_DAYS = 31L
        val INCREMENTAL_OVERLAP: Duration = Duration.ofMinutes(2)
        val FULL_RECONCILE_INTERVAL: Duration = Duration.ofHours(24)

        /** 보이는 범위: 이번 달 ±2개월(NS-03 기본값). */
        fun fullRange(today: LocalDate): Pair<LocalDate, LocalDate> {
            val month = YearMonth.from(today)
            return month.minusMonths(RANGE_MONTHS).atDay(1) to month.plusMonths(RANGE_MONTHS).atEndOfMonth()
        }
    }
}

internal fun DataSourceEntity.toMapping() = PropertyMapping(
    titlePropId = titlePropId,
    datePropId = datePropId,
    donePropId = donePropId,
    doneType = doneType?.let { runCatching { DoneType.valueOf(it) }.getOrNull() },
    colorPropId = colorPropId,
    sortPropId = sortPropId,
)

internal fun RemoteEvent.toEntity(dataSourceId: String) = EventEntity(
    pageId = pageId,
    dataSourceId = dataSourceId,
    title = title,
    startDate = startDate,
    endDate = endDate,
    startTime = startTime?.toEpochMilli(),
    endTime = endTime?.toEpochMilli(),
    isAllDay = isAllDay,
    done = done,
    colorKey = colorKey,
    sortKey = sortKey,
    url = url,
    lastEditedTime = lastEditedTime.toEpochMilli(),
    colorOptionId = colorOptionId,
    extraValues = encodeExtraValues(extraValues.mapValues { it.value.toPropValue() }),
)

/** 색 기준 속성(select·status)의 옵션 목록 JSON. 속성이 없으면 null. */
internal fun colorOptionsJson(schema: DataSourceDto, colorPropId: String?): String? {
    val prop = schema.properties.values.firstOrNull { it.id == colorPropId } ?: return null
    val options = (prop.select?.options ?: prop.status?.options).orEmpty().map { ColorOption(it.id, it.name, it.color) }
    return OutboxJson.encodeToString(ListSerializer(ColorOption.serializer()), options)
}
