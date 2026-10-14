package com.ysdoing.notioncal.core.data

import com.ysdoing.notioncal.core.data.db.HolidayDao
import com.ysdoing.notioncal.core.data.db.HolidayEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 공휴일 하나(EX-02). [source]는 읽어 온 폰 캘린더 종류("GOOGLE"·"SAMSUNG"). */
data class Holiday(val date: LocalDate, val name: String, val source: String)

/**
 * EX-02 공휴일. 폰의 공휴일 캘린더에서 읽어 Room에 둔 것을 화면·위젯이 읽는다(화면·위젯은 Room만 읽는다).
 * 설정에서 공휴일 표시를 끄면 빈 값을 낸다(저장된 공휴일은 남긴다).
 */
@Singleton
class HolidayRepository @Inject constructor(
    private val dao: HolidayDao,
    private val settings: SettingsRepository,
    private val widgetRefresher: WidgetRefresher,
) {
    fun observe(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, String>> =
        combine(settings.settings.map { it.showHolidays }.distinctUntilChanged(), dao.observeRange(from, to)) { show, rows ->
            if (show) rows.associate { it.date to it.name } else emptyMap()
        }.distinctUntilChanged()

    /** [from]~[to] 공휴일을 통째로 바꾸고 위젯을 다시 그린다. */
    suspend fun replace(from: LocalDate, to: LocalDate, holidays: List<Holiday>) {
        dao.replaceRange(from, to, holidays.map { HolidayEntity(it.date, it.name, it.source) })
        widgetRefresher.requestUpdate()
    }

    /** 진단 화면용: 저장된 공휴일의 출처(없으면 null). */
    suspend fun source(): String? = dao.anySource()
}
