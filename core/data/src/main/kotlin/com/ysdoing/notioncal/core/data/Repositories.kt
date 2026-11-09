package com.ysdoing.notioncal.core.data

import androidx.room.withTransaction
import com.ysdoing.notioncal.core.data.db.DataSourceDao
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventDao
import com.ysdoing.notioncal.core.data.db.EventRow
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.SyncLogDao
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 화면·위젯이 쓰는 일정 모델. 색은 select 옵션 색 → DB 색 순으로 정해 둔다. */
data class CalendarEvent(
    val pageId: String,
    val dataSourceId: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val startTime: Instant?,
    val endTime: Instant?,
    val isAllDay: Boolean,
    val done: Boolean,
    val colorHex: String,
    val sourceColorHex: String,
    val url: String,
    /** 아직 노션에 보내지 않은 변경이 있다(전송 대기 표시). */
    val pending: Boolean = false,
    /** 색 기준 select 값의 옵션 ID(MV-09 값별 숨김). 값이 없으면 null. */
    val colorOptionId: String? = null,
    /** EX-11 강조 일정(예: 우선순위 '1st'). 완료되지 않았을 때만 강조해 그린다([showsEmphasis]). */
    val emphasized: Boolean = false,
) {
    /** EX-11 강조해 그릴지: 완료한 일정은 강조하지 않는다(취소선·흐림이 우선). */
    val showsEmphasis: Boolean get() = emphasized && !done

    val lastDate: LocalDate get() = endDate ?: startDate

    /** [date]가 이 일정이 걸친 날(시작일~끝날)인가. */
    fun occursOn(date: LocalDate): Boolean = !startDate.isAfter(date) && !lastDate.isBefore(date)
    val isMultiDay: Boolean get() = endDate != null && endDate.isAfter(startDate)
}

internal fun EventRow.toCalendarEvent() = CalendarEvent(
    pageId = event.pageId,
    dataSourceId = event.dataSourceId,
    title = event.title,
    startDate = event.startDate,
    endDate = event.endDate,
    startTime = event.startTime?.let(Instant::ofEpochMilli),
    endTime = event.endTime?.let(Instant::ofEpochMilli),
    isAllDay = event.isAllDay,
    done = event.done,
    colorHex = NotionColors.hexFor(event.colorKey.takeIf { useOptionColors }) ?: sourceColorHex,
    sourceColorHex = sourceColorHex,
    url = event.url,
    pending = event.pendingOps > 0,
    colorOptionId = event.colorOptionId,
    emphasized = isEmphasized(event.extraValues, emphasisPropId, emphasisOptionIds),
)

/** EX-11: 일정의 추가 속성 값(동기화 때 모든 select·status 값을 받아 둔다) 중 강조 속성 값이 강조 옵션이면 true. */
internal fun isEmphasized(extraValues: String?, propId: String?, optionIds: String?): Boolean {
    if (propId == null || optionIds == null || extraValues == null) return false
    val wanted = parseIdList(optionIds).toSet().ifEmpty { return false }
    return parseExtraValues(extraValues)[propId]?.optionIds.orEmpty().any { it in wanted }
}

@Singleton
class EventRepository @Inject constructor(private val eventDao: EventDao) {
    /** Room 조회만 한다. 네트워크와 무관하게 즉시 마지막 데이터를 낸다(PF-02). */
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<CalendarEvent>> =
        eventDao.observeRange(from, to).map { rows -> rows.map { it.toCalendarEvent() } }

