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
)

@Singleton
class EventRepository @Inject constructor(private val eventDao: EventDao) {
    /** Room 조회만 한다. 네트워크와 무관하게 즉시 마지막 데이터를 낸다(PF-02). */
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<CalendarEvent>> =
        eventDao.observeRange(from, to).map { rows -> rows.map { it.toCalendarEvent() } }
}

@Singleton
class DataSourceRepository @Inject constructor(
    private val db: NotionCalDatabase,
    private val dataSourceDao: DataSourceDao,
    private val eventDao: EventDao,
) {
    fun observeAll(): Flow<List<DataSourceEntity>> = dataSourceDao.observeAll()

    suspend fun setEnabled(id: String, enabled: Boolean) = dataSourceDao.setEnabled(id, enabled)

    /** 등록을 지우면 그 DB의 로컬 일정도 지운다. 노션 원본은 건드리지 않는다. */
    suspend fun delete(id: String) = db.withTransaction {
        eventDao.deleteForSource(id)
        dataSourceDao.deleteRow(id)
    }
}

@Singleton
class SyncStatusRepository @Inject constructor(private val syncLogDao: SyncLogDao) {
    val latest: Flow<SyncLogEntity?> = syncLogDao.observeLatest()
    val latestSuccess: Flow<SyncLogEntity?> = syncLogDao.observeLatestSuccess()
}
