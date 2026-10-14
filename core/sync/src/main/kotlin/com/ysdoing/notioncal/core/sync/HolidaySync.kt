package com.ysdoing.notioncal.core.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.Holiday
import com.ysdoing.notioncal.core.data.HolidayRepository
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** 폰 캘린더 목록 한 줄(필요한 열만). */
data class CalendarRow(val id: Long, val ownerAccount: String?, val displayName: String?, val syncEvents: Boolean)

/** 공휴일 캘린더의 일정 한 개(반복은 펼친 상태). [begin]은 epoch ms. 종일 일정의 begin은 UTC 자정이다. */
data class HolidayInstance(val calendarId: Long, val title: String, val description: String?, val begin: Long, val allDay: Boolean)

/**
 * EX-02 공휴일 캘린더 고르기·일정 거르기(순수 함수).
 * - 구글 '대한민국의 휴일'(ownerAccount `*.south_korea#holiday@group.v.calendar.google.com`, 동기화 켜짐)을 먼저 쓴다.
 *   설·추석 연휴와 대체공휴일이 모두 있고, 설명이 "공휴일"인 일정만 쉬는 날이다(어버이날·스승의날 같은 기념일 제외).
 * - 없으면 삼성 '공휴일'(ownerAccount `local.samsungholiday`)을 쓴다. 이 캘린더는 연휴 날이 빠져 있다.
 */
object HolidayPicker {
    const val GOOGLE = "GOOGLE"
    const val SAMSUNG = "SAMSUNG"
    private const val GOOGLE_SUFFIX = "#holiday@group.v.calendar.google.com"
    private const val SAMSUNG_OWNER = "local.samsungholiday"
    private const val SAMSUNG_NAME = "공휴일"

    /** 구글 공휴일 일정의 설명 값(한국어·영어 캘린더). */
    private val PUBLIC_HOLIDAY = setOf("공휴일", "Public holiday")

    data class Choice(val source: String, val calendarIds: List<Long>)

    fun choose(calendars: List<CalendarRow>): Choice? {
        val google = calendars.filter { row ->
            val owner = row.ownerAccount.orEmpty()
            row.syncEvents && owner.endsWith(GOOGLE_SUFFIX) && "south_korea" in owner
        }.sortedBy { if (it.ownerAccount.orEmpty().startsWith("ko.")) 0 else 1 } // 한국어 이름을 먼저
        if (google.isNotEmpty()) return Choice(GOOGLE, google.map { it.id })
        val samsung = calendars.filter { it.ownerAccount == SAMSUNG_OWNER && it.displayName == SAMSUNG_NAME }
        if (samsung.isNotEmpty()) return Choice(SAMSUNG, samsung.map { it.id })
        return null
    }

    /** [choice] 캘린더의 일정에서 [from]~[to] 공휴일을 날짜별 하나로. 같은 날이면 [Choice.calendarIds] 순서가 먼저인 이름을 쓴다. */
    fun holidays(choice: Choice, instances: List<HolidayInstance>, from: LocalDate, to: LocalDate): List<Holiday> {
        val byCalendar = instances.groupBy { it.calendarId }
        val result = linkedMapOf<LocalDate, Holiday>()
        for (calendarId in choice.calendarIds) {
            val rows = byCalendar[calendarId].orEmpty()
            // 설명으로 공휴일을 가를 수 있으면 그것만 쓴다(설명이 전부 비어 있으면 모두 쓴다).
            val filtered = if (choice.source == GOOGLE && rows.any { !it.description.isNullOrBlank() }) {
                rows.filter { it.description?.trim() in PUBLIC_HOLIDAY }
            } else {
                rows
            }
            for (row in filtered) {
                val date = dateOf(row)
                if (date < from || date > to || date in result) continue
                result[date] = Holiday(date, row.title.trim(), choice.source)
            }
        }
        return result.values.sortedBy { it.date }
    }

