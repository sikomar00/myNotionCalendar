package com.ysdoing.notioncal.core.sync

import androidx.room.withTransaction
import com.ysdoing.notioncal.core.data.ColorOption
import com.ysdoing.notioncal.core.data.BudgetPropIds
import com.ysdoing.notioncal.core.data.CategoryPropIds
import com.ysdoing.notioncal.core.data.HabitPropIds
import com.ysdoing.notioncal.core.data.LedgerPropIds
import com.ysdoing.notioncal.core.data.db.BudgetEntity
import com.ysdoing.notioncal.core.data.db.CategoryGroupEntity
import com.ysdoing.notioncal.core.data.ledger.Flow
import com.ysdoing.notioncal.core.notion.mapping.BudgetMapping
import com.ysdoing.notioncal.core.notion.mapping.BudgetPageMapper
import com.ysdoing.notioncal.core.notion.mapping.CategoryMapping
import com.ysdoing.notioncal.core.notion.mapping.CategoryPageMapper
import com.ysdoing.notioncal.core.notion.mapping.LedgerMapping
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.db.HabitEntity
import com.ysdoing.notioncal.core.data.sourceKind
import com.ysdoing.notioncal.core.notion.mapping.HabitMapping
import com.ysdoing.notioncal.core.notion.mapping.HabitPageMapper
import com.ysdoing.notioncal.core.notion.mapping.RemoteHabit
import com.ysdoing.notioncal.core.data.SchemaIssue
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
 *
 * 🌱 습관(HB-01)은 날짜가 없어 매번 필터 없이 전체 조회해 통째로 바꾼다. ✅ 습관 기록은 일정과 같은 방식이되 범위가
 * max(가장 이른 습관 시작일, 오늘-[HABIT_LOG_DAYS]일)~오늘이다(긴 연속·연속 달성 주를 세려고). 습관을 먼저 받는다.
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
                .sortedBy { if (it.sourceKind == SourceKind.HABIT) 0 else 1 } // 기록 범위가 습관 시작일을 쓴다
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
        val kind = source.sourceKind ?: return 0 // 이 버전이 모르는 종류(뒤 Phase의 가계부 등)는 건드리지 않는다.
        val now = clock.instant()
        val today = now.atZone(DateCodec.SEOUL).toLocalDate()
        val schema = reader.dataSource(source.dataSourceId)
        // NS-09: 매핑한 속성이 사라지거나 바뀌었는지 먼저 본다. 앱은 죽지 않고 알림을 남긴다.
        val report = SchemaCheck.check(source, schema, SchemaIssue.parseNames(source.mappedPropNames))
        val issues = SchemaIssue.merge(SchemaIssue.parse(source.schemaIssues), report.issues)
        sources.setSchemaState(source.dataSourceId, SchemaIssue.encodeNames(report.names), SchemaIssue.encode(issues))
        if (report.blocksSync) return 0 // 제목·날짜가 없으면 조회할 수 없다. 캐시는 그대로 둔다.
        if (kind == SourceKind.HABIT) return syncHabits(source, report.droppedRoles, now)
        if (kind == SourceKind.BUDGET || kind == SourceKind.CATEGORY) return syncLedgerTable(source, kind, report.droppedRoles, now)
        val mapping = source.toMapping(report.droppedRoles) ?: return 0
        val mapper = NotionPageMapper(mapping, schema)
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
            val (from, to) = if (kind == SourceKind.HABIT_LOG) habitLogRange(source, today) else fullRange(today)
            pulled += reconcile(source, mapping.datePropId, mapper, from, to)
            sources.setLastFullSyncAt(source.dataSourceId, now.toEpochMilli())
        } else if (trigger == SyncTrigger.APP_OPEN || trigger == SyncTrigger.PULL_TO_REFRESH) {
            val month = YearMonth.from(today)
            pulled += reconcile(source, mapping.datePropId, mapper, month.atDay(1), month.atEndOfMonth())
        }
        // 조회 시작 전 시각을 기준으로 남겨, 동기화 도중 바뀐 페이지는 다음 증분에서 받는다.
        sources.setLastSyncAt(source.dataSourceId, now.toEpochMilli())
        return pulled
    }

    /** HB-01: 🌱 습관 전체 조회 → 그 DB의 습관을 통째로 바꾼다(노션에서 지운 습관도 빠진다). */
    private suspend fun syncHabits(source: DataSourceEntity, dropped: Set<String>, now: Instant): Int {
        val ids = HabitPropIds.parse(source.habitPropIds)
        fun keep(role: String, id: String?) = id.takeUnless { role in dropped }
        val mapper = HabitPageMapper(
            HabitMapping(
                titlePropId = source.titlePropId,
                emojiPropId = keep(SchemaIssue.EMOJI, ids.emoji),
                weeklyTargetPropId = keep(SchemaIssue.TARGET, ids.weeklyTarget),
                activePropId = keep(SchemaIssue.ACTIVE, ids.active),
                startDatePropId = keep(SchemaIssue.START, ids.startDate),
                sortOrderPropId = keep(SchemaIssue.ORDER, ids.sortOrder),
            ),
        )
        val result = reader.queryAll(source.dataSourceId, null)
        val habits = result.pages.mapNotNull(mapper::map).map { it.toEntity(source.dataSourceId) }
        // 1만 건 제한에 걸리면 지우기는 하지 않는다.
        if (result.complete) db.habitDao().replaceForSource(source.dataSourceId, habits) else db.habitDao().insert(habits)
        sources.setLastSyncAt(source.dataSourceId, now.toEpochMilli())
        sources.setLastFullSyncAt(source.dataSourceId, now.toEpochMilli())
        widgetRefresher.requestUpdate()
        return habits.size
    }

    /** LD-01·05: 예산·카테고리 마스터 전체 조회 → 표를 통째로 바꾼다(날짜 없는 DB, 행이 수십 개). */
    private suspend fun syncLedgerTable(source: DataSourceEntity, kind: SourceKind, dropped: Set<String>, now: Instant): Int {
        fun keep(role: String, id: String?) = id.takeUnless { role in dropped }
        val result = reader.queryAll(source.dataSourceId, null)
        val dao = db.ledgerDao()
        val count = if (kind == SourceKind.BUDGET) {
            val ids = BudgetPropIds.parse(source.rolePropIds)
            val mapper = BudgetPageMapper(
                BudgetMapping(
                    titlePropId = source.titlePropId,
                    periodTypePropId = ids.periodType,
                    periodValuePropId = ids.periodValue,
                    groupPropId = keep(SchemaIssue.GROUP, ids.group),
                    amountPropId = keep(SchemaIssue.AMOUNT, ids.amount),
                ),
            )
            val rows = result.pages.mapNotNull(mapper::map).map {
                BudgetEntity(it.pageId, source.dataSourceId, it.periodType, it.periodKey, it.group, it.amount)
            }
            if (result.complete) dao.replaceBudgets(source.dataSourceId, rows) else dao.insertBudgets(rows)
            rows.size
        } else {
            val mapper = CategoryPageMapper(CategoryMapping(source.titlePropId, keep(SchemaIssue.GROUP, CategoryPropIds.parse(source.rolePropIds).group)))
            val rows = result.pages.mapNotNull(mapper::map).map { CategoryGroupEntity(it.pageId, source.dataSourceId, it.category, it.group) }
            if (result.complete) dao.replaceCategoryGroups(source.dataSourceId, rows) else dao.insertCategoryGroups(rows)
            rows.size
        }
        sources.setLastSyncAt(source.dataSourceId, now.toEpochMilli())
        sources.setLastFullSyncAt(source.dataSourceId, now.toEpochMilli())
        widgetRefresher.requestUpdate()
        return count
    }

    /** 검증 메모 6: 기록 조회 범위. 시작일이 없는 습관이 있으면 [HABIT_LOG_DAYS]일 전부터. */
    private suspend fun habitLogRange(source: DataSourceEntity, today: LocalDate): Pair<LocalDate, LocalDate> {
        val floor = today.minusDays(HABIT_LOG_DAYS)
        val starts = source.linkDataSourceId?.let { db.habitDao().forSource(it) }.orEmpty().map { it.startDate }
        val earliest = if (starts.isEmpty() || starts.any { it == null }) floor else maxOf(starts.filterNotNull().min(), floor)
        return minOf(earliest, today) to today
    }

    /** [from]~[to] 범위를 조회해 upsert하고, 로컬에만 남은 항목은 단건 재조회로 확인한 뒤 정리한다. */
    private suspend fun reconcile(source: DataSourceEntity, datePropId: String, mapper: NotionPageMapper, from: LocalDate, to: LocalDate): Int {
        val queryFrom = from.minusDays(LOOKBACK_DAYS)
        val result = reader.queryAll(source.dataSourceId, QueryFilters.dateRange(datePropId, queryFrom, to))
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

        /** ✅ 습관 기록을 받아 두는 최대 기간(일). */
        const val HABIT_LOG_DAYS = 400L

        /** 보이는 범위: 이번 달 ±2개월(NS-03 기본값). */
        fun fullRange(today: LocalDate): Pair<LocalDate, LocalDate> {
            val month = YearMonth.from(today)
            return month.minusMonths(RANGE_MONTHS).atDay(1) to month.plusMonths(RANGE_MONTHS).atEndOfMonth()
        }
    }
}

