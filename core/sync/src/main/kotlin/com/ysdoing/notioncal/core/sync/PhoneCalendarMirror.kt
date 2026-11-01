package com.ysdoing.notioncal.core.sync

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.EventRepository
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.NotionColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** EX-08 미러 한 줄 값(순수 함수, 테스트용으로 Map). */
internal object MirrorRows {
    const val ACCOUNT_NAME = "NotionCal"
    const val ACCOUNT_TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL
    const val DISPLAY_NAME = "노션 캘린더"

    /** 이 앱이 만든 로컬 계정 캘린더만 고른다(다른 캘린더는 건드리지 않는다). */
    val OWN_SELECTION = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
    val OWN_ARGS = arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE)

    fun values(event: CalendarEvent, calendarId: Long): Map<String, Any?> {
        val allDay = event.startTime == null
        val (start, end, zone) = if (allDay) {
            val last = event.endDate?.takeIf { it.isAfter(event.startDate) } ?: event.startDate
            Triple(
                event.startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                "UTC",
            )
        } else {
            val s = event.startTime!!
            Triple(s.toEpochMilli(), (event.endTime ?: s.plusSeconds(3600)).toEpochMilli(), APP_ZONE.id)
        }
        return mapOf(
            CalendarContract.Events.CALENDAR_ID to calendarId,
            CalendarContract.Events.TITLE to event.title,
            CalendarContract.Events.DTSTART to start,
            CalendarContract.Events.DTEND to end,
            CalendarContract.Events.ALL_DAY to if (allDay) 1 else 0,
            CalendarContract.Events.EVENT_TIMEZONE to zone,
        )
    }
}

/**
 * EX-08 시험 구현(디버그 메뉴만): 노션 일정(앞으로 [DAYS]일, 켠 일정 DB)을 폰 캘린더의 로컬 계정 캘린더 '노션 캘린더'에
 * 읽기 전용으로 복사한다. 삼성 캘린더를 거쳐 S25 나우 브리프 일정 카드에 뜨는지 보려는 것이다. 끄면 그 캘린더를 통째로 지운다.
 * 네트워크는 쓰지 않는다(Room → CalendarContract). 동기화가 끝날 때마다 켜져 있으면(캘린더가 있으면) 다시 맞춘다.
 */
@Singleton
class PhoneCalendarMirror @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventRepository,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun hasPermission(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** 켜기: 캘린더가 없으면 만들고 일정을 복사한다. 복사한 개수, 권한이 없으면 null. */
    suspend fun enable(): Int? = withContext(io) {
        if (!hasPermission()) return@withContext null
        val id = ownCalendarId() ?: createCalendar() ?: return@withContext null
        copyInto(id)
    }

    /** 끄기: 이 앱의 로컬 계정 캘린더를 지운다(일정도 함께 지워진다). 지운 캘린더 수. */
    suspend fun disable(): Int = withContext(io) {
        if (!hasPermission()) return@withContext 0
        context.contentResolver.delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), MirrorRows.OWN_SELECTION, MirrorRows.OWN_ARGS)
    }

    /** 동기화 뒤: 켜져 있으면(캘린더가 있으면) 다시 맞춘다. 실패해도 동기화에는 영향이 없다. */
    suspend fun refreshIfEnabled() = withContext(io) {
        if (!hasPermission()) return@withContext
        runCatching { ownCalendarId()?.let { copyInto(it) } }.onFailure { Log.w(TAG, "미러 갱신 실패: ${it.javaClass.simpleName}") }
    }

    private fun ownCalendarId(): Long? = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), MirrorRows.OWN_SELECTION, MirrorRows.OWN_ARGS, null,
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun createCalendar(): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, MirrorRows.ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, MirrorRows.ACCOUNT_TYPE)
            put(CalendarContract.Calendars.NAME, MirrorRows.DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, MirrorRows.DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, NotionColors.argb("#2383E2"))
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, MirrorRows.ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, APP_ZONE.id)
        }
        return context.contentResolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), values)?.lastPathSegment?.toLongOrNull()
    }

    /** 그 캘린더의 일정을 지우고 지금 Room의 앞으로 [DAYS]일 일정으로 다시 채운다. */
    private suspend fun copyInto(calendarId: Long): Int {
        val resolver = context.contentResolver
        val eventsUri = asSyncAdapter(CalendarContract.Events.CONTENT_URI)
        resolver.delete(eventsUri, "${CalendarContract.Events.CALENDAR_ID} = ?", arrayOf(calendarId.toString()))
        val today = clock.instant().atZone(APP_ZONE).toLocalDate()
        val list = events.observeRange(today, today.plusDays(DAYS)).first().filterNot { it.done }
        val rows = list.map { e ->
            ContentValues().apply {
                MirrorRows.values(e, calendarId).forEach { (k, v) ->
                    when (v) {
                        is Long -> put(k, v)
                        is Int -> put(k, v)
                        is String -> put(k, v)
                        null -> putNull(k)
                    }
                }
            }
        }.toTypedArray()
        return if (rows.isEmpty()) 0 else resolver.bulkInsert(eventsUri, rows)
    }

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, MirrorRows.ACCOUNT_NAME)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, MirrorRows.ACCOUNT_TYPE)
        .build()

    private companion object {
        const val TAG = "NotionCalMirror"
        const val DAYS = 30L
    }
}
