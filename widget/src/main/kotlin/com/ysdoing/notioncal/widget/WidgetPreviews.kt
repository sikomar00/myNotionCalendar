package com.ysdoing.notioncal.widget

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * WG-08 위젯 선택 화면 미리보기. 위젯 목록은 다른 사람에게도 보일 수 있으므로 개인 일정이 아니라 예시 일정으로 그린다
 * (Room을 읽지 않는다). 날짜는 그리는 날 기준이라 이번 달·오늘이 자연스럽게 보인다.
 */
object WidgetPreviews {
    /** 미리보기 크기: 월 위젯 4×4, 보조 위젯 4×2(S25 기본 칸 크기 근사). */
    val MONTH_SIZE = DpSize(358.dp, 380.dp)
    val AGENDA_SIZE = DpSize(358.dp, 152.dp)

    private const val TAG = "NotionCalWidget"

    private fun sample(id: String, title: String, date: LocalDate, end: LocalDate? = null, time: LocalTime? = null, color: String, done: Boolean = false) =
        CalendarEvent(
            pageId = "preview-$id", dataSourceId = "preview", title = title, startDate = date, endDate = end,
            startTime = time?.let { date.atTime(it).atZone(APP_ZONE).toInstant() }, endTime = null, isAllDay = time == null,
            done = done, colorHex = color, sourceColorHex = color, url = "",
        )

    /** 예시 일정(오늘 기준). Ctgr 색(노랑·파랑·빨강)과 기간 일정·완료 표시를 함께 보인다. */
    fun sampleEvents(today: LocalDate): List<CalendarEvent> = listOf(
        sample("1", "팀 회의", today, time = LocalTime.of(10, 0), color = "#DFAB01"),
        sample("2", "영어 스터디", today, color = "#0B6E99"),
        sample("3", "운동", today.plusDays(1), color = "#E03E3E"),
        sample("4", "출장", today.plusDays(3), end = today.plusDays(5), color = "#D9730D"),
        sample("5", "보고서 마감", today.plusDays(6), time = LocalTime.of(18, 0), color = "#0B6E99"),
        sample("6", "장보기", today.minusDays(2), color = "#E03E3E", done = true),
    )

    fun monthState(today: LocalDate = LocalDate.now(APP_ZONE)): WidgetUiState =
        NotionCalWidget.uiState(YearMonth.from(today), today, AppSettings(weekStart = DayOfWeek.SUNDAY), WidgetConfig(0), sampleEvents(today))

    fun agendaState(today: LocalDate = LocalDate.now(APP_ZONE)): AgendaUiState =
        AgendaWidget.uiState(today, WidgetConfig(0), sampleEvents(today))

    /**
     * 두 위젯의 생성 미리보기를 시스템에 올린다(Android 15 이상). 시스템이 호출 횟수를 제한하므로 앱이 버전마다 한 번만 부른다.
     * 모두 성공하면 true.
     */
    suspend fun publish(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return true
        val manager = GlanceAppWidgetManager(context)
        val results = listOf(
            runCatching { manager.setWidgetPreviews(NotionCalWidgetReceiver::class) }.getOrNull(),
            runCatching { manager.setWidgetPreviews(AgendaWidgetReceiver::class) }.getOrNull(),
        )
        Log.d(TAG, "위젯 미리보기 게시: $results")
        return results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }
    }
}