    /** 종일 일정은 UTC 자정 기준 날짜(CalendarContract 규칙), 시간 일정은 서울 날짜. */
    fun dateOf(row: HolidayInstance): LocalDate {
        val instant = Instant.ofEpochMilli(row.begin)
        return if (row.allDay) instant.atZone(ZoneOffset.UTC).toLocalDate() else instant.atZone(APP_ZONE).toLocalDate()
    }
}

/**
 * EX-02: 폰의 공휴일 캘린더(CalendarContract)를 읽어 Room에 복사한다. 화면·위젯은 Room만 읽는다.
 * 권한(READ_CALENDAR)이 없거나 공휴일 캘린더가 없으면 아무것도 하지 않는다. 네트워크는 쓰지 않는다.
 */
@Singleton
class HolidaySync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holidays: HolidayRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** 읽어 온 출처("GOOGLE"·"SAMSUNG")를 돌려준다. 권한·캘린더가 없으면 null. */
    suspend fun refresh(): String? = withContext(io) {
        if (!hasPermission(context)) return@withContext null
        val today = clock.instant().atZone(APP_ZONE).toLocalDate()
        val from = LocalDate.of(today.year, 1, 1)
        val to = LocalDate.of(today.year + 1, 12, 31)
        val result = runCatching {
            val choice = HolidayPicker.choose(queryCalendars()) ?: return@runCatching null
            val list = HolidayPicker.holidays(choice, queryInstances(choice.calendarIds, from, to), from, to)
            holidays.replace(from, to, list)
            choice.source
        }.onFailure { Log.w(TAG, "공휴일 읽기 실패", it) }.getOrNull()
        settings.setHolidaysRefreshedAt(clock.millis())
        result
    }

    private fun queryCalendars(): List<CalendarRow> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.SYNC_EVENTS,
        )
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(CalendarRow(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1))
            }
        }.orEmpty()
    }

    private fun queryInstances(calendarIds: List<Long>, from: LocalDate, to: LocalDate): List<HolidayInstance> {
        // 종일 일정은 UTC 자정이라 하루 여유를 두고 읽은 뒤 날짜로 거른다.
        val begin = from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})"
        val args = calendarIds.map { it.toString() }.toTypedArray()
        return context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(HolidayInstance(c.getLong(0), c.getString(1).orEmpty(), c.getString(2), c.getLong(3), c.getInt(4) == 1))
                }
            }
        }.orEmpty()
    }

    companion object {
        private const val TAG = "NotionCalHoliday"

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
}

/** EX-02 공휴일 다시 읽기 작업. 네트워크 조건이 없다. */
@HiltWorker
class HolidayWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: HolidaySync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        sync.refresh()
        return Result.success()
    }
}

/** 공휴일 다시 읽기 요청. 앱 열기 때 하루 한 번, 설정에서 켤 때 바로. */
@Singleton
class HolidayScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    fun requestNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HolidayWorker>().build(),
        )
    }

    /** 앱 설정에서 캘린더 권한을 거둬 갔으면 공휴일 표시를 끈다(저장된 공휴일을 계속 보이지 않게). 앱이 보일 때마다 부른다. */
    suspend fun dropIfPermissionRevoked() {
        if (settings.settings.first().showHolidays && !HolidaySync.hasPermission(context)) settings.setShowHolidays(false)
    }

    /** 공휴일 표시가 켜져 있고 권한이 있으며 마지막으로 읽은 지 하루가 지났으면 다시 읽는다. */
    suspend fun requestIfStale() {
        val current = settings.settings.first()
        if (!current.showHolidays || !HolidaySync.hasPermission(context)) return
        if (Duration.ofMillis(clock.millis() - current.holidaysRefreshedAt) < REFRESH_INTERVAL) return
        requestNow()
    }

    private companion object {
        const val WORK_NAME = "holiday-refresh"
        val REFRESH_INTERVAL: Duration = Duration.ofDays(1)
    }
}