/** [dropped]: 스키마에서 사라져 이번 동기화에서 뺄 선택 역할(NS-09). 날짜 속성이 없는 DB(🌱 습관)는 일정 매핑이 없다(null). */
internal fun DataSourceEntity.toMapping(dropped: Set<String> = emptySet()): PropertyMapping? = datePropId?.let { date -> toMapping(date, dropped) }

private fun DataSourceEntity.toMapping(datePropId: String, dropped: Set<String>) = PropertyMapping(
    titlePropId = titlePropId,
    datePropId = datePropId,
    donePropId = donePropId.takeUnless { SchemaIssue.DONE in dropped },
    doneType = doneType?.let { runCatching { DoneType.valueOf(it) }.getOrNull() }.takeUnless { SchemaIssue.DONE in dropped },
    colorPropId = colorPropId.takeUnless { SchemaIssue.COLOR in dropped },
    sortPropId = sortPropId.takeUnless { SchemaIssue.SORT in dropped },
    linkPropId = linkPropId.takeUnless { SchemaIssue.LINK in dropped },
    ledger = if (sourceKind == SourceKind.LEDGER) ledgerMapping(dropped) else null,
)

/** LD-01 거래내역 매핑. 스키마에서 빠진 선택 속성은 뺀다(NS-09). */
private fun DataSourceEntity.ledgerMapping(dropped: Set<String>): LedgerMapping {
    val ids = LedgerPropIds.parse(rolePropIds)
    fun keep(role: String, id: String?) = id.takeUnless { role in dropped }
    return LedgerMapping(
        amountPropId = ids.amount,
        flowPropId = ids.flow,
        categoryPropId = keep(SchemaIssue.CATEGORY, ids.category),
        timePropId = keep(SchemaIssue.TIME, ids.time),
        paymentPropId = keep(SchemaIssue.PAYMENT, ids.payment),
        plannedPropId = keep(SchemaIssue.PLANNED, ids.planned),
        manualPropId = keep(SchemaIssue.MANUAL, ids.manual),
    )
}

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
    titleHasMention = titleHasMention,
    linkPageId = linkPageId,
    createdTime = createdTime?.toEpochMilli() ?: 0,
    amount = ledger?.amount,
    flow = ledger?.flow?.let(Flow::ofName)?.name,
    category = ledger?.category,
    txTime = ledger?.time,
    payment = ledger?.payment,
    planned = ledger?.planned ?: false,
    manual = ledger?.manual ?: false,
)

internal fun RemoteHabit.toEntity(dataSourceId: String) = HabitEntity(
    pageId = pageId,
    dataSourceId = dataSourceId,
    name = name,
    emoji = emoji,
    weeklyTarget = weeklyTarget,
    active = active,
    startDate = startDate,
    sortOrder = sortOrder,
    url = url,
)

/** 색 기준 속성(select·status)의 옵션 목록 JSON. 속성이 없으면 null. */
internal fun colorOptionsJson(schema: DataSourceDto, colorPropId: String?): String? {
    val prop = schema.properties.values.firstOrNull { it.id == colorPropId } ?: return null
    val options = (prop.select?.options ?: prop.status?.options).orEmpty().map { ColorOption(it.id, it.name, it.color) }
    return OutboxJson.encodeToString(ListSerializer(ColorOption.serializer()), options)
}
