package com.ysdoing.notioncal.core.data.dday

import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DdayPinEntity
import com.ysdoing.notioncal.core.data.db.DdayPinRow
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 고정한 디데이 한 개. 일정 행이 아직 없으면(동기화 전) [label]이 null이다. */
data class Dday(
    val pageId: String,
    val title: String?,
    val start: LocalDate?,
    val end: LocalDate?,
    val mode: DdayMode,
    val hideAfterDays: Int,
    val colorHex: String?,
    val label: DdayLabel?,
)

/**
 * DD-01·02 디데이 고정. 고정 목록은 앱 상태 표(dday_pins)이고, 날짜·제목은 일정 캐시(events)에서 읽는다.
 * 노션에서 날짜를 바꾸면 동기화가 events를 고쳐 숫자가 따라간다. Room만 읽고 쓴다.
 */
@Singleton
class DdayRepository @Inject constructor(
    private val db: NotionCalDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val widgetRefresher: WidgetRefresher,
) {
    private val dao get() = db.ddayDao()

    fun today(): LocalDate = clock.instant().atZone(APP_ZONE).toLocalDate()

    /** 고정한 디데이 전부(지난 것 포함). 가까운 순, 동기화 전 행은 맨 뒤. */
    fun observe(today: LocalDate): Flow<List<Dday>> =
        dao.observe().map { rows -> rows.map { it.toDday(today) }.sortedBy { it.label?.sortKey ?: Long.MAX_VALUE } }

    /** 위젯·목록에 보일 디데이: 동기화된 것 중 숨김이 아닌 것. */
    fun observeVisible(today: LocalDate): Flow<List<Dday>> =
        observe(today).map { list -> list.filter { it.label != null && !it.label.hidden } }

    fun observePinnedIds(): Flow<Set<String>> = dao.observeIds().map { it.toSet() }.distinctUntilChanged()

    suspend fun pin(pageId: String) {
        if (dao.all().none { it.pageId == pageId }) dao.upsert(DdayPinEntity(pageId, sortOrder = dao.nextOrder()))
        widgetRefresher.requestUpdate()
    }

    suspend fun unpin(pageId: String) {
        dao.delete(pageId)
        widgetRefresher.requestUpdate()
    }

    suspend fun setMode(pageId: String, mode: DdayMode) {
        dao.setMode(pageId, mode.name)
        widgetRefresher.requestUpdate()
    }

    /** 노션에서 휴지통으로 가 고정을 푼 디데이 제목들(DD-01). */
    val removedNotice: Flow<Set<String>> = settings.settings.map { it.ddayRemovedNotice }.distinctUntilChanged()

    suspend fun dismissRemovedNotice() = settings.clearDdayRemovedNotice()

    private fun DdayPinRow.toDday(today: LocalDate): Dday {
        val mode = runCatching { DdayMode.valueOf(countMode) }.getOrDefault(DdayMode.DOWN)
        return Dday(
            pageId = pageId,
            title = title,
            start = startDate,
            end = endDate,
            mode = mode,
            hideAfterDays = hideAfterDays,
            colorHex = colorHex,
            label = startDate?.let { DdayMath.label(it, endDate, today, mode, hideAfterDays) },
        )
    }
}