    /** MV-10 로컬 캐시 제목 검색. 앞뒤 공백은 무시하고, 빈 검색어는 빈 결과다. 앱 필터(MV-09)는 걸지 않는다. */
    suspend fun search(query: String, today: LocalDate, limit: Int = SEARCH_LIMIT): List<CalendarEvent> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return eventDao.search(escapeLike(trimmed), today, limit).map { it.toCalendarEvent() }
    }

    companion object {
        const val SEARCH_LIMIT = 100

        /** LIKE 와일드카드를 글자 그대로 찾게 한다(ESCAPE '\\'). */
        fun escapeLike(text: String): String =
            text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

/** 편집 시트(WI-05)가 쓰는 일정 1개와 그 DB의 색 기준 선택지. */
data class EditableEvent(
    val event: CalendarEvent,
    val colorOptionId: String?,
    val colorOptions: List<ColorOption>,
    val dataSourceName: String,
    /** 색 기준 속성 이름(예: Ctgr). 편집 시트에서 옵션 칩 위에 붙인다. */
    val colorPropName: String? = null,
    /** EX-07: 편집 화면에 보여 줄 추가 속성(설정에서 고른 것 중 지금 스키마에 있는 것). */
    val extraProps: List<ExtraProp> = emptyList(),
    val extraValues: Map<String, PropValue> = emptyMap(),
    /** 제목에 노션 언급(@)이 있다. 제목을 바꾸면 언급이 글자로 바뀐다. */
    val titleHasMention: Boolean = false,
)

@Singleton
class EditableEventRepository @Inject constructor(
    private val eventDao: EventDao,
    private val dataSourceDao: DataSourceDao,
) {
    /**
     * 새 일정을 편집하는 사이 생성이 노션에 가 id가 바뀌어도 같은 일정을 계속 낸다([CreatedPageIds]). 편집 폼은 id에 입력을 묶어 두므로
     * 처음 연 id 그대로 내고, id가 바뀌는 순간 잠깐 지나가는 '없음'은 건너뛴다(폼이 닫혀 입력이 사라지지 않게).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(pageId: String): Flow<EditableEvent?> {
        val events = CreatedPageIds.observe(pageId).flatMapLatest { id ->
            if (id == pageId) {
                eventDao.observe(id).filter { it != null || CreatedPageIds.resolve(pageId) == pageId } // 이름이 바뀌어 사라진 임시 행
            } else {
                eventDao.observe(id).dropWhile { it == null } // 진짜 행이 보이기 전의 '없음'만 건너뛰고, 그 뒤 지워지면 알린다
            }
        }
        return combine(events, dataSourceDao.observeAll()) { event, sources ->
            val source = event?.let { e -> sources.firstOrNull { it.dataSourceId == e.dataSourceId } } ?: return@combine null
            EditableEvent(
                event = EventRow(event.copy(pageId = pageId), source.colorHex, source.useOptionColors).toCalendarEvent(),
                colorOptionId = event.colorOptionId,
                colorOptions = parseColorOptions(source.colorOptions),
                dataSourceName = source.name,
                colorPropName = parsePropCatalog(source.propCatalog).firstOrNull { it.id == source.colorPropId }?.name,
                extraProps = source.shownExtraProps(),
                extraValues = parseExtraValues(event.extraValues),
                titleHasMention = event.titleHasMention,
            )
        }
    }
}

@Singleton
class DataSourceRepository @Inject constructor(
    private val db: NotionCalDatabase,
    private val dataSourceDao: DataSourceDao,
    private val eventDao: EventDao,
    private val widgetRefresher: WidgetRefresher,
) {
    /** 설정 화면: 모든 종류. */
    fun observeAll(): Flow<List<DataSourceEntity>> = dataSourceDao.observeAll()

    /** 달력 필터·퀵 추가 DB 칩·기본 DB·위젯 설정 DB 목록: 일정 DB만. */
    fun observeCalendarSources(): Flow<List<DataSourceEntity>> = dataSourceDao.observeByKind(SourceKind.CALENDAR.name)

    /** HB-01: 습관 기록과 🌱 습관은 한 쌍이라 함께 켜고 끈다. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        // 켤 때는 🌱도 늘 켠다. 끌 때는 그 🌱를 쓰는 다른 기록 DB가 켜져 있으면 🌱는 둔다(🌱는 따로 켜고 끌 카드가 없다).
        db.withTransaction { pairOf(id) { other -> !enabled && other.enabled }.forEach { dataSourceDao.setEnabled(it, enabled) } }
        widgetRefresher.requestUpdate()
    }

    /**
     * [id]와 짝(✅ 습관 기록 ↔ 🌱 습관, 거래내역 ↔ 예산·카테고리 마스터)의 ID. 일정 DB면 자기 자신만.
     * 🌱는 [keptBy]가 참인 다른 기록 DB가 함께 쓰면 짝에서 뺀다: 기록 DB 둘이 한 🌱를 쓰면 한쪽을 끄거나 지울 때 다른 쪽의 습관까지 사라졌다.
     */
    private suspend fun pairOf(id: String, keptBy: (DataSourceEntity) -> Boolean = { true }): List<String> {
        val source = dataSourceDao.get(id) ?: return listOf(id)
        return when (source.sourceKind) {
            SourceKind.HABIT_LOG -> listOf(id) + listOfNotNull(
                source.linkDataSourceId?.takeIf { habit -> dataSourceDao.linkedTo(habit).none { it.dataSourceId != id && keptBy(it) } },
            )
            SourceKind.HABIT, SourceKind.LEDGER -> listOf(id) + dataSourceDao.linkedTo(id).map { it.dataSourceId }
            // 거래내역이 이미 지워졌으면(짝 잃음) 자기 자신만: 그래야 설정에서 따로 끄고 지울 수 있다.
            SourceKind.BUDGET, SourceKind.CATEGORY -> source.linkDataSourceId?.takeIf { dataSourceDao.get(it) != null }?.let { pairOf(it) } ?: listOf(id)
            else -> listOf(id)
        }
    }

    /** NS-09 배너 '확인': 이 DB의 스키마 알림을 확인 처리한다. */
    suspend fun acknowledgeSchemaIssues(id: String) {
        val source = dataSourceDao.get(id) ?: return
        dataSourceDao.setSchemaIssues(id, SchemaIssue.encode(SchemaIssue.acknowledge(SchemaIssue.parse(source.schemaIssues))))
    }

    /** EX-03 DB 색과 색 기준을 바꾸고 위젯을 다시 그린다. */
    suspend fun setColorStyle(id: String, colorHex: String, useOptionColors: Boolean) {
        dataSourceDao.setColorStyle(id, colorHex, useOptionColors)
        widgetRefresher.requestUpdate()
    }

    /** EX-11 강조 기준을 바꾸고 위젯을 다시 그린다. [propId]가 null이거나 옵션이 없으면 끈다. */
    suspend fun setEmphasis(id: String, propId: String?, optionIds: List<String>) {
        val ids = encodeIdList(optionIds).takeIf { propId != null }
        dataSourceDao.setEmphasis(id, propId?.takeIf { ids != null }, ids)
        widgetRefresher.requestUpdate()
    }

    suspend fun setExtraProps(id: String, propIds: List<String>) {
        dataSourceDao.setExtraPropIds(id, encodeIdList(propIds))
    }

    /** 다시 연결로 쓰지 않게 된 🌱([habitId])를, 이제 아무 기록 DB도 가리키지 않을 때만 지운다(설정에 홀로 남아 계속 동기화됐다). 지웠으면 true. */
    suspend fun deleteOrphanHabit(habitId: String): Boolean {
        if (dataSourceDao.linkedTo(habitId).isNotEmpty()) return false
        delete(habitId)
        return true
    }

    /** 등록을 지우면 그 DB의 로컬 일정(습관 기록·습관 캐시 포함)도 지운다. 습관 짝은 함께 지운다. 노션 원본은 건드리지 않는다. */
    suspend fun delete(id: String) {
        db.withTransaction {
            pairOf(id).forEach { target ->
                eventDao.deleteForSource(target)
                db.habitDao().deleteForSource(target)
                db.ledgerDao().deleteBudgets(target)
                db.ledgerDao().deleteCategoryGroups(target)
                dataSourceDao.deleteRow(target)
            }
        }
        widgetRefresher.requestUpdate()
    }
}

@Singleton
class SyncStatusRepository @Inject constructor(private val syncLogDao: SyncLogDao) {
    val latest: Flow<SyncLogEntity?> = syncLogDao.observeLatest()
    val latestSuccess: Flow<SyncLogEntity?> = syncLogDao.observeLatestSuccess()
}

/**
 * EX-07: 고른 속성 중 지금 스키마에 있는 것만, 고른 순서대로. 노션에서 지워진 속성은 앱이 죽지 않게 조용히 뺀다
 * (알림은 NS-09). 이미 편집기가 있는 속성(제목·날짜·완료·색 기준)은 고를 수 없게 [selectableExtraProps]에서 뺀다.
 */
fun DataSourceEntity.shownExtraProps(): List<ExtraProp> {
    val catalog = selectableExtraProps().associateBy { it.id }
    return parseIdList(extraPropIds).mapNotNull { catalog[it] }
}

fun DataSourceEntity.selectableExtraProps(): List<ExtraProp> {
    val taken = setOfNotNull(titlePropId, datePropId, donePropId, colorPropId)
    return parsePropCatalog(propCatalog).filterNot { it.id in taken }
}
