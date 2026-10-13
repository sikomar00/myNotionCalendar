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
    colorHex = NotionColors.hexFor(event.colorKey) ?: sourceColorHex,
    sourceColorHex = sourceColorHex,
    url = event.url,
    pending = event.pendingOps > 0,
)

@Singleton
class EventRepository @Inject constructor(private val eventDao: EventDao) {
    /** Room 조회만 한다. 네트워크와 무관하게 즉시 마지막 데이터를 낸다(PF-02). */
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<CalendarEvent>> =
        eventDao.observeRange(from, to).map { rows -> rows.map { it.toCalendarEvent() } }
}

/** 편집 시트(WI-05)가 쓰는 일정 1개와 그 DB의 색 기준 선택지. */
data class EditableEvent(
    val event: CalendarEvent,
    val colorOptionId: String?,
    val colorOptions: List<ColorOption>,
    val dataSourceName: String,
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
                event = EventRow(event, source.colorHex).toCalendarEvent(),
                colorOptionId = event.colorOptionId,
                colorOptions = parseColorOptions(source.colorOptions),
                dataSourceName = source.name,
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
