package com.ysdoing.notioncal.core.data

import androidx.room.withTransaction
import com.ysdoing.notioncal.core.data.db.DataSourceDao
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventDao
import com.ysdoing.notioncal.core.data.db.EventRow
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.SyncLogDao
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
) {
    val lastDate: LocalDate get() = endDate ?: startDate
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
)

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
    fun observe(pageId: String): Flow<EditableEvent?> =
        combine(eventDao.observe(pageId), dataSourceDao.observeAll()) { event, sources ->
            val source = event?.let { e -> sources.firstOrNull { it.dataSourceId == e.dataSourceId } } ?: return@combine null
            EditableEvent(
                event = EventRow(event, source.colorHex, source.useOptionColors).toCalendarEvent(),
                colorOptionId = event.colorOptionId,
                colorOptions = parseColorOptions(source.colorOptions),
                dataSourceName = source.name,
                extraProps = source.shownExtraProps(),
                extraValues = parseExtraValues(event.extraValues),
                titleHasMention = event.titleHasMention,
            )
        }
}

@Singleton
class DataSourceRepository @Inject constructor(
    private val db: NotionCalDatabase,
    private val dataSourceDao: DataSourceDao,
    private val eventDao: EventDao,
    private val widgetRefresher: WidgetRefresher,
) {
    fun observeAll(): Flow<List<DataSourceEntity>> = dataSourceDao.observeAll()

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dataSourceDao.setEnabled(id, enabled)
        widgetRefresher.requestUpdate()
    }

    /** EX-07: 편집 화면에 보여 줄 속성을 고른다. 값은 동기화 때 이미 받아 두므로 다시 받지 않는다. */
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

    suspend fun setExtraProps(id: String, propIds: List<String>) {
        dataSourceDao.setExtraPropIds(id, encodeIdList(propIds))
    }

    /** 등록을 지우면 그 DB의 로컬 일정도 지운다. 노션 원본은 건드리지 않는다. */
    suspend fun delete(id: String) {
        db.withTransaction {
            eventDao.deleteForSource(id)
            dataSourceDao.deleteRow(id)
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
