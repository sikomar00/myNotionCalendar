package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** 위젯 테스트 공용 표본. 📅 Calendar DB 모양(Ctgr 색, 완료 취소선, 기간·시간 일정)을 본뜬다. */
object WidgetSamples {
    fun event(
        id: String,
        start: String,
        end: String? = null,
        color: String = "#0B6E99",
        done: Boolean = false,
        time: String? = null,
        source: String = "ds",
        title: String = id,
    ) = CalendarEvent(
        pageId = id, dataSourceId = source, title = title, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
        startTime = time?.let(Instant::parse), endTime = null, isAllDay = time == null, done = done, colorHex = color,
        sourceColorHex = color, url = "",
    )

    val october = YearMonth.of(2026, 10)
    val today: LocalDate = LocalDate.of(2026, 10, 13)

    val events = listOf(
        event("1", "2026-10-13", title = "영어 스터디"),
        event("2", "2026-10-13", time = "2026-10-12T23:30:00Z", color = "#DFAB01", title = "회의"),
        event("3", "2026-10-31", color = "#DFAB01", done = true, title = "바코프로젝트 기획"),
        event("4", "2026-10-30", "2026-11-02", color = "#6940A5", done = true, title = "데이터셋 선정"),
        event("5", "2026-10-15", color = "#0F7B6C", title = "문서정리 및 발표"),
        event("6", "2026-10-20", "2026-10-22", color = "#D9730D", title = "출장"),
        event("7", "2026-10-05", color = "#E03E3E", title = "운동", source = "other"),
    )

    /** 자식 10개 제한을 시험하는 빽빽한 데이터: 같은 날 8개 + 서로 겹치는 기간 일정 3개(+주 경계를 넘는 것 포함). */
    val dense = (1..8).map { event("d$it", "2026-10-14", title = "하루 일정 $it") } +
        listOf(
            event("s1", "2026-10-12", "2026-10-16", color = "#6940A5", title = "기간 A"),
            event("s2", "2026-10-13", "2026-10-15", color = "#D9730D", title = "기간 B"),
            event("s3", "2026-10-09", "2026-10-21", color = "#0F7B6C", title = "주를 넘는 기간"),
        ) + (1..31).map { event("m$it", "2026-10-%02d".format(it), title = "매일 $it") }

    fun state(events: List<CalendarEvent> = this.events, config: WidgetConfig = WidgetConfig(1)) =
        WidgetUiState(october, today, DayOfWeek.SUNDAY, events, config)
}
